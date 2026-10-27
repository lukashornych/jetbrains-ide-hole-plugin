package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Entry point of every start (New Sandbox dialog, favourites, empty state): shows the Hole tool window and asks the
 * session manager to start. Called on the EDT; the probe and spawn run in the background.
 */
public final class SandboxStarter {

    private SandboxStarter() {
    }

    /**
     * Starts a sandbox in a new tab.
     *
     * @param config     what to start
     * @param debug      open a debug shell instead of the agent
     * @param instanceId instance ID to use, {@code null} to generate one
     */
    public static void start(@NotNull Project project, @NotNull SandboxConfig config, boolean debug, @Nullable String instanceId) {
        final ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(HoleToolWindowFactory.ID);
        if (toolWindow != null) {
            toolWindow.activate(null);
        }
        SandboxSessionManager.getInstance(project).start(config, debug, instanceId);
    }
}
