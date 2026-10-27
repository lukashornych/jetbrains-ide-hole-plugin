package io.github.lukashornych.hole.idea.hole;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Resolves project paths typed in the dialog and offers the agent and profile names defined in Hole's settings files
 * as suggestions.
 *
 * <p>Where the settings files are is Hole's business: {@link HoleSettingsClient} asks Hole (`hole settings get`), and
 * this class reads the files at the paths Hole returned. Settings content is only read for the keys of `profiles` and
 * `agents`; it is never logged and never edited. Unreadable or malformed files contribute nothing. File access is
 * blocking; call off the EDT.</p>
 */
public final class HoleSettingsLocator {

    /** Agents built into Hole. */
    public static final List<String> BUILTIN_AGENTS = List.of("claude", "gemini", "codex");
    /** Allowed shape of profile and agent names (Hole's `ProfileNamePattern` / `agents.NamePattern`). */
    public static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final Supplier<Map<String, String>> environment;

    /**
     * @param environment the environment Hole runs with (user's shell environment); its `HOME` expands `~`
     */
    public HoleSettingsLocator(@NotNull Supplier<Map<String, String>> environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Resolves a project path as typed in the dialog against the IDE project root: `.` and blank mean the root,
     * relative paths are resolved against it, `~/` against the home directory. Only used for validation and the
     * settings-file lookup; the command keeps the typed value.
     *
     * @return the normalized absolute path, or empty when the value is not a valid path
     */
    @NotNull
    public Optional<Path> resolveProjectDirectory(@NotNull Path ideProjectRoot, @NotNull String projectPath) {
        final String value = projectPath.trim();
        try {
            final Path path;
            if (value.isEmpty() || value.equals(".")) {
                path = ideProjectRoot;
            } else if (value.equals("~")) {
                path = home(this.environment.get());
            } else if (value.startsWith("~/")) {
                path = home(this.environment.get()).resolve(value.substring(2));
            } else {
                path = ideProjectRoot.resolve(value);
            }
            return Optional.of(path.toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /**
     * Returns the profile names defined in the given settings files, sorted and without duplicates.
     *
     * @param settingsFiles the settings files Hole reported (global and project); missing files are skipped
     */
    @NotNull
    public List<String> profileNames(@NotNull List<Path> settingsFiles) {
        final TreeSet<String> names = new TreeSet<>();
        settingsFiles.forEach(file -> names.addAll(objectKeys(file, "profiles")));
        return List.copyOf(names);
    }

    /**
     * Returns the agent names to offer: the built-in agents first, then further keys of `agents` in the given settings
     * files (sorted).
     *
     * @param settingsFiles the settings files Hole reported (global and project); missing files are skipped
     */
    @NotNull
    public List<String> agentNames(@NotNull List<Path> settingsFiles) {
        final TreeSet<String> custom = new TreeSet<>();
        settingsFiles.forEach(file -> custom.addAll(objectKeys(file, "agents")));
        BUILTIN_AGENTS.forEach(custom::remove);
        final List<String> names = new ArrayList<>(BUILTIN_AGENTS);
        names.addAll(custom);
        return List.copyOf(names);
    }

    @NotNull
    private static List<String> objectKeys(@NotNull Path file, @NotNull String key) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            final JsonElement root = JsonParser.parseString(Files.readString(file));
            if (!root.isJsonObject()) {
                return List.of();
            }
            final JsonElement value = root.getAsJsonObject().get(key);
            if (value == null || !value.isJsonObject()) {
                return List.of();
            }
            final JsonObject object = value.getAsJsonObject();
            return object.keySet().stream().filter(name -> NAME_PATTERN.matcher(name).matches()).toList();
        } catch (IOException | JsonParseException | IllegalStateException e) {
            // unreadable or malformed settings are Hole's to report; they just give no suggestions here
            return List.of();
        }
    }

    @NotNull
    private static Path home(@NotNull Map<String, String> env) {
        final String home = env.get("HOME");
        return Path.of(home != null && !home.isBlank() ? home : System.getProperty("user.home"));
    }
}
