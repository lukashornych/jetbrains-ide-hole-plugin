package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.InstanceFlags;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoleListParserTest {

    @Test
    void parsesAssignmentSchema() throws IOException {
        final List<InstanceInfo> instances = HoleListParser.parse(resource("/hole-list/full.json"));
        assertEquals(2, instances.size());

        final InstanceInfo first = instances.get(0);
        assertEquals("r8tq2c", first.instanceId());
        assertEquals("acme-cms-r8tq2c", first.instanceName());
        assertEquals(InstanceStatus.RUNNING, first.status());
        assertEquals("claude", first.agent());
        assertEquals("research", first.profile());
        assertEquals("/Users/dev/projects/acme-cms", first.projectPath());
        assertEquals("acme-cms", first.projectName());
        assertEquals(OffsetDateTime.parse("2026-10-21T10:14:03+02:00"), first.startedAt());
        assertEquals(754, first.uptimeSeconds());
        assertFalse(first.docker());
        assertEquals(List.of("hole-acme-cms-r8tq2c_sandbox"), first.networks());
        assertEquals(List.of("global(~/.hole/settings.json)", "project"), first.settings());
        assertEquals("global(~/.hole/settings.json)+project", first.settingsLabel());
        assertEquals(
            List.of("/Users/dev/.hole/settings.json", "/Users/dev/projects/acme-cms/.hole/settings.json"),
            first.settingsFiles()
        );
        assertEquals(InstanceFlags.NONE, first.flags());
        assertEquals(48211, first.pid());
        assertEquals("2.3.0", first.version());

        final InstanceInfo second = instances.get(1);
        assertEquals("7m2ahw", second.instanceId());
        assertEquals(InstanceStatus.STARTING, second.status());
        assertEquals("", second.profile());
        assertTrue(second.docker());
        assertTrue(second.networks().isEmpty());
        assertEquals(new InstanceFlags(true, true, true, true, true), second.flags());
        // Go marshals time.Time with nanoseconds and Z
        assertEquals(OffsetDateTime.parse("2026-10-21T08:30:00.123456789Z"), second.startedAt());
    }

    @Test
    void toleratesOlderPartialAndMistypedEntries() throws IOException {
        final List<InstanceInfo> instances = HoleListParser.parse(resource("/hole-list/older-and-partial.json"));
        assertEquals(List.of("aaaaaa", "bbbbbb", "cccccc", "dddddd", "eeeeee"), instances.stream().map(InstanceInfo::instanceId).toList());

        final InstanceInfo older = instances.get(0);
        assertEquals(InstanceStatus.RUNNING, older.status(), "no status = instance of an older Hole = running");
        assertEquals("gemini", older.agent());
        assertEquals("", older.profile());
        assertNull(older.startedAt());
        assertTrue(older.settings().isEmpty());
        assertEquals(InstanceFlags.NONE, older.flags());

        final InstanceInfo mistyped = instances.get(1);
        assertEquals(InstanceStatus.STOPPING, mistyped.status());
        assertNull(mistyped.startedAt());
        assertEquals(0, mistyped.pid());
        assertTrue(mistyped.networks().isEmpty());
        assertFalse(mistyped.docker());

        assertEquals(InstanceStatus.UNKNOWN, instances.get(2).status());
        assertEquals(InstanceStatus.STARTING, instances.get(3).status());
        assertEquals("", instances.get(3).profile());
        assertEquals(InstanceStatus.RUNNING, instances.get(4).status(), "status null = no phase = running");
    }

    @Test
    void runtimeEntryCountDefaultsToZero() {
        final List<InstanceInfo> instances = HoleListParser.parse("""
            [{"instanceId": "r8tq2c", "runtimeEntryCount": 3}, {"instanceId": "k3x9a2"}, {"instanceId": "m4p7q1", "runtimeEntryCount": "x"}]
            """);
        assertEquals(3, instances.get(0).runtimeEntryCount());
        assertEquals(0, instances.get(1).runtimeEntryCount(), "absent");
        assertEquals(0, instances.get(2).runtimeEntryCount(), "mistyped");
    }

    @Test
    void emptyArray() {
        assertTrue(HoleListParser.parse("[]\n").isEmpty());
        assertTrue(HoleListParser.parse("  [ ]  ").isEmpty());
    }

    @Test
    void rejectsNonArrayAndInvalidJson() {
        assertThrows(IllegalArgumentException.class, () -> HoleListParser.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> HoleListParser.parse("No sandboxes are running."));
        assertThrows(IllegalArgumentException.class, () -> HoleListParser.parse("[{\"instanceId\": "));
        assertThrows(IllegalArgumentException.class, () -> HoleListParser.parse(""));
    }

    @Test
    void uptimeIsComputedFromStartTime() {
        final InstanceInfo info = HoleListParser.parse(
            "[{\"instanceId\": \"r8tq2c\", \"startedAt\": \"2026-10-21T10:00:00Z\", \"uptimeSeconds\": 3}]"
        ).get(0);
        assertEquals(Duration.ofSeconds(90), info.uptimeAt(Instant.parse("2026-10-21T10:01:30Z")));
        assertEquals(Duration.ZERO, info.uptimeAt(Instant.parse("2026-10-21T09:00:00Z")), "clock skew is clamped");

        final InstanceInfo withoutStart = HoleListParser.parse("[{\"instanceId\": \"r8tq2c\", \"uptimeSeconds\": 3}]").get(0);
        assertEquals(Duration.ofSeconds(3), withoutStart.uptimeAt(Instant.now()));
    }

    private static String resource(String name) throws IOException {
        try (InputStream stream = HoleListParserTest.class.getResourceAsStream(name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
