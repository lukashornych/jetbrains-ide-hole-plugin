package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.util.ui.StatusText;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the bottom <b>Hole</b> tool window, in which each tab is one running sandbox. The tab strip ends with
 * {@code +} (New Sandbox) and the favourites dropdown; without tabs the window shows an empty state with a
 * <i>New Sandbox…</i> link and the favourites ({@link HoleEmptyState}). On Windows it only shows that Hole is not
 * supported.
 */
public final class HoleToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Tool window ID from `plugin.xml`. */
    public static final String ID = "Hole";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        final StatusText emptyText = toolWindow instanceof ToolWindowEx toolWindowEx ? toolWindowEx.getEmptyText() : null;
        if (SystemInfo.isWindows) {
            if (emptyText != null) {
                emptyText.setText("Hole is supported on macOS and Linux");
            }
            return;
        }
        final SandboxSessionManager manager = SandboxSessionManager.getInstance(project);
        manager.attachView(new HoleToolWindowView(project, toolWindow, manager));
        if (toolWindow instanceof ToolWindowEx toolWindowEx) {
            toolWindowEx.setTabActions(new NewSandboxAction(), new FavouritesPopupAction());
        }
        final HoleEmptyState emptyState = emptyText == null ? null : new HoleEmptyState(project, toolWindow, emptyText);
        if (emptyState != null) {
            emptyState.render();
        }
        // tell the user early when Hole is missing or too old; starts are gated again by every start
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleCapability capability = HoleExecutable.getInstance().probe();
            if (capability.status() == HoleCapability.Status.UNSUPPORTED && !project.isDisposed()) {
                new HoleNotifications(project).cannotStart(capability);
            }
            if (emptyState != null) {
                // favourites are greyed out when Hole cannot start
                ApplicationManager.getApplication().invokeLater(emptyState::render);
            }
        });
    }
}
