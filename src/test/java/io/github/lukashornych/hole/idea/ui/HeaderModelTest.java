package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleListParser;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SessionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.STARTING, null, null, null), true, HeaderModel.Checkout.NONE, NOW);
        assertEquals("r8tq2c", model.instanceId(), "the plugin chose the ID, so it is known at once");
        assertTrue(model.debug());
        assertTrue(model.pending());
        assertFalse(model.metadata());
        assertNull(model.stopping());
        assertNull(model.exited());
    }

    @Test
    void metadataFields() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, HeaderModel.Checkout.MAIN, NOW);
        assertFalse(model.pending());
        assertTrue(model.metadata());
        assertTrue(model.docker());
        assertEquals("global(~/.hole/settings.json)+project", model.settingsTooltip());
        assertTrue(model.globalUsed());
        assertTrue(model.projectUsed());
        assertEquals("acme-cms (main)", model.project(), "the main repository shows only its folder name");
        assertEquals("/work/acme-cms", model.projectPath());
        assertFalse(model.debug());
    }

    @Test
    void linkedWorktreeShowsItsParentFolderToo() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, HeaderModel.Checkout.WORKTREE, NOW);
        assertEquals("work/acme-cms (worktree)", model.project());
        assertEquals("/work/acme-cms", model.projectPath(), "the full path stays in the tooltip");
    }

    @Test
    void projectLabel() {
        assertEquals("app (main)", HeaderModel.projectLabel("/home/user/app", HeaderModel.Checkout.MAIN));
        assertEquals("app-worktrees/fix (worktree)", HeaderModel.projectLabel("/home/user/app-worktrees/fix", HeaderModel.Checkout.WORKTREE));
        assertEquals("app", HeaderModel.projectLabel("/home/user/app", HeaderModel.Checkout.NONE), "not a git repository, or not known yet");
        assertEquals("app (worktree)", HeaderModel.projectLabel("/app", HeaderModel.Checkout.WORKTREE), "no parent folder name under the root");
        assertEquals("/ (worktree)", HeaderModel.projectLabel("/", HeaderModel.Checkout.WORKTREE));
        assertEquals("", HeaderModel.projectLabel("", HeaderModel.Checkout.WORKTREE));
    }

    @Test
    void usedSettingsFilesAreMatchedByLabelPrefix() {
        final HeaderModel globalOnly = model(List.of("global(~/.hole/settings.json)"));
        assertTrue(globalOnly.globalUsed());
        assertFalse(globalOnly.projectUsed(), "the project file is not used: dimmed");
        final HeaderModel overrides = model(List.of("global(/etc/hole/global.json)", "project(~/elsewhere/project.json)"));
        assertTrue(overrides.globalUsed());
        assertTrue(overrides.projectUsed());
        assertEquals("global(/etc/hole/global.json)+project(~/elsewhere/project.json)", overrides.settingsTooltip());
        final HeaderModel defaults = model(List.of("defaults"));
        assertFalse(defaults.globalUsed());
        assertFalse(defaults.projectUsed());
        assertEquals(HeaderModel.NO_SETTINGS, model(List.of()).settingsTooltip());
    }

    @Test
    void stoppingTimerAndForceStopAfterThirtySeconds() {
        final HeaderModel early = HeaderModel.of(snapshot(SessionState.STOPPING, INFO, null, NOW.minusSeconds(29)), false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Stopping for 29 s", early.stopping());
        assertFalse(early.forceStop());
        final HeaderModel late = HeaderModel.of(snapshot(SessionState.STOPPING, INFO, null, NOW.minusSeconds(34)), false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Stopping for 34 s", late.stopping());
        assertTrue(late.forceStop());
        assertTrue(late.forceStopCloses(), "Force Stop of a close request closes the tab");
    }

    @Test
    void restartShowsRestartingTimerAndForceStopKeepsTheTab() {
        final HeaderModel early = HeaderModel.of(restarting(NOW.minusSeconds(5)), false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Restarting for 5 s", early.stopping());
        assertFalse(early.forceStop());
        assertNull(early.exited(), "no exit code during a restart");
        final HeaderModel late = HeaderModel.of(restarting(NOW.minusSeconds(31)), false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Restarting for 31 s", late.stopping());
        assertTrue(late.forceStop());
        assertFalse(late.forceStopCloses(), "no \"Closes the tab\" hint: the restart goes on after Force Stop");
    }

    @Test
    void restartWhileRunningOrExitedAndDockerToggleOnlyWhileRunning() {
        final HeaderModel running = HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, HeaderModel.Checkout.NONE, NOW);
        assertTrue(running.restart());
        assertTrue(running.dockerToggle());
        assertTrue(running.network());
        final HeaderModel exited = HeaderModel.of(snapshot(SessionState.EXITED, INFO, 1, null), false, HeaderModel.Checkout.NONE, NOW);
        assertTrue(exited.restart(), "the toolbar Restart starts an exited sandbox again");
        assertFalse(exited.dockerToggle(), "the Docker pill stays a plain label");
        assertFalse(exited.network());
        for (SandboxSession.Snapshot other : java.util.List.of(
            snapshot(SessionState.STARTING, null, null, null),
            snapshot(SessionState.STARTING, INFO, null, null),
            snapshot(SessionState.STOPPING, INFO, null, NOW),
            restarting(NOW),
            snapshot(SessionState.CLOSED, INFO, 0, null)
        )) {
            final HeaderModel model = HeaderModel.of(other, false, HeaderModel.Checkout.NONE, NOW);
            assertFalse(model.restart(), other.toString());
            assertFalse(model.dockerToggle(), other.toString());
            assertFalse(model.network(), other.toString());
        }
    }

    @Test
    void relaunchOfAnExitedSandboxShowsRestartingWithoutExitCode() {
        // while the new process is spawned the session is STOPPING with a pending restart; the old exit code is kept
        final SandboxSession.Snapshot relaunching = new SandboxSession.Snapshot(
            SessionState.STOPPING, "r8tq2c", INFO, 1, NOW, "", false, true
        );
        final HeaderModel model = HeaderModel.of(relaunching, false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Restarting for 0 s", model.stopping());
        assertNull(model.exited());
        assertFalse(model.restart(), "no second restart while one is pending");
        assertFalse(model.forceStop());
        final HeaderModel late = HeaderModel.of(relaunching, false, HeaderModel.Checkout.NONE, NOW.plusSeconds(31));
        assertEquals("Restarting for 31 s", late.stopping());
        assertFalse(late.forceStop(), "no process to kill while the new one is spawned");
        final HeaderModel restart = HeaderModel.of(restarting(NOW), false, HeaderModel.Checkout.NONE, NOW.plusSeconds(31));
        assertTrue(restart.forceStop(), "a restart of a running sandbox still offers it");
    }

    @Test
    void exitedShowsCodeWithoutSkeletons() {
        final HeaderModel model = HeaderModel.of(snapshot(SessionState.EXITED, null, 1, null), false, HeaderModel.Checkout.NONE, NOW);
        assertEquals("Exited with code 1", model.exited());
        assertFalse(model.pending(), "no endless skeletons for a sandbox Hole never listed");
        assertNull(model.stopping());
    }

    @Test
    void modelOfAnEndedSandboxDoesNotChangeWithTime() {
        // the tab's 1 s timer re-renders only on a changed model and skips EXITED / CLOSED
        assertEquals(
            HeaderModel.of(snapshot(SessionState.EXITED, INFO, 1, null), false, HeaderModel.Checkout.NONE, NOW),
            HeaderModel.of(snapshot(SessionState.EXITED, INFO, 1, null), false, HeaderModel.Checkout.NONE, NOW.plusSeconds(90))
        );
        assertEquals(
            HeaderModel.of(snapshot(SessionState.CLOSED, INFO, 0, null), false, HeaderModel.Checkout.NONE, NOW),
            HeaderModel.of(snapshot(SessionState.CLOSED, INFO, 0, null), false, HeaderModel.Checkout.NONE, NOW.plusSeconds(90))
        );
    }

    @Test
    void modelOfARunningSandboxDoesNotChangeWithTime() {
        // no uptime any more: the 1 s timer re-renders a running sandbox only when something else changed
        assertEquals(
            HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, HeaderModel.Checkout.NONE, NOW),
            HeaderModel.of(snapshot(SessionState.RUNNING, INFO, null, null), false, HeaderModel.Checkout.NONE, NOW.plusSeconds(90))
        );
    }

    private static SandboxSession.Snapshot snapshot(SessionState state, InstanceInfo info, Integer exitCode, Instant stoppingSince) {
        return new SandboxSession.Snapshot(state, "r8tq2c", info, exitCode, stoppingSince, "", false, false);
    }

    private static HeaderModel model(List<String> settings) {
        final String labels = settings.stream().map(label -> "\"" + label + "\"").collect(java.util.stream.Collectors.joining(", "));
        final InstanceInfo info = HoleListParser.parse(
            "[{\"instanceId\": \"r8tq2c\", \"status\": \"running\", \"projectPath\": \"/work/acme-cms\", \"settings\": [" + labels + "]}]"
        ).get(0);
        return HeaderModel.of(snapshot(SessionState.RUNNING, info, null, null), false, HeaderModel.Checkout.NONE, NOW);
    }

    private static SandboxSession.Snapshot restarting(Instant stoppingSince) {
        return new SandboxSession.Snapshot(SessionState.STOPPING, "r8tq2c", INFO, null, stoppingSince, "", false, true);
    }
}
