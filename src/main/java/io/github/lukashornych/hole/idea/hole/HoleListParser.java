package io.github.lukashornych.hole.idea.hole;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.lukashornych.hole.idea.model.InstanceFlags;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the stdout of `hole list --json` (schema in `documentation/hole-assignment-instance-id-and-list-json.md`).
 *
 * <p>The top level must be a JSON array of objects. Fields are read leniently: unknown fields are ignored (Hole may
 * add new ones), missing or mistyped fields get empty defaults. An entry without an `instanceId` is skipped, because
 * it cannot be matched to a sandbox.</p>
 */
public final class HoleListParser {

    private HoleListParser() {
    }

    /**
     * Parses the JSON document.
     *
     * @throws IllegalArgumentException when the document is not valid JSON or not an array
     */
    @NotNull
    public static List<InstanceInfo> parse(@NotNull String json) {
        final JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("Output of `hole list --json` is not valid JSON.", e);
        }
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("Output of `hole list --json` is not a JSON array.");
        }
        final JsonArray array = root.getAsJsonArray();
        final List<InstanceInfo> instances = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            final InstanceInfo info = parseInstance(element.getAsJsonObject());
            if (info != null) {
                instances.add(info);
            }
        }
        return List.copyOf(instances);
    }

    @Nullable
    private static InstanceInfo parseInstance(@NotNull JsonObject object) {
        final String instanceId = string(object, "instanceId");
        if (instanceId.isEmpty()) {
            return null;
        }
        return new InstanceInfo(
            instanceId,
            string(object, "instanceName"),
            InstanceStatus.fromJson(object.has("status") ? string(object, "status") : null),
            string(object, "agent"),
            string(object, "profile"),
            string(object, "projectPath"),
            string(object, "projectName"),
            dateTime(object, "startedAt"),
            number(object, "uptimeSeconds"),
            bool(object, "docker"),
            strings(object, "networks"),
            strings(object, "settings"),
            strings(object, "settingsFiles"),
            flags(object),
            number(object, "pid"),
            string(object, "version")
        );
    }

    @NotNull
    private static InstanceFlags flags(@NotNull JsonObject object) {
        final JsonElement flags = object.get("flags");
        if (flags == null || !flags.isJsonObject()) {
            return InstanceFlags.NONE;
        }
        final JsonObject f = flags.getAsJsonObject();
        return new InstanceFlags(
            bool(f, "debug"),
            bool(f, "rebuild"),
            bool(f, "unrestricted"),
            bool(f, "dumpNetworkAccess"),
            bool(f, "withDocker")
        );
    }

    @NotNull
    private static String string(@NotNull JsonObject object, @NotNull String name) {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return "";
        }
        return value.getAsString();
    }

    private static long number(@NotNull JsonObject object, @NotNull String name) {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return 0L;
        }
        return value.getAsLong();
    }

    private static boolean bool(@NotNull JsonObject object, @NotNull String name) {
        final JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    @NotNull
    private static List<String> strings(@NotNull JsonObject object, @NotNull String name) {
        final JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item.isJsonPrimitive()) {
                result.add(item.getAsString());
            }
        }
        return result;
    }

    @Nullable
    private static OffsetDateTime dateTime(@NotNull JsonObject object, @NotNull String name) {
        final String value = string(object, name);
        if (value.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
