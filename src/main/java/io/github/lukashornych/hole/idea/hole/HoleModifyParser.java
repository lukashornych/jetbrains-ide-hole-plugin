package io.github.lukashornych.hole.idea.hole;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON of `hole modify … list --json` (`{"instanceId": "…", "entries": [{"entry", "source"}]}`) and
 * `hole modify … list-blocked --json` (`{"instanceId": "…", "blocked": [{"name", "count", "firstSeen", "lastSeen"}]}`,
 * RFC 3339 times, newest first). Unknown fields are ignored and missing ones get defaults, so a newer Hole does not
 * break the plugin; items without an entry / a name are skipped.
 */
public final class HoleModifyParser {

    private HoleModifyParser() {
    }

    /**
     * Parses the entries of `list --json` in Hole's order (base entries, then runtime ones).
     *
     * @throws IllegalArgumentException when the document is not a JSON object
     */
    @NotNull
    public static List<NetworkEntry> entries(@NotNull String json) {
        final List<NetworkEntry> entries = new ArrayList<>();
        for (JsonObject item : items(json, "entries")) {
            final String entry = HoleListParser.string(item, "entry");
            if (!entry.isEmpty()) {
                entries.add(new NetworkEntry(entry, HoleListParser.string(item, "source")));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Parses the names of `list-blocked --json` in Hole's order (newest first).
     *
     * @throws IllegalArgumentException when the document is not a JSON object
     */
    @NotNull
    public static List<BlockedName> blocked(@NotNull String json) {
        final List<BlockedName> names = new ArrayList<>();
        for (JsonObject item : items(json, "blocked")) {
            final String name = HoleListParser.string(item, "name");
            if (!name.isEmpty()) {
                names.add(new BlockedName(
                    name,
                    HoleListParser.number(item, "count"),
                    HoleListParser.dateTime(item, "firstSeen"),
                    HoleListParser.dateTime(item, "lastSeen")
                ));
            }
        }
        return List.copyOf(names);
    }

    @NotNull
    private static List<JsonObject> items(@NotNull String json, @NotNull String array) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Output of `hole modify` is not valid JSON.", e);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("Output of `hole modify` is not a JSON object.");
        }
        final JsonElement value = root.getAsJsonObject().get(array);
        final List<JsonObject> items = new ArrayList<>();
        if (value != null && value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    items.add(item.getAsJsonObject());
                }
            }
        }
        return items;
    }
}
