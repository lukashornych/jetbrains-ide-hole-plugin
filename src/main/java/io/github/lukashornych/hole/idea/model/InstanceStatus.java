package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Lifecycle status of a sandbox as reported by `hole list --json` (`status` field).
 */
public enum InstanceStatus {
    /** The state file is written, the gateway and containers are starting. */
    STARTING,
    /** The agent container is up and the terminal is attached to it. */
    RUNNING,
    /** Teardown has begun. */
    STOPPING,
    /** A value this plugin version does not know (a newer Hole). */
    UNKNOWN;

    /**
     * Maps the JSON value to a status. A missing value means `running`, as Hole reports instances started by an older
     * Hole without a phase; an unrecognised value maps to {@link #UNKNOWN}.
     */
    @NotNull
    public static InstanceStatus fromJson(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return RUNNING;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "starting" -> STARTING;
            case "running" -> RUNNING;
            case "stopping" -> STOPPING;
            default -> UNKNOWN;
        };
    }
}
