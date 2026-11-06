package io.github.lukashornych.hole.idea.hole;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads, adds, changes and removes entries of the top-level `libraries` setting of a Hole settings document, for the
 * Libraries window. `libraries` maps a host path to a container path, either as the read-only shorthand
 * {@code "~/lib": "/libs/lib"} or as {@code {"path": "/libs/lib", "readwrite": true}} (Hole's `config.Library`).
 * Like {@link SettingsEntries}, the whole document is written back with Hole's formatting (two-space indent, key
 * order kept, trailing newline), unknown keys are kept, untouched entries keep their form and `profiles` is never
 * read or changed.
 *
 * <p>Hole merges the global and the project document per <i>literal</i> key (`config.Merge`): the same key in both is
 * one library, two objects merge field by field (a project object without `readwrite` keeps a global
 * {@code "readwrite": true}), any other combination takes the project value whole. {@code ~/lib} and
 * {@code /home/me/lib} are two keys there; Hole resolves them to one directory only when it mounts and keeps one of
 * them, so {@link HostResolver} exists only to warn about such duplicates.</p>
 */
public final class SettingsLibraries {

    /** The top-level key. */
    public static final String KEY = "libraries";

    private SettingsLibraries() {
    }

    /**
     * Which settings files an effective library comes from.
     */
    public enum Origin {
        /** Only the global settings file. */
        GLOBAL,
        /** Only the project settings file. */
        PROJECT,
        /** The same literal key in both files; the project value overrides or merges into the global one. */
        PROJECT_OVERRIDES_GLOBAL
    }

    /**
     * One library as Hole reads it.
     *
     * @param hostPath  the literal key, as written in the file
     * @param mountPath container path; empty when the value has none
     * @param readWrite mounted read-write
     * @param valid     the value is a string or an object with a non-empty `path`
     * @param origin    the files it comes from
     */
    public record Library(@NotNull String hostPath, @NotNull String mountPath, boolean readWrite, boolean valid, @NotNull Origin origin) {
    }

    /**
     * Returns the libraries of one document in file order.
     *
     * @param origin the origin given to every library
     * @throws IllegalArgumentException the document is not a JSON object, or `libraries` is not an object
     */
    @NotNull
    public static List<Library> libraries(@NotNull String json, @NotNull Origin origin) {
        final List<Library> libraries = new ArrayList<>();
        raw(json).forEach((key, value) -> libraries.add(decode(key, value, origin)));
        return libraries;
    }

    /**
     * Returns the libraries Hole sees after merging the documents: the global ones in file order (a key also in the
     * project document with the merged value), then the project-only ones in file order.
     *
     * @param global  the global document, blank when there is none
     * @param project the project document, blank when there is none
     * @throws IllegalArgumentException a document is not a JSON object, or its `libraries` is not an object
     */
    @NotNull
    public static List<Library> merged(@NotNull String global, @NotNull String project) {
        final Map<String, JsonElement> globalLibraries = raw(global);
        final Map<String, JsonElement> projectLibraries = raw(project);
        final List<Library> libraries = new ArrayList<>();
        globalLibraries.forEach((key, value) -> {
            final JsonElement override = projectLibraries.get(key);
            libraries.add(override == null
                ? decode(key, value, Origin.GLOBAL)
                : decode(key, merge(value, override), Origin.PROJECT_OVERRIDES_GLOBAL));
        });
        projectLibraries.forEach((key, value) -> {
            if (!globalLibraries.containsKey(key)) {
                libraries.add(decode(key, value, Origin.PROJECT));
            }
        });
        return libraries;
    }

    /**
     * Returns the document with a new library: read-only as the string shorthand, read-write in the object form.
     * Missing `libraries` is created; a blank document counts as {@code {}}.
     *
     * @param resolver resolves host paths, to refuse a key that names the same directory as an existing one
     * @throws IllegalArgumentException the document is not a JSON object, `libraries` is not an object, or the host
     *                                  directory is already in the document
     */
    @NotNull
    public static String withLibrary(
        @NotNull String json, @NotNull String hostPath, @NotNull String mountPath, boolean readWrite, @NotNull HostResolver resolver
    ) {
        final JsonObject root = SettingsEntries.parse(json);
        final JsonObject libraries = object(root, true).orElseThrow();
        final String resolved = resolver.resolve(hostPath);
        for (String key : libraries.keySet()) {
            if (key.equals(hostPath) || resolver.resolve(key).equals(resolved)) {
                throw new IllegalArgumentException(key + " is already in this file");
            }
        }
        libraries.add(hostPath, value(mountPath, readWrite));
        return SettingsEntries.GSON.toJson(root) + "\n";
    }

    /**
     * Returns the document with the mount path and access of a library changed, or nothing when they are already so.
     * A string becomes an object only when it turns read-write; an object stays an object and gets both `path` and
     * `readwrite` written out, because a project object without `readwrite` would keep a global {@code true}.
     *
     * @param hostPath the literal key
     * @throws IllegalArgumentException the document is not a JSON object, `libraries` is not an object, or the key is
     *                                  not in it
     */
    @NotNull
    public static Optional<String> withChangedLibrary(
        @NotNull String json, @NotNull String hostPath, @NotNull String mountPath, boolean readWrite
    ) {
        final JsonObject root = SettingsEntries.parse(json);
        final JsonObject libraries = object(root, false)
            .filter(object -> object.has(hostPath))
            .orElseThrow(() -> new IllegalArgumentException(hostPath + " is no longer in this file"));
        final JsonElement existing = libraries.get(hostPath);
        final JsonElement changed;
        if (existing.isJsonObject()) {
            final JsonObject object = existing.deepCopy().getAsJsonObject();
            object.addProperty("path", mountPath);
            object.addProperty("readwrite", readWrite);
            changed = object;
        } else {
            changed = value(mountPath, readWrite);
        }
        if (changed.equals(existing)) {
            return Optional.empty();
        }
        libraries.add(hostPath, changed);
        return Optional.of(SettingsEntries.GSON.toJson(root) + "\n");
    }

    /**
     * Returns the document without the library, or nothing when the key is not in it.
     *
     * @param hostPath the literal key
     * @throws IllegalArgumentException the document is not a JSON object, or `libraries` is not an object
     */
    @NotNull
    public static Optional<String> withoutLibrary(@NotNull String json, @NotNull String hostPath) {
        final JsonObject root = SettingsEntries.parse(json);
        final Optional<JsonObject> libraries = object(root, false);
        if (libraries.isEmpty() || libraries.get().remove(hostPath) == null) {
            return Optional.empty();
        }
        return Optional.of(SettingsEntries.GSON.toJson(root) + "\n");
    }

    /**
     * Returns why a mount path is not accepted, or nothing. Hole's settings take any container path; the window asks
     * for an absolute one, one in the sandbox home ({@code ~/…}) or one starting with a variable ({@code $HOME/…}),
     * which Hole expands from its environment before resolving the path.
     */
    @NotNull
    public static Optional<String> mountPathProblem(@NotNull String mountPath) {
        final String trimmed = mountPath.trim();
        if (trimmed.isEmpty()) {
            return Optional.of("Enter the path inside the sandbox");
        }
        if (!trimmed.startsWith("/") && !trimmed.equals("~") && !trimmed.startsWith("~/") && !trimmed.startsWith("$")) {
            return Optional.of("The mount path must be absolute (/libs/lib), in the sandbox home (~/lib) or start with a variable ($HOME/lib)");
        }
        return Optional.empty();
    }

    @NotNull
    private static JsonElement value(@NotNull String mountPath, boolean readWrite) {
        if (!readWrite) {
            return new JsonPrimitive(mountPath);
        }
        final JsonObject object = new JsonObject();
        object.addProperty("path", mountPath);
        object.addProperty("readwrite", true);
        return object;
    }

    /**
     * Hole's `mergeValues` for one key: two objects merge field by field with the project winning, anything else is
     * replaced by the project value.
     */
    @NotNull
    private static JsonElement merge(@NotNull JsonElement global, @NotNull JsonElement project) {
        if (!global.isJsonObject() || !project.isJsonObject()) {
            return project;
        }
        final JsonObject merged = global.deepCopy().getAsJsonObject();
        project.getAsJsonObject().entrySet().forEach(field -> merged.add(field.getKey(), field.getValue().deepCopy()));
        return merged;
    }

    @NotNull
    private static Library decode(@NotNull String key, @NotNull JsonElement value, @NotNull Origin origin) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            final String path = value.getAsString();
            return new Library(key, path, false, !path.isEmpty(), origin);
        }
        if (value.isJsonObject()) {
            final JsonObject object = value.getAsJsonObject();
            final JsonElement path = object.get("path");
            final JsonElement readWrite = object.get("readwrite");
            final String mount = path != null && path.isJsonPrimitive() && path.getAsJsonPrimitive().isString() ? path.getAsString() : "";
            final boolean writable = readWrite != null && readWrite.isJsonPrimitive() && readWrite.getAsJsonPrimitive().isBoolean()
                && readWrite.getAsBoolean();
            return new Library(key, mount, writable, !mount.isEmpty(), origin);
        }
        return new Library(key, "", false, false, origin);
    }

    @NotNull
    private static Map<String, JsonElement> raw(@NotNull String json) {
        final Map<String, JsonElement> libraries = new LinkedHashMap<>();
        object(SettingsEntries.parse(json), false).ifPresent(object -> object.entrySet()
            .forEach(entry -> libraries.put(entry.getKey(), entry.getValue())));
        return libraries;
    }

    @NotNull
    private static Optional<JsonObject> object(@NotNull JsonObject root, boolean create) {
        final JsonElement existing = root.get(KEY);
        if (existing == null || existing.isJsonNull()) {
            if (!create) {
                return Optional.empty();
            }
            final JsonObject created = new JsonObject();
            root.add(KEY, created);
            return Optional.of(created);
        }
        if (!existing.isJsonObject()) {
            throw new IllegalArgumentException("`" + KEY + "` is not an object");
        }
        return Optional.of(existing.getAsJsonObject());
    }

    /**
     * Resolves a host path roughly as Hole's `ResolveHostPath` does: trailing slashes stripped, {@code ~} and
     * {@code ~/…} in the user's home, a relative path in the project directory, then normalised. `$VAR` is not
     * expanded (the plugin does not know Hole's environment for sure), so such keys are compared as written.
     *
     * @param home             the user's home directory
     * @param projectDirectory the sandbox's project directory
     */
    public record HostResolver(@NotNull Path home, @NotNull Path projectDirectory) {

        /**
         * Returns the resolved, normalised path.
         */
        @NotNull
        public String resolve(@NotNull String hostPath) {
            String path = hostPath.trim();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            final Path resolved;
            if (path.equals("~")) {
                resolved = this.home;
            } else if (path.startsWith("~/")) {
                resolved = this.home.resolve(path.substring(2));
            } else if (path.isEmpty()) {
                resolved = this.projectDirectory;
            } else if (!path.startsWith("/")) {
                resolved = this.projectDirectory.resolve(path);
            } else {
                resolved = Path.of(path);
            }
            return resolved.normalize().toString();
        }
    }
}
