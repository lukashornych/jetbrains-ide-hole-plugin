package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON contract of `hole modify … list --json` / `list-blocked --json` (Hole 2.6.0), with anonymised fixtures.
 */
class HoleModifyParserTest {

    @Test
    void entriesKeepHolesOrderAndSourcesAndIgnoreUnknownFields() throws IOException {
        final List<NetworkEntry> entries = HoleModifyParser.entries(resource("/hole-modify/list.json"));
        assertEquals(List.of(
            new NetworkEntry("api.example.com:443", "settings"),
            new NetworkEntry("registry.example.org:443,80", "agent:claude"),
            new NetworkEntry("*.cdn.example.net:443", "runtime")
        ), entries, "the item without an entry is skipped");
        assertFalse(entries.get(0).isRuntime());
        assertTrue(entries.get(2).isRuntime());
    }

    @Test
    void blockedNamesWithTimesAndTolerantParsing() throws IOException {
        final List<BlockedName> names = HoleModifyParser.blocked(resource("/hole-modify/list-blocked.json"));
        assertEquals(2, names.size());
        assertEquals(new BlockedName("telemetry.example.com", 12,
            OffsetDateTime.parse("2026-10-21T10:01:02+02:00"), OffsetDateTime.parse("2026-10-21T10:25:40+02:00")), names.get(0));
        assertNull(names.get(1).firstSeen(), "unparsable time");
        assertEquals(OffsetDateTime.parse("2026-10-21T08:00:00Z"), names.get(1).lastSeen());
    }

    @Test
    void missingArraysAreEmptyAndInvalidDocumentsRejected() {
        assertEquals(List.of(), HoleModifyParser.entries("{\"instanceId\": \"k3x9a2\"}"));
        assertEquals(List.of(), HoleModifyParser.blocked("{\"blocked\": null}"));
        assertThrows(IllegalArgumentException.class, () -> HoleModifyParser.entries("[]"));
        assertThrows(IllegalArgumentException.class, () -> HoleModifyParser.blocked("{not json"));
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = HoleModifyParserTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
