package io.github.lukashornych.hole.idea.ui.network;

import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    void sandboxOnlyIsTheDefaultTargetAndAFailedSaveSaysTheEntryIsAllowed() {
        assertEquals(EntryInputDialog.Target.SANDBOX, EntryInputDialog.Target.values()[0]);
        assertEquals(null, EntryInputDialog.Target.SANDBOX.scope());
        assertEquals(HoleSettingsClient.Scope.PROJECT, EntryInputDialog.Target.PROJECT.scope());
        assertEquals("Allowed in this sandbox, but not saved to the global settings: not valid JSON",
            NetworkDialog.notSavedText(HoleSettingsClient.Scope.GLOBAL, "not valid JSON"));
    }

    @Test
    void entriesArePairedWithTheSettingsFilesThatHoldThem() {
        final List<NetworkTableModels.EntryRow> rows = NetworkTableModels.EntryRow.of(List.of(
            new NetworkEntry("a.example.com:80,443", "settings"),
            new NetworkEntry("b.example.com:80,443", NetworkEntry.RUNTIME),
            new NetworkEntry("c.example.com:80,443", "agent:claude"),
            new NetworkEntry("d.example.com:80,443", NetworkEntry.RUNTIME)
        ), Map.of(
            HoleSettingsClient.Scope.GLOBAL, Set.of("a.example.com:80,443", "b.example.com:80,443"),
            HoleSettingsClient.Scope.PROJECT, Set.of("a.example.com:80,443")
        ));
        assertEquals("global, project", rows.get(0).savedInText());
        assertTrue(rows.get(0).removable() && rows.get(0).needsRestart());
        assertEquals("global", rows.get(1).savedInText());
        assertTrue(rows.get(1).removable() && !rows.get(1).needsRestart());
        assertTrue(!rows.get(2).removable());
        assertEquals("", rows.get(3).savedInText());
        assertTrue(rows.get(3).removable() && !rows.get(3).needsRestart());

        assertEquals(NetworkDialog.NEEDS_RESTART, NetworkDialog.hint(rows.subList(0, 2)));
        assertEquals(NetworkDialog.FROM_AGENT, NetworkDialog.hint(rows.subList(2, 3)));
        assertEquals(" ", NetworkDialog.hint(rows.subList(3, 4)));
        // in force from settings, in neither file (a profile)
        assertEquals(NetworkDialog.NOT_IN_FILES, NetworkDialog.hint(NetworkTableModels.EntryRow.of(
            List.of(new NetworkEntry("a.example.com:80,443", "settings")), Map.of())));
        // regression: removed from the project file, restart cancelled — marked as waiting for the restart
        final List<NetworkTableModels.EntryRow> pending = NetworkTableModels.EntryRow.of(
            List.of(new NetworkEntry("a.example.com:80,443", "settings")), Map.of(), Set.of("a.example.com:80,443"));
        assertEquals(NetworkTableModels.EntryRow.REMOVED, pending.get(0).savedInText());
        assertTrue(!pending.get(0).removable());
        assertEquals(NetworkDialog.REMOVED_PENDING, NetworkDialog.hint(pending));
        // saved again by hand: back in the file, no longer pending
        assertEquals("project", NetworkTableModels.EntryRow.of(
            List.of(new NetworkEntry("a.example.com:80,443", "settings")),
            Map.of(HoleSettingsClient.Scope.PROJECT, Set.of("a.example.com:80,443")), Set.of("a.example.com:80,443")
        ).get(0).savedInText());

        assertEquals("""
            Remove 2 entries?

            a.example.com:80,443 — from the global settings file, the project settings file
            b.example.com:80,443 — from this sandbox, the global settings file

            Entries from settings stay in force until the sandbox restarts; closing this window offers the restart.""",
            NetworkDialog.removeQuestion(rows.subList(0, 2)));
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
