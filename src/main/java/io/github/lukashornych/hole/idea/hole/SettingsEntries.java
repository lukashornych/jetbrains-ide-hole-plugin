package io.github.lukashornych.hole.idea.hole;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads, adds and removes entries of a network setting of a Hole settings document (e.g. `network.allow`), for the
 * Network window. Hole has no CLI that writes a settings file, so the plugin edits it itself: Hole reads it as plain
 * JSON (`encoding/json`) and keeps unknown keys, so the document is parsed, the array changed and the whole document
 * written back with Hole's own formatting (two-space indent, key order kept, trailing newline). Only the top-level
 * setting is read and changed, never one in `profiles`.
 *
 * <p>Entries are compared in the canonical form `hole modify … list` reports ({@link #canonical}), so
 * {@code Example.com} in a file matches the listed {@code example.com:80,443}.</p>
 */
public final class SettingsEntries {

    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Ports of an allow entry without a port list, as Hole's `network.DefaultPorts`. */
    private static final String DEFAULT_PORTS = "80,443";

    private SettingsEntries() {
    }

    /**
     * Returns an entry in the form Hole lists it: host in lower case, ports sorted without duplicates, and for
     * {@link NetworkSetting#ALLOW} the ports {@code 80,443} when none are given. A value that is not an entry is
     * returned trimmed (it matches nothing Hole lists).
     */
    @NotNull
    public static String canonical(@NotNull NetworkSetting setting, @NotNull String value) {
        final String trimmed = value.trim();
        final int colon = trimmed.lastIndexOf(':');
        final String host = (colon < 0 ? trimmed : trimmed.substring(0, colon)).toLowerCase(Locale.ROOT);
        if (colon < 0) {
            return setting == NetworkSetting.ALLOW ? host + ":" + DEFAULT_PORTS : host;
        }
        final TreeSet<Integer> ports = new TreeSet<>();
        for (String port : trimmed.substring(colon + 1).split(",")) {
            try {
                ports.add(Integer.parseInt(port.trim()));
            } catch (NumberFormatException e) {
                return trimmed;
            }
        }
        return host + ":" + ports.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * Returns the canonical entries of the top-level setting; empty when the document has none.
     *
     * @throws IllegalArgumentException the document is not a JSON object, or a key on the way has another type
     */
    @NotNull
    public static Set<String> entries(@NotNull String json, @NotNull NetworkSetting setting) {
        final Set<String> entries = new LinkedHashSet<>();
        array(parse(json), setting, false).ifPresent(array -> array.forEach(element -> {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                entries.add(canonical(setting, element.getAsString()));
            }
        }));
        return entries;
    }

    /**
     * Returns the document without the values that match the entry, or nothing when none does.
     *
     * @param entry an entry in any form; compared canonically
     * @throws IllegalArgumentException the document is not a JSON object, or a key on the way has another type
     */
    @NotNull
    public static Optional<String> withoutEntry(@NotNull String json, @NotNull NetworkSetting setting, @NotNull String entry) {
        final JsonObject root = parse(json);
        final Optional<JsonArray> array = array(root, setting, false);
        if (array.isEmpty()) {
            return Optional.empty();
        }
        final String wanted = canonical(setting, entry);
        boolean changed = false;
        for (int i = array.get().size() - 1; i >= 0; i--) {
            final JsonElement element = array.get().get(i);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                && canonical(setting, element.getAsString()).equals(wanted)) {
                array.get().remove(i);
                changed = true;
            }
        }
        return changed ? Optional.of(GSON.toJson(root) + "\n") : Optional.empty();
    }

    /**
     * Returns the document with the values appended to the setting's array, or nothing when every value is already
     * there. Missing objects and the array are created; a blank document counts as {@code {}}.
     *
     * @param json    the settings document
     * @param setting where the values go
     * @param values  entries in Hole's syntax, e.g. {@code example.com:443,80}
     * @throws IllegalArgumentException the document is not a JSON object, or a key on the way is not an object / an
     *                                  array
     */
    @NotNull
    public static Optional<String> withEntries(@NotNull String json, @NotNull NetworkSetting setting, @NotNull List<String> values) {
        final JsonObject root = parse(json);
        final JsonArray array = array(root, setting, true).orElseThrow();
        final Set<String> present = new LinkedHashSet<>();
        array.forEach(element -> {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                present.add(canonical(setting, element.getAsString()));
            }
        });
        boolean changed = false;
        for (String value : values) {
            if (present.add(canonical(setting, value))) {
                array.add(new JsonPrimitive(value));
                changed = true;
            }
        }
        return changed ? Optional.of(GSON.toJson(root) + "\n") : Optional.empty();
    }

    /**
     * Parses a settings document; a blank one counts as {@code {}}.
     *
     * @throws IllegalArgumentException the document is not valid JSON or not a JSON object
     */
    @NotNull
    static JsonObject parse(@NotNull String json) {
        final JsonElement root;
        try {
            root = json.isBlank() ? new JsonObject() : JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return root.getAsJsonObject();
    }

    /**
     * Returns the setting's array, creating it and the objects on the way when {@code create} is set.
     */
    @NotNull
    private static Optional<JsonArray> array(@NotNull JsonObject root, @NotNull NetworkSetting setting, boolean create) {
        final String[] keys = setting.key().split("\\.");
        JsonObject parent = root;
        for (int i = 0; i < keys.length - 1; i++) {
            final JsonElement child = parent.get(keys[i]);
            if (child == null) {
                if (!create) {
                    return Optional.empty();
                }
                final JsonObject created = new JsonObject();
                parent.add(keys[i], created);
                parent = created;
            } else if (child.isJsonObject()) {
                parent = child.getAsJsonObject();
            } else {
                throw new IllegalArgumentException("`" + String.join(".", List.of(keys).subList(0, i + 1)) + "` is not an object");
            }
        }
        final String last = keys[keys.length - 1];
        final JsonElement existing = parent.get(last);
        if (existing == null) {
            if (!create) {
                return Optional.empty();
            }
            final JsonArray created = new JsonArray();
            parent.add(last, created);
            return Optional.of(created);
        }
        if (!existing.isJsonArray()) {
            throw new IllegalArgumentException("`" + setting.key() + "` is not an array");
        }
        return Optional.of(existing.getAsJsonArray());
    }
}
