package io.github.lukashornych.hole.idea.ui.network;

import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swing-free parts of the Network window: banners by exit code, input splitting, local times.
 */
class NetworkDialogTextsTest {

    @Test
    void bannersFollowHolesExitCodes() {
        assertTrue(NetworkDialog.bannerText(HoleModifyClient.ModifyFailure.APPLY_FAILED, "gateway exit 1")
            .startsWith("The gateway did not apply the change; the previous allow list is still in force."));
        assertTrue(NetworkDialog.bannerText(HoleModifyClient.ModifyFailure.NOT_RUNNING, "sandbox k3x9a2 is stopping")
            .endsWith("sandbox k3x9a2 is stopping"));
        assertTrue(NetworkDialog.bannerText(HoleModifyClient.ModifyFailure.OLDER_HOLE, "x").contains("restart it"));
        assertEquals("invalid entry", NetworkDialog.bannerText(HoleModifyClient.ModifyFailure.OTHER, "invalid entry"));
    }

    @Test
    void inputIsSplitIntoEntriesBySpaces() {
        assertEquals(List.of("*.example.com:443,80", "db.example.com:5432"),
            EntryInputDialog.values("  *.example.com:443,80   db.example.com:5432 "));
        assertEquals(List.of(), EntryInputDialog.values("   "));
    }

    @Test
    void lastSeenIsShownInLocalTime() {
        assertEquals("2026-10-21 12:25:40",
            NetworkTableModels.localTime(OffsetDateTime.parse("2026-10-21T10:25:40Z"), ZoneId.of("Europe/Prague")));
        assertEquals("", NetworkTableModels.localTime(null, ZoneId.of("UTC")));
    }
}
