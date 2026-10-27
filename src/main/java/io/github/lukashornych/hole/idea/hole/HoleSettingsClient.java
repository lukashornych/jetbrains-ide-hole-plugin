package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.diagnostic.Logger;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks Hole where its settings files are, through `hole settings get global` and
 * `hole settings get project <path>`. Hole resolves the paths exactly as `hole start` does (including the
 * `HOLE_SETTINGS_FILE` / `HOLE_PROJECT_SETTINGS_FILE` overrides from the environment it runs with), so the plugin does
 * not duplicate those rules.
 *
 * <p>`settings get` prints the absolute path and exits 0 when the file exists; otherwise it fails with
 * `settings file '<path>' does not exist; create it with '…'`. For the global file the path is taken from that
 * message, so the dialog can show where the file would be; nothing is ever created. Blocking; call off the EDT.</p>
 */
public final class HoleSettingsClient {

    /** `settings get` only resolves a path and checks a file. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = Logger.getInstance(HoleSettingsClient.class);
    private static final Pattern MISSING_FILE = Pattern.compile("settings file '(.+?)' does not exist");

    private final String executable;
    private final CliRunner runner;

    /**
     * @param executable path of the `hole` binary
     * @param runner     runs Hole with the user's shell environment
     */
    public HoleSettingsClient(@NotNull String executable, @NotNull CliRunner runner) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Returns the global settings file: its path and whether it exists. Empty when Hole cannot tell (it does not run,
     * or rejects its environment, e.g. a relative `HOLE_SETTINGS_FILE`).
     */
    @NotNull
    public Optional<SettingsFile> global() {
        final Optional<CliResult> result = run(HoleCommandBuilder.settingsGetGlobal(this.executable));
        if (result.isEmpty()) {
            return Optional.empty();
        }
        if (result.get().isSuccess()) {
            return printedPath(result.get()).map(path -> new SettingsFile(path, true));
        }
        final Optional<Path> missing = missingPath(result.get());
        if (missing.isEmpty()) {
            // e.g. not a regular file, a rejected override or a Hole without `settings get`: no link
            LOG.info("`hole settings get global` failed with exit code " + result.get().exitCode() + ": "
                + result.get().firstStderrLine());
        }
        return missing.map(path -> new SettingsFile(path, false));
    }

    /**
     * Returns the project settings file of a project directory when it exists. Empty when it does not exist, the
     * directory does not exist or Hole cannot tell.
     *
     * @param projectDirectory the project directory, resolved to an absolute path
     */
    @NotNull
    public Optional<Path> existingProjectFile(@NotNull Path projectDirectory) {
        return run(HoleCommandBuilder.settingsGetProject(this.executable, projectDirectory.toString()))
            .filter(CliResult::isSuccess)
            .flatMap(HoleSettingsClient::printedPath);
    }

    @NotNull
    private Optional<CliResult> run(@NotNull List<String> command) {
        try {
            return Optional.of(this.runner.run(command, null, TIMEOUT));
        } catch (CliException e) {
            LOG.info("Could not ask Hole for a settings file: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The path `settings get` printed on stdout (its only output there).
     */
    @NotNull
    private static Optional<Path> printedPath(@NotNull CliResult result) {
        return result.stdout().lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst()
            .flatMap(HoleSettingsClient::absolutePath);
    }

    /**
     * The path from Hole's "does not exist" error on stderr; empty for any other error.
     */
    @NotNull
    private static Optional<Path> missingPath(@NotNull CliResult result) {
        final Matcher matcher = MISSING_FILE.matcher(result.stderr());
        return matcher.find() ? absolutePath(matcher.group(1)) : Optional.empty();
    }

    @NotNull
    private static Optional<Path> absolutePath(@NotNull String value) {
        try {
            final Path path = Path.of(value);
            return path.isAbsolute() ? Optional.of(path) : Optional.empty();
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * A settings file as Hole reports it.
     *
     * @param path   absolute path
     * @param exists the file exists
     */
    public record SettingsFile(@NotNull Path path, boolean exists) {
    }
}
