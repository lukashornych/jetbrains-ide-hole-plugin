package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the user why a sandbox did not start when there is no tab to show it in.
 */
public interface StartFailureNotifier {

    /**
     * Hole was not found or is too old ({@link HoleCapability#canStart()} is {@code false}).
     */
    void cannotStart(@NotNull HoleCapability capability);

    /**
     * The `hole` process could not be spawned.
     *
     * @param reason message of the failure, without the command line
     */
    void spawnFailed(@NotNull String reason);
}
