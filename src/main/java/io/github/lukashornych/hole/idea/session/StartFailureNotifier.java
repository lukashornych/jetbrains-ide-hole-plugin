package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the user why a sandbox did not start when there is no tab to show it in, or why a restart ended it.
 */
public interface StartFailureNotifier {

    /**
     * Hole was not found or is too old ({@link HoleCapability#canStart()} is {@code false}).
     */
    void cannotStart(@NotNull HoleCapability capability);

    /**
     * The environment of the user's shell could not be read, so nothing was started (or restarted). The user may
     * retry; there is no start without that environment.
     *
     * @param shell  the shell that was run
     * @param reason why the read failed (exit code, timeout), without environment values or rc-file output
     * @param retry  runs the same start or restart again
     */
    void environmentUnavailable(@NotNull String shell, @NotNull String reason, @NotNull Runnable retry);

    /**
     * The `hole` process could not be spawned.
     *
     * @param reason message of the failure, without the command line
     */
    void spawnFailed(@NotNull String reason);

    /**
     * A restart stopped the old sandbox, but the new `hole` process could not be spawned; the tab shows the exit.
     *
     * @param reason message of the failure, without the command line
     */
    void restartFailed(@NotNull String reason);
}
