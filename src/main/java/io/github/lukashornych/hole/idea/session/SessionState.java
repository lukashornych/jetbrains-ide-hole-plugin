package io.github.lukashornych.hole.idea.session;

/**
 * Lifecycle state of one sandbox tab.
 *
 * <pre>
 * STARTING --(Hole reports running)--> RUNNING
 * STARTING|RUNNING --(Hole reports stopping, or the plugin sends SIGINT)--> STOPPING
 * process exit: stop requested by the plugin or exit code 0 --> CLOSED (the tab is removed)
 *               otherwise                                    --> EXITED (the tab stays with "Exited with code N")
 * EXITED --(Close)--> CLOSED
 * </pre>
 */
public enum SessionState {
    /** The `hole` process runs; Hole has not reported the agent as running yet. */
    STARTING,
    /** The agent runs. */
    RUNNING,
    /** Teardown has begun (Hole reported it, or the plugin sent SIGINT). */
    STOPPING,
    /** The process ended on its own with a non-zero exit code; the tab stays so the output can be read. */
    EXITED,
    /** Finished; the tab is (being) removed. */
    CLOSED;

    /**
     * Returns {@code true} while the `hole` process may still run.
     */
    public boolean isAlive() {
        return this == STARTING || this == RUNNING || this == STOPPING;
    }
}
