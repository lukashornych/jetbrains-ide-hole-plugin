package io.github.lukashornych.hole.idea.session;

import org.jetbrains.annotations.NotNull;

/**
 * The tabs that show sessions: the Hole tool window in the IDE, a recording fake in tests. All methods are called on
 * the UI thread of {@link SandboxSessionManager} (the EDT in the IDE).
 */
public interface SessionView {

    /**
     * Shows a new tab for the session, connected to its current process.
     */
    void add(@NotNull SandboxSession session);

    /**
     * The session's state, metadata or title changed; refresh its tab.
     */
    void update(@NotNull SandboxSession session);

    /**
     * The session replaced its process (retry with a new instance ID); connect the terminal to the new process.
     */
    void processRestarted(@NotNull SandboxSession session);

    /**
     * Selects the session's tab. The view reports the change back through
     * {@link SandboxSessionManager#selected(SandboxSession)}.
     */
    void select(@NotNull SandboxSession session);

    /**
     * Removes the session's tab and disposes its terminal.
     */
    void remove(@NotNull SandboxSession session);
}
