package io.github.lukashornych.hole.idea.process;

import com.intellij.util.EnvironmentUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs short-lived, non-interactive command line processes (`hole list`, `hole version`, `git worktree list`) and
 * captures their output. Blocks the calling thread, so it must not be called on the EDT.
 *
 * <p>Every process gets exactly the environment from the injected supplier, by default the user's shell environment
 * from {@link EnvironmentUtil#getEnvironmentMap()}, and not the IDE's own environment. The executable (first element
 * of the command) is resolved on the `PATH` of that environment. The environment is never logged.</p>
 *
 * <p>Not for `hole start`, which needs a real TTY and runs through pty4j.</p>
 */
public final class CliRunner {

    /** The user's shell environment as loaded by the IDE. */
    public static final Supplier<Map<String, String>> SHELL_ENVIRONMENT = EnvironmentUtil::getEnvironmentMap;

    private static final Duration TERMINATE_GRACE = Duration.ofSeconds(2);

    private final Supplier<Map<String, String>> environment;

    /**
     * @param environment supplier of the environment every process gets
     */
    public CliRunner(@NotNull Supplier<Map<String, String>> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Returns the environment processes run with.
     */
    @NotNull
    public Map<String, String> environment() {
        return this.environment.get();
    }

    /**
     * Runs the command and waits for it to finish.
     *
     * @param command          executable followed by its arguments, passed as they are (no shell)
     * @param workingDirectory working directory, or {@code null} for the IDE's
     * @param timeout          how long to wait; the process is killed afterwards
     * @return the result, whatever the exit code
     * @throws CliException when the executable is not found, the process cannot start or does not finish in time
     */
    @NotNull
    public CliResult run(@NotNull List<String> command, @Nullable Path workingDirectory, @NotNull Duration timeout)
        throws CliException {
        if (command.isEmpty()) {
            throw new IllegalArgumentException("Command must not be empty.");
        }
        final Map<String, String> env = this.environment.get();
        final Path executable = ExecutableLocator.resolve(command.get(0), env)
            .orElseThrow(() -> new CliException("Executable not found", command, null));

        final List<String> resolvedCommand = new ArrayList<>(command);
        resolvedCommand.set(0, executable.toString());

        final ProcessBuilder builder = new ProcessBuilder(resolvedCommand);
        builder.environment().clear();
        builder.environment().putAll(env);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }

        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new CliException("Process could not be started: " + e.getMessage(), resolvedCommand, null, e);
        }
        try {
            process.getOutputStream().close();
            final long deadline = System.nanoTime() + timeout.toNanos();
            final CompletableFuture<String> stdout = readAsync(process.getInputStream());
            final CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                throw new CliException("Process did not finish within " + timeout.toSeconds() + " s", resolvedCommand, null);
            }
            // a background child that inherited the pipes could keep them open after the process itself exited
            final long remaining = Math.max(0, deadline - System.nanoTime());
            return new CliResult(
                resolvedCommand,
                process.exitValue(),
                stdout.get(remaining, TimeUnit.NANOSECONDS),
                stderr.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            );
        } catch (TimeoutException e) {
            throw new CliException(
                "Process output was not closed within " + timeout.toSeconds() + " s", resolvedCommand, process.exitValue(), e
            );
        } catch (InterruptedException e) {
            // SIGTERM first even when interrupted: `hole list` may be in a GC teardown that must not be cut short
            terminateQuietly(process);
            Thread.currentThread().interrupt();
            throw new CliException("Interrupted while waiting for the process", resolvedCommand, null, e);
        } catch (ExecutionException | IOException e) {
            terminateQuietly(process);
            throw new CliException("Process output could not be read: " + e.getMessage(), resolvedCommand, null, e);
        }
    }

    /**
     * Asks the process to stop (SIGTERM), so e.g. Hole can finish a teardown it is in, and kills it if it does not
     * stop within a short grace period.
     */
    private static void terminate(@NotNull Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(TERMINATE_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
        }
    }

    /**
     * {@link #terminate(Process)} for paths that already handle an interruption: a further interrupt during the grace
     * period kills the process at once and keeps the thread's interrupt flag set.
     */
    private static void terminateQuietly(@NotNull Process process) {
        try {
            terminate(process);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @NotNull
    private static CompletableFuture<String> readAsync(@NotNull InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, runnable -> Thread.ofVirtual().name("Hole CLI output reader").start(runnable));
    }
}
