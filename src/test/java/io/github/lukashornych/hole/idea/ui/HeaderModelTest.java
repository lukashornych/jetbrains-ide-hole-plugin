package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleListParser;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderModelTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:30:00Z");
    private static final InstanceInfo INFO = HoleListParser.parse("""
        [{"instanceId": "r8tq2c", "status": "running", "projectPath": "/work/acme-cms", "startedAt": "2026-10-21T10:17:26Z",
          "docker": true, "settings": ["global(~/.hole/settings.json)", "project"]}]
        """).get(0);

    @Test
    void skeletonsUntilFirstMetadata() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.STARTING, null, null, null), true, NOW);
        assertEquals("r8tq2c", model.instanceId(), "the plugin chose the ID, so it is known at once");
        assertTrue(model.debug());
        assertTrue(model.pending());
        assertFalse(model.metadata());
        assertNull(model.stopping());
        assertNull(model.exited());
    }

    @Test
    void metadataFieldsAndLocalUptime() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, NOW);
        assertFalse(model.pending());
        assertTrue(model.metadata());
        assertTrue(model.docker());
        assertEquals("global(~/.hole/settings.json)+project", model.settings());
        assertEquals("/work/acme-cms", model.project());
        assertEquals("12m 34s", model.uptime(), "computed from startedAt, not from uptimeSeconds");
        assertFalse(model.debug());
    }

    @Test
    void stoppingTimerAndForceStopAfterThirtySeconds() {
        final HeaderModel early = HeaderModel.of(snapshot(SessionState.STOPPING, INFO, null, NOW.minusSeconds(29)), false, NOW);
        assertEquals("Stopping for 29 s", early.stopping());
        assertFalse(early.forceStop());
        final HeaderModel late = HeaderModel.of(snapshot(SessionState.STOPPING, INFO, null, NOW.minusSeconds(34)), false, NOW);
        assertEquals("Stopping for 34 s", late.stopping());
        assertTrue(late.forceStop());
    }

    @Test
    void exitedShowsCodeWithoutSkeletons() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.EXITED, null, 1, null), false, NOW);
        assertEquals("Exited with code 1", model.exited());
        assertFalse(model.pending(), "no endless skeletons for a sandbox Hole never listed");
        assertNull(model.stopping());
    }

    @Test
    void modelOfAnEndedSandboxDoesNotChangeWithTime() {
        // the tab's 1 s timer re-renders only on a changed model and skips EXITED / CLOSED
        assertEquals(
            HeaderModel.of(snapshot(SessionState.EXITED, INFO, 1, null), false, NOW),
            HeaderModel.of(snapshot(SessionState.EXITED, INFO, 1, null), false, NOW.plusSeconds(90))
        );
        assertEquals(
            HeaderModel.of(snapshot(SessionState.CLOSED, INFO, 0, null), false, NOW),
            HeaderModel.of(snapshot(SessionState.CLOSED, INFO, 0, null), false, NOW.plusSeconds(90))
        );
    }

    @Test
    void modelOfARunningSandboxChangesWithTheUptimeOnly() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, NOW);
        assertEquals(model, HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, NOW.plusMillis(400)));
        final HeaderModel later = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, NOW.plusSeconds(1));
        assertFalse(model.equals(later));
        assertEquals("12m 35s", later.uptime());
    }

    @Test
    void durationFormat() {
        assertEquals("0s", HeaderModel.formatDuration(Duration.ofSeconds(-3)));
        assertEquals("45s", HeaderModel.formatDuration(Duration.ofSeconds(45)));
        assertEquals("1m 00s", HeaderModel.formatDuration(Duration.ofSeconds(60)));
        assertEquals("3h 07m", HeaderModel.formatDuration(Duration.ofMinutes(187).plusSeconds(59)));
    }

    private static SandboxSession.Snapshot snapshot(SessionState state, InstanceInfo info, Integer exitCode, Instant stoppingSince) {
        return new SandboxSession.Snapshot(state, "r8tq2c", info, exitCode, stoppingSince, "", false);
    }
}
