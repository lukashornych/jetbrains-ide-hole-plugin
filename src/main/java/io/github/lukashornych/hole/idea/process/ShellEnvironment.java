package io.github.lukashornych.hole.idea.process;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Reads the environment of the user's login-interactive shell (`$SHELL -l -i -c '/usr/bin/env -0 > <dir>/env'`), i.e.
 * what a new terminal tab has after sourcing the rc files.
 *
 * <p>{@link EnvironmentUtil#getEnvironmentMap()} is not enough: the platform skips loading it when the IDE looks
 * launched from a terminal (`SHLVL > 0`), falls back to the IDE's own environment silently on a timeout or a failing
 * shell, and never refreshes it after IDE start.</p>
 *
 * <p>The environment goes to a temporary file, never to stdout, so whatever rc files print is harmless. The shell
 * gets {@value #READER_MARKER}, the platform's marker that rc files use to skip tmux auto-attach and the like.
 * Concurrent reads share one shell run. Neither values nor rc output are ever logged.</p>
 */
@Service(Service.Level.APP)
public final class ShellEnvironment {

    private static final Logger LOG = Logger.getInstance(ShellEnvironment.class);
    /** A start waits for the read; a typical login shell needs well under a second. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** A read slower than this shows a cancellable background progress. */
    private static final Duration PROGRESS_DELAY = Duration.ofSeconds(1);
    /** The marker the platform sets for its own shell environment reader (rc files test it for non-emptiness). */
    static final String READER_MARKER = "INTELLIJ_ENVIRONMENT_READER";
    /** Variables that describe the reading shell itself, not the user's environment. */
    private static final Set<String> SHELL_OWN = Set.of("_", "PWD", "OLDPWD", "SHLVL", READER_MARKER);

    private final Supplier<Map<String, String>> base;
    private final Supplier<Map<String, String>> fallback;
    private final Duration timeout;
    private final boolean supported;
    private final Object lock = new Object();
    /** Read in progress, shared by concurrent callers. Guarded by {@link #lock}. */
    private CompletableFuture<Map<String, String>> inFlight;
    /** The most recent read failed and none succeeded since. Guarded by {@link #lock}. */
    private boolean failed;
    private volatile Map<String, String> latest;
    private final AtomicBoolean fallbackLogged = new AtomicBoolean();

    /**
     * Platform constructor: the IDE's environment as base, the platform's snapshot as fallback; not read on Windows.
     */
    public ShellEnvironment() {
        this(System::getenv, EnvironmentUtil::getEnvironmentMap, TIMEOUT, !SystemInfo.isWindows);
    }

    /**
     * @param base      environment the shell is started with; its `SHELL` names the shell (`/bin/sh` without one)
     * @param fallback  environment {@link #latest()} returns when no read has succeeded
     * @param timeout   how long a read may take
     * @param supported {@code false} makes every call return {@code fallback} without running a shell
     */
    @NonInjectable
    public ShellEnvironment(
        @NotNull Supplier<Map<String, String>> base,
        @NotNull Supplier<Map<String, String>> fallback,
        @NotNull Duration timeout,
        boolean supported
    ) {
        this.base = Objects.requireNonNull(base, "base");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.supported = supported;
    }

    /**
     * Returns the application-wide instance.
     */
    @NotNull
    public static ShellEnvironment getInstance() {
        return ApplicationManager.getApplication().getService(ShellEnvironment.class);
    }

    /**
     * Runs the shell and returns its environment; a read already in progress is joined instead. Blocking, call off
     * the EDT. A success becomes {@link #latest()}.
     *
     * @throws ShellEnvironmentException when the shell fails, times out or writes no environment
     */
    @NotNull
    public Map<String, String> read() throws ShellEnvironmentException {
        if (!this.supported) {
            return this.fallback.get();
        }
        return await(readAsync());
    }

    /**
     * {@link #read()} for a start: when the read takes longer than a second, a background progress shows it, and
     * cancelling the progress stops waiting (the shell itself still ends by its timeout). Two starts in that second
     * each show their own progress for the one shared read. Blocking, call off the EDT.
     *
     * @param project project the progress belongs to
     * @throws ShellEnvironmentException when the read fails, or {@linkplain ShellEnvironmentException#isCancelled()
     *                                   cancelled} when the user cancelled the progress
     */
    @NotNull
    public Map<String, String> readShowingProgress(@Nullable Project project) throws ShellEnvironmentException {
        if (!this.supported) {
            return this.fallback.get();
        }
        final CompletableFuture<Map<String, String>> read = readAsync();
        try {
            read.get(PROGRESS_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            final String shell = shellOf(this.base.get());
            final CompletableFuture<Void> cancelled = new CompletableFuture<>();
            ProgressManager.getInstance().run(new Task.Backgroundable(project, "Reading shell environment", true) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    indicator.setIndeterminate(true);
                    indicator.setText("Reading the environment of " + shell);
                    while (!read.isDone()) {
                        if (indicator.isCanceled()) {
                            cancelled.complete(null);
                            return;
                        }
                        try {
                            read.get(100, TimeUnit.MILLISECONDS);
                        } catch (TimeoutException | ExecutionException ignored) {
                            // checked again by the loop
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            cancelled.complete(null);
                            return;
                        }
                    }
                }

                @Override
                public void onCancel() {
                    cancelled.complete(null);
                }
            });
            CompletableFuture.anyOf(read, cancelled).exceptionally(failure -> null).join();
            if (!read.isDone()) {
                throw ShellEnvironmentException.cancelled(shell);
            }
        } catch (ExecutionException e) {
            // reported by await below
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ShellEnvironmentException.cancelled(shellOf(this.base.get()));
        }
        return await(read);
    }

    /**
     * Returns the environment of the most recent successful read, for the short-lived CLI calls. Before the first
     * success it waits for a read (starting one unless the last one failed); without any success it returns the
     * platform's snapshot and logs that once. Blocking until the first read ended, call off the EDT.
     */
    @NotNull
    public Map<String, String> latest() {
        final Map<String, String> current = this.latest;
        if (current != null) {
            return current;
        }
        if (!this.supported) {
            return this.fallback.get();
        }
        final CompletableFuture<Map<String, String>> read;
        synchronized (this.lock) {
            read = this.inFlight != null || !this.failed ? readAsync() : null;
        }
        if (read != null) {
            try {
                return await(read);
            } catch (ShellEnvironmentException e) {
                if (this.fallbackLogged.compareAndSet(false, true)) {
                    LOG.info("Shell environment could not be read (" + e.getMessage()
                        + "); Hole CLI calls use the IDE's environment until a sandbox start reads it");
                }
            }
        }
        return this.fallback.get();
    }

    @NotNull
    private CompletableFuture<Map<String, String>> readAsync() {
        final CompletableFuture<Map<String, String>> read;
        synchronized (this.lock) {
            if (this.inFlight != null) {
                return this.inFlight;
            }
            read = new CompletableFuture<>();
            this.inFlight = read;
        }
        // a platform thread: Process.waitFor would pin a virtual thread's carrier for the whole read
        Thread.ofPlatform().daemon().name("Hole shell environment reader").start(() -> {
            Map<String, String> environment = null;
            Throwable failure = null;
            try {
                environment = runShell();
            } catch (ShellEnvironmentException e) {
                failure = e;
            } catch (RuntimeException | Error e) {
                failure = new ShellEnvironmentException(shellOf(this.base.get()), "failed unexpectedly: " + e);
            }
            synchronized (this.lock) {
                this.inFlight = null;
                this.failed = environment == null;
                if (environment != null) {
                    this.latest = environment;
                }
            }
            if (environment != null) {
                this.fallbackLogged.set(false);
                read.complete(environment);
            } else {
                read.completeExceptionally(failure);
            }
        });
        return read;
    }

    @NotNull
    private Map<String, String> runShell() throws ShellEnvironmentException {
        final Map<String, String> baseEnvironment = this.base.get();
        final String shell = shellOf(baseEnvironment);
        if (!baseEnvironment.containsKey("SHELL")) {
            LOG.info("SHELL is not set in the IDE's environment; reading the environment of " + shell);
        }
        // the shell creates the file itself: `>` onto an existing file fails with `noclobber` set in an rc file
        final Path directory;
        try {
            directory = Files.createTempDirectory("hole-shell-env",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException | UnsupportedOperationException e) {
            throw new ShellEnvironmentException(shell, "a temporary directory could not be created: " + e.getMessage());
        }
        final Path file = directory.resolve("env");
        try {
            if (file.toString().contains("'")) {
                throw new ShellEnvironmentException(shell, "the temporary directory " + directory + " cannot be quoted");
            }
            final List<String> command = new ArrayList<>();
            command.add(shell);
            if (!isCsh(shell)) {
                // csh and tcsh reject -l together with -c
                command.add("-l");
            }
            command.add("-i");
            command.add("-c");
            command.add("/usr/bin/env -0 > '" + file + "'");
            final Map<String, String> environment = new HashMap<>(baseEnvironment);
            environment.put(READER_MARKER, "true");

            final CliResult result;
            try {
                result = new CliRunner(() -> environment).run(command, home(baseEnvironment), this.timeout);
            } catch (CliException e) {
                throw new ShellEnvironmentException(shell, reason(e));
            }
            if (!result.isSuccess()) {
                // not CliException(result, …): it would quote stderr, i.e. rc-file output
                throw new ShellEnvironmentException(shell, "exited with code " + result.exitCode());
            }
            if (!Files.isRegularFile(file)) {
                throw new ShellEnvironmentException(shell, "wrote no environment");
            }
            final Map<String, String> parsed = parse(Files.readAllBytes(file));
            if (parsed.isEmpty()) {
                throw new ShellEnvironmentException(shell, "wrote no environment");
            }
            return parsed;
        } catch (IOException e) {
            throw new ShellEnvironmentException(shell, "the environment could not be read back: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            } catch (IOException e) {
                LOG.info("Could not delete the temporary shell environment directory " + directory + ": " + e.getMessage());
            }
        }
    }

    /**
     * Parses `env -0` output: NUL-separated `NAME=value` entries whose values may contain `=` and newlines. The
     * variables of the reading shell itself are dropped.
     */
    @NotNull
    static Map<String, String> parse(byte @NotNull [] bytes) {
        final Map<String, String> environment = new HashMap<>();
        for (String entry : new String(bytes, StandardCharsets.UTF_8).split("\0")) {
            final int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            final String name = entry.substring(0, separator);
            if (!SHELL_OWN.contains(name)) {
                environment.put(name, entry.substring(separator + 1));
            }
        }
        return Map.copyOf(environment);
    }

    @NotNull
    private static Map<String, String> await(@NotNull CompletableFuture<Map<String, String>> read) throws ShellEnvironmentException {
        try {
            return read.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ShellEnvironmentException failure) {
                throw failure;
            }
            throw new IllegalStateException("Unexpected shell environment failure", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ShellEnvironmentException.cancelled("shell");
        }
    }

    @NotNull
    private static String shellOf(@NotNull Map<String, String> environment) {
        final String shell = environment.get("SHELL");
        return shell == null || shell.isBlank() ? "/bin/sh" : shell;
    }

    private static boolean isCsh(@NotNull String shell) {
        final String name = shell.substring(shell.lastIndexOf('/') + 1);
        return name.equals("csh") || name.equals("tcsh");
    }

    @Nullable
    private static Path home(@NotNull Map<String, String> environment) {
        final String home = environment.getOrDefault("HOME", System.getProperty("user.home"));
        try {
            return home != null && Files.isDirectory(Path.of(home)) ? Path.of(home) : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * The reason of a failed run without the command line ({@link CliException} appends it).
     */
    @NotNull
    private String reason(@NotNull CliException e) {
        if (e.getMessage().startsWith("Executable not found")) {
            return "the shell was not found";
        }
        if (e.getMessage().startsWith("Process did not finish")) {
            return "timed out after " + this.timeout.toSeconds() + " s";
        }
        final int command = e.getMessage().indexOf(" [command: ");
        return command < 0 ? e.getMessage() : e.getMessage().substring(0, command);
    }
}
