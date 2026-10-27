package io.github.lukashornych.hole.idea.hole;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

/**
 * Result of locating and probing the `hole` executable.
 *
 * @param status     result of the probe; see {@link #canStart()}
 * @param executable the located executable, {@code null} when none was found
 * @param message    human-readable reason, empty for {@link Status#SUPPORTED}
 */
public record HoleCapability(@NotNull Status status, @Nullable Path executable, @NotNull String message) {

    /**
     * Whether the plugin can work with the located Hole.
     */
    public enum Status {
        /** Hole is 2.6.0 or newer (or a development build): it has everything the plugin needs. */
        SUPPORTED,
        /** Hole was found but is older than 2.6.0; the user must run `hole update`. */
        UNSUPPORTED,
        /**
         * Hole was found but `hole version` failed, did not run or printed something unrecognizable. The reason is in
         * the message; whether this blocks starting is decided by the caller.
         */
        UNAVAILABLE,
        /** No `hole` executable was found. */
        NOT_FOUND
    }

    /**
     * Returns {@code true} when the probe confirmed that Hole is new enough. To gate starts use {@link #canStart()}.
     */
    public boolean isSupported() {
        return this.status == Status.SUPPORTED;
    }

    /**
     * Returns {@code true} when sandboxes may be started: Hole is {@link Status#SUPPORTED}, or it is
     * {@link Status#UNAVAILABLE}, i.e. found but its version could not be determined. Such a start is allowed, because
     * Hole then prints its own error in the tab (EXITED state), which tells the user more than the plugin could. {@link Status#UNSUPPORTED} (too old, run
     * `hole update`) and {@link Status#NOT_FOUND} block starts.
     */
    public boolean canStart() {
        return this.status == Status.SUPPORTED || this.status == Status.UNAVAILABLE;
    }
}
