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
 * @param instanceId   instance ID; known from the start, because the plugin chooses it
 * @param debug        show the Debug Shell pill
 * @param pending      metadata has not arrived yet: show skeleton placeholders
 * @param metadata     metadata fields are known
 * @param docker       Docker-in-Docker sidecar enabled
 * @param settings     settings labels as in `hole list`, e.g. `global(~/.hole/settings.json)+project`
 * @param project      absolute project path
 * @param uptime       local uptime computed from `startedAt`
 * @param stopping     "Stopping for N s", {@code null} when not stopping
 * @param forceStop    offer Force Stop
 * @param exited       "Exited with code N", {@code null} unless EXITED
 */
public record HeaderModel(
    @NotNull String instanceId,
    boolean debug,
    boolean pending,
    boolean metadata,
    boolean docker,
    @NotNull String settings,
    @NotNull String project,
    @NotNull String uptime,
    @Nullable String stopping,
    boolean forceStop,
    @Nullable String exited
) {

    /** Shown when Hole reported no settings files. */
    static final String NO_SETTINGS = "none";

    /**
     * Builds the model.
     *
     * @param snapshot session state
     * @param debug    the session runs a debug shell
     * @param now      current time (uptime, stopping timer)
     */
    @NotNull
    public static HeaderModel of(@NotNull SandboxSession.Snapshot snapshot, boolean debug, @NotNull Instant now) {
        final InstanceInfo info = snapshot.info();
        final boolean alive = snapshot.state().isAlive();
        String stopping = null;
        boolean forceStop = false;
        if (snapshot.state() == SessionState.STOPPING && snapshot.stoppingSince() != null) {
            final Duration since = nonNegative(Duration.between(snapshot.stoppingSince(), now));
            stopping = "Stopping for " + since.toSeconds() + " s";
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
            info == null ? "" : info.settingsLabel().isEmpty() ? NO_SETTINGS : info.settingsLabel(),
            info == null ? "" : info.projectPath(),
            info == null || !alive ? "" : formatDuration(info.uptimeAt(now)),
            stopping,
            forceStop,
            exited
        );
    }

    /**
     * Formats an uptime: `45s`, `12m 05s`, `3h 07m`.
     */
    @NotNull
    public static String formatDuration(@NotNull Duration duration) {
        final long seconds = nonNegative(duration).toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format("%dm %02ds", seconds / 60, seconds % 60);
        }
        return String.format("%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    @NotNull
    private static Duration nonNegative(@NotNull Duration duration) {
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
