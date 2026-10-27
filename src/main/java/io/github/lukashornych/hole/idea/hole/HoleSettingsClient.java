package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.diagnostic.Logger;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
 * `hole settings get project <path>`, and creates missing ones from Hole's template through `hole settings create`.
 * Hole resolves the paths exactly as `hole start` does (including the `HOLE_SETTINGS_FILE` /
 * `HOLE_PROJECT_SETTINGS_FILE` overrides from the environment it runs with), so the plugin does not duplicate those
 * rules.
 *
 * <p>`settings get` prints the absolute path and exits 0 when the file exists; otherwise it fails with
 * `settings file '<path>' does not exist; create it with '…'`, and the path is taken from that message, so the UI can
 * show where the file would be. `settings create` never overwrites: on an existing file it fails with
 * `settings file already exists: '<path>'; …`. Blocking; call off the EDT.</p>
 */
public final class HoleSettingsClient {

    /** `settings get` only resolves a path and checks a file; `create` writes a small template. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final Logger LOG = Logger.getInstance(HoleSettingsClient.class);
    private static final Pattern MISSING_FILE = Pattern.compile("settings file '(.+?)' does not exist");
    private static final String ALREADY_EXISTS = "settings file already exists";

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
        return Optional.ofNullable(lookup(Scope.GLOBAL, null).file());
    }

    /**
     * Returns the project settings file of a project directory: its path and whether it exists. Empty when Hole cannot
     * tell (the directory does not exist, a rejected override, Hole does not run).
     *
     * @param projectDirectory the project directory, resolved to an absolute path
     */
    @NotNull
    public Optional<SettingsFile> project(@NotNull Path projectDirectory) {
        return Optional.ofNullable(lookup(Scope.PROJECT, projectDirectory).file());
    }

    /**
     * Asks Hole for a settings file (`hole settings get`).
     *
     * @param scope            which file
     * @param projectDirectory the project directory for {@link Scope#PROJECT}, ignored for {@link Scope#GLOBAL}
     * @return the file (existing or not), or the reason Hole could not tell
     */
    @NotNull
    public Lookup lookup(@NotNull Scope scope, @Nullable Path projectDirectory) {
        final List<String> command = scope == Scope.GLOBAL
            ? HoleCommandBuilder.settingsGetGlobal(this.executable)
            : HoleCommandBuilder.settingsGetProject(this.executable, requireDirectory(scope, projectDirectory).toString());
        final CliResult result;
        try {
            result = this.runner.run(command, null, TIMEOUT);
        } catch (CliException e) {
            LOG.info("Could not ask Hole for the " + scope.label() + " settings file: " + e.getMessage());
            return new Lookup(null, Objects.requireNonNullElse(e.getMessage(), "Hole could not be run"));
        }
        if (result.isSuccess()) {
            return printedPath(result)
                .map(path -> new Lookup(new SettingsFile(path, true), ""))
                .orElseGet(() -> new Lookup(null, "Hole printed no settings file path"));
        }
        final Optional<Path> missing = missingPath(result);
        if (missing.isPresent()) {
            return new Lookup(new SettingsFile(missing.get(), false), "");
        }
        // e.g. not a regular file, a rejected override or a missing project directory: no link
        LOG.info("`hole settings get " + scope.label() + "` failed with exit code " + result.exitCode() + ": "
            + result.errorMessage());
        return new Lookup(null, result.errorMessage());
    }

    /**
     * Creates a settings file from Hole's template (`hole settings create`), with its parent directories.
     *
     * @param scope            which file
     * @param projectDirectory the project directory for {@link Scope#PROJECT}, ignored for {@link Scope#GLOBAL}
     * @return {@link Created#CREATED}, {@link Created#ALREADY_EXISTS} (created meanwhile; nothing was changed) or
     * {@link Created#FAILED} with Hole's message
     */
    @NotNull
    public Creation create(@NotNull Scope scope, @Nullable Path projectDirectory) {
        final List<String> command = scope == Scope.GLOBAL
            ? HoleCommandBuilder.settingsCreateGlobal(this.executable)
            : HoleCommandBuilder.settingsCreateProject(this.executable, requireDirectory(scope, projectDirectory).toString());
        final CliResult result;
        try {
            result = this.runner.run(command, null, TIMEOUT);
        } catch (CliException e) {
            LOG.info("Could not create the " + scope.label() + " settings file: " + e.getMessage());
            return new Creation(Created.FAILED, Objects.requireNonNullElse(e.getMessage(), "Hole could not be run"));
        }
        if (result.isSuccess()) {
            LOG.info("Created the " + scope.label() + " settings file through `hole settings create`");
            return new Creation(Created.CREATED, "");
        }
        if (result.stderr().contains(ALREADY_EXISTS)) {
            return new Creation(Created.ALREADY_EXISTS, result.errorMessage());
        }
        LOG.info("`hole settings create " + scope.label() + "` failed with exit code " + result.exitCode() + ": "
            + result.errorMessage());
        return new Creation(Created.FAILED, result.errorMessage());
    }

    @NotNull
    private static Path requireDirectory(@NotNull Scope scope, @Nullable Path projectDirectory) {
        if (projectDirectory == null) {
            throw new IllegalArgumentException("The " + scope.label() + " settings file needs a project directory.");
        }
        return projectDirectory;
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
     * Which settings file.
     */
    public enum Scope {
        /** `~/.hole/settings.json` or `HOLE_SETTINGS_FILE`. */
        GLOBAL,
        /** `<project>/.hole/settings.json` or `HOLE_PROJECT_SETTINGS_FILE`. */
        PROJECT;

        /**
         * Returns the scope as Hole names it: `global` or `project`.
         */
        @NotNull
        public String label() {
            return this == GLOBAL ? "global" : "project";
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

    /**
     * Answer of `settings get`.
     *
     * @param file    the file, {@code null} when Hole could not tell
     * @param failure Hole's message when {@code file} is {@code null}, otherwise empty
     */
    public record Lookup(@Nullable SettingsFile file, @NotNull String failure) {
    }

    /**
     * Outcome of `settings create`.
     */
    public enum Created {
        /** Hole wrote the template. */
        CREATED,
        /** The file existed already (e.g. created meanwhile); Hole left it alone. */
        ALREADY_EXISTS,
        /** Hole failed; see {@link Creation#message()}. */
        FAILED
    }

    /**
     * Answer of `settings create`.
     *
     * @param outcome what happened
     * @param message Hole's message, empty on success
     */
    public record Creation(@NotNull Created outcome, @NotNull String message) {
    }
}
