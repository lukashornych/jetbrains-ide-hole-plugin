package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SessionState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;

/**
 * What the sandbox header shows, derived from a session snapshot without Swing so it can be tested.
 *
 * @param instanceId      instance ID; known from the start, because the plugin chooses it
 * @param debug           show the Debug Shell pill
 * @param pending         metadata has not arrived yet: show skeleton placeholders
 * @param metadata        metadata fields are known
 * @param docker          Docker-in-Docker sidecar enabled
 * @param dockerToggle    the Docker pill restarts the sandbox with Docker-in-Docker toggled (only while RUNNING)
 * @param globalUsed      the sandbox uses a global settings file (a `global(<path>)` label)
 * @param projectUsed     the sandbox uses a project settings file (a `project` or `project(<path>)` label)
 * @param settingsTooltip settings labels as in `hole list`, e.g. `global(~/.hole/settings.json)+project`
 * @param project         absolute project path
 * @param stopping        "Stopping for N s" or "Restarting for N s", {@code null} when not stopping
 * @param forceStop       offer Force Stop
 * @param forceStopCloses Force Stop closes the tab (not during a restart, which goes on after the kill)
 * @param restart         offer Restart (only while RUNNING)
 * @param network         offer the Network window (only while RUNNING)
 * @param exited          "Exited with code N", {@code null} unless EXITED
 */
public record HeaderModel(
    @NotNull String instanceId,
    boolean debug,
    boolean pending,
    boolean metadata,
    boolean docker,
    boolean dockerToggle,
    boolean globalUsed,
    boolean projectUsed,
    @NotNull String settingsTooltip,
    @NotNull String project,
    @Nullable String stopping,
    boolean forceStop,
    boolean forceStopCloses,
    boolean restart,
    boolean network,
    @Nullable String exited
) {

    /** Shown when Hole reported no settings files. */
    static final String NO_SETTINGS = "none";
    /** Prefix of the global settings label: `global(<path>)` (`internal/sandbox/list.go`). */
    private static final String GLOBAL_LABEL = "global";
    /** The in-repo project settings label `project`, or `project(<path>)` for a `HOLE_PROJECT_SETTINGS_FILE` override. */
    private static final String PROJECT_LABEL = "project";

    /**
     * Builds the model.
     *
     * @param snapshot session state
     * @param debug    the session runs a debug shell
     * @param now      current time (stopping timer)
     */
    @NotNull
    public static HeaderModel of(@NotNull SandboxSession.Snapshot snapshot, boolean debug, @NotNull Instant now) {
        final InstanceInfo info = snapshot.info();
        final boolean alive = snapshot.state().isAlive();
        final boolean running = snapshot.state() == SessionState.RUNNING;
        String stopping = null;
        boolean forceStop = false;
        if (snapshot.state() == SessionState.STOPPING && snapshot.stoppingSince() != null) {
            final Duration since = nonNegative(Duration.between(snapshot.stoppingSince(), now));
            stopping = (snapshot.restartPending() ? "Restarting for " : "Stopping for ") + since.toSeconds() + " s";
            forceStop = since.compareTo(SandboxSession.FORCE_STOP_AFTER) >= 0;
        }
        final String exited = snapshot.state() == SessionState.EXITED
            ? "Exited with code " + snapshot.exitCode()
            : null;
        return new HeaderModel(
            snapshot.instanceId(),
            debug,
            info == null && alive,
            info != null,
            info != null && info.docker(),
            running && info != null,
            info != null && info.settings().stream().anyMatch(label -> label.startsWith(GLOBAL_LABEL + "(")),
            info != null && info.settings().stream().anyMatch(
                label -> label.equals(PROJECT_LABEL) || label.startsWith(PROJECT_LABEL + "(")),
            info == null ? "" : info.settingsLabel().isEmpty() ? NO_SETTINGS : info.settingsLabel(),
            info == null ? "" : info.projectPath(),
            stopping,
            forceStop,
            forceStop && !snapshot.restartPending(),
            running,
            running,
            exited
        );
    }

    @NotNull
    private static Duration nonNegative(@NotNull Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
