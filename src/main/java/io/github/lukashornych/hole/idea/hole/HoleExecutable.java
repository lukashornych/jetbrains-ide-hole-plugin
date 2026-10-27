package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.serviceContainer.NonInjectable;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.process.ExecutableLocator;
import io.github.lukashornych.hole.idea.storage.HolePluginSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Locates the `hole` executable and checks that it is new enough for the plugin: Hole {@link HoleVersion#MINIMUM}
 * (2.6.0), the release with `--instance-id`, `list --json`, `list --no-gc` and `settings get`.
 *
 * <p>Location order: the path from <i>Settings › Tools › Hole</i> when set (`~/` expands to the home directory; used exclusively, so a typo is reported
 * instead of silently using another binary), otherwise `hole` on the `PATH` of the user's shell environment, otherwise
 * `~/.local/bin/hole` (Hole's install location).</p>
 *
 * <p>The capability probe runs `hole version` once (it needs no container engine) and parses it with
 * {@link HoleVersion}: a release or `go install` build of at least 2.6.0 and a development build are
 * {@link HoleCapability.Status#SUPPORTED}; an older one is {@link HoleCapability.Status#UNSUPPORTED} (the user must run
 * `hole update`; there is no fallback for older Hole versions); output that cannot be parsed, or a `hole version`
 * that fails or does not run, is {@link HoleCapability.Status#UNAVAILABLE}, which still allows starting, so Hole can
 * show its own error in the tab. A positive result is cached per executable path and modification time, so
 * `hole update` or a changed setting triggers a new probe. Negative results are not cached, so a probe that failed
 * for a transient reason is retried on the next call.</p>
 */
@Service(Service.Level.APP)
public final class HoleExecutable {

    private static final Logger LOG = Logger.getInstance(HoleExecutable.class);
    private static final String EXECUTABLE_NAME = "hole";
    /** The probe runs before a start; `hole version` answers at once, the budget only guards against a hang. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);
    /** The notice for a Hole that is too old. */
    public static final String UPDATE_NOTICE = "This plugin needs Hole " + HoleVersion.MINIMUM + " or newer. Run `hole update`.";

    private final Supplier<String> configuredPath;
    private final CliRunner runner;
    private final Object cacheLock = new Object();
    /** Executable for which the probe succeeded. Guarded by {@link #cacheLock}. */
    private ProbeKey supportedKey;
    /** Probe run in progress, shared by concurrent callers. Guarded by {@link #cacheLock}. */
    private CompletableFuture<HoleCapability> inFlight;
    /** Executable {@link #inFlight} probes. Guarded by {@link #cacheLock}. */
    private ProbeKey inFlightKey;
    private volatile HoleCapability last;

    /**
     * Platform constructor: settings from {@link HolePluginSettings}, environment from the user's shell.
     */
    public HoleExecutable() {
        this(() -> HolePluginSettings.getInstance().getHoleExecutablePath(), new CliRunner(CliRunner.SHELL_ENVIRONMENT));
    }

    /**
     * @param configuredPath supplier of the configured executable path (blank for detection)
     * @param runner         runs the probe with the user's shell environment
     */
    @NonInjectable
    public HoleExecutable(@NotNull Supplier<String> configuredPath, @NotNull CliRunner runner) {
        this.configuredPath = Objects.requireNonNull(configuredPath, "configuredPath");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Returns the application-wide instance.
     */
    @NotNull
    public static HoleExecutable getInstance() {
        return ApplicationManager.getApplication().getService(HoleExecutable.class);
    }

    /**
     * Returns the runner that every Hole CLI call of the plugin should use.
     */
    @NotNull
    public CliRunner runner() {
        return this.runner;
    }

    /**
     * Locates the `hole` executable. Only file system checks; cheap, but call off the EDT because the shell
     * environment may still be loading.
     */
    @NotNull
    public Optional<Path> locate() {
        final Map<String, String> environment = this.runner.environment();
        final String home = environment.getOrDefault("HOME", System.getProperty("user.home"));
        final String configured = Objects.requireNonNullElse(this.configuredPath.get(), "").trim();
        if (!configured.isEmpty()) {
            try {
                final Path path = configured.startsWith("~/") && home != null
                    ? Path.of(home, configured.substring(2))
                    : Path.of(configured);
                return ExecutableLocator.isExecutableFile(path) ? Optional.of(path) : Optional.empty();
            } catch (InvalidPathException e) {
                return Optional.empty();
            }
        }
        final Optional<Path> onPath = ExecutableLocator.findOnPath(EXECUTABLE_NAME, environment);
        if (onPath.isPresent()) {
            return onPath;
        }
        if (home == null || home.isBlank()) {
            return Optional.empty();
        }
        final Path localBin = Path.of(home, ".local", "bin", EXECUTABLE_NAME);
        return ExecutableLocator.isExecutableFile(localBin) ? Optional.of(localBin) : Optional.empty();
    }

    /**
     * Locates Hole and checks its version. Blocking (runs `hole version` unless a positive result is cached); call
     * off the EDT. Concurrent callers for the same executable share one probe run, so e.g. several favourite starts
     * in a row do not spawn several `hole version` processes.
     */
    @NotNull
    public HoleCapability probe() {
        final Optional<Path> located = locate();
        if (located.isEmpty()) {
            final String configured = Objects.requireNonNullElse(this.configuredPath.get(), "").trim();
            return remember(new HoleCapability(
                HoleCapability.Status.NOT_FOUND,
                null,
                configured.isEmpty()
                    ? "Hole was not found on PATH or in ~/.local/bin. Install Hole or set its path in Settings | Tools | Hole."
                    : "The configured Hole executable '" + configured + "' does not exist or is not executable."
            ));
        }
        final Path executable = located.get();
        final ProbeKey key = ProbeKey.of(executable);
        final CompletableFuture<HoleCapability> run;
        final boolean owner;
        synchronized (this.cacheLock) {
            if (key.equals(this.supportedKey)) {
                return remember(new HoleCapability(HoleCapability.Status.SUPPORTED, executable, ""));
            }
            if (this.inFlight != null && key.equals(this.inFlightKey)) {
                run = this.inFlight;
                owner = false;
            } else {
                run = new CompletableFuture<>();
                this.inFlight = run;
                this.inFlightKey = key;
                owner = true;
            }
        }
        if (owner) {
            HoleCapability result;
            try {
                result = runProbe(executable);
            } catch (RuntimeException | Error e) {
                result = new HoleCapability(HoleCapability.Status.UNAVAILABLE, executable, "`hole version` failed: " + e.getMessage());
            }
            synchronized (this.cacheLock) {
                if (result.isSupported()) {
                    this.supportedKey = key;
                }
                if (this.inFlight == run) {
                    this.inFlight = null;
                    this.inFlightKey = null;
                }
            }
            run.complete(result);
            return remember(result);
        }
        return remember(run.join());
    }

    /**
     * Returns the result of the most recent {@link #probe()}, {@code null} before the first one. Non-blocking, so UI
     * code on the EDT can disable start actions without probing; the start itself probes again.
     */
    @Nullable
    public HoleCapability lastCapability() {
        return this.last;
    }

    @NotNull
    private HoleCapability remember(@NotNull HoleCapability capability) {
        this.last = capability;
        return capability;
    }

    @NotNull
    private HoleCapability runProbe(@NotNull Path executable) {
        final CliResult result;
        try {
            result = this.runner.run(HoleCommandBuilder.version(executable.toString()), null, PROBE_TIMEOUT);
        } catch (CliException e) {
            LOG.info("Hole version probe failed: " + e.getMessage());
            return new HoleCapability(HoleCapability.Status.UNAVAILABLE, executable, "`hole version` failed: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            LOG.info("Hole version probe failed: " + new CliException(result, "`hole version` failed").getMessage());
            return new HoleCapability(
                HoleCapability.Status.UNAVAILABLE,
                executable,
                "`hole version` failed with exit code " + result.exitCode()
                    + (result.firstStderrLine().isEmpty() ? "." : ": " + result.firstStderrLine())
            );
        }
        final Optional<HoleVersion> version = HoleVersion.parse(result.stdout());
        if (version.isEmpty()) {
            LOG.info("Hole version probe: unrecognized `hole version` output from " + executable);
            return new HoleCapability(
                HoleCapability.Status.UNAVAILABLE,
                executable,
                "The version of " + executable + " could not be recognized."
            );
        }
        if (!version.get().isAtLeastMinimum()) {
            LOG.info("Hole " + version.get() + " at " + executable + " is older than " + HoleVersion.MINIMUM);
            return new HoleCapability(HoleCapability.Status.UNSUPPORTED, executable, UPDATE_NOTICE);
        }
        if (version.get().development()) {
            LOG.info("Hole at " + executable + " is a development build; assuming it supports everything the plugin needs");
        }
        return new HoleCapability(HoleCapability.Status.SUPPORTED, executable, "");
    }

    /**
     * Identity of a probed executable: path plus modification time.
     */
    private record ProbeKey(@NotNull Path path, @NotNull FileTime modified) {

        @NotNull
        static ProbeKey of(@NotNull Path path) {
            FileTime modified;
            try {
                modified = Files.getLastModifiedTime(path);
            } catch (IOException e) {
                modified = FileTime.fromMillis(0);
            }
            return new ProbeKey(path.toAbsolutePath(), modified);
        }
    }
}
