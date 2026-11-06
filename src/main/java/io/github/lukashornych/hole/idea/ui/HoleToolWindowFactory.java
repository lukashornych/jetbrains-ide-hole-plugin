package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ex.ToolWindowEx;
import com.intellij.openapi.wm.impl.content.ToolWindowContentUi;
import com.intellij.util.ui.StatusText;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.process.ShellEnvironment;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Creates the bottom <b>Hole</b> tool window, in which each tab is one running sandbox. The tab strip ends with
 * {@code +} (New Sandbox) and the favourites dropdown; without tabs the window shows an empty state with a
 * <i>New Sandbox…</i> link and the favourites ({@link HoleEmptyState}). On Windows it only shows that Hole is not
 * supported.
 *
 * <p>The window opts in to the platform's tab drag and drop, which also offers <i>Split Right / Split Down</i>:
 * sandbox tabs can be shown side by side in panes ({@link HoleToolWindowView}).</p>
 */
public final class HoleToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Tool window ID from `plugin.xml`. */
    public static final String ID = "Hole";

    private static final Logger LOG = Logger.getInstance(HoleToolWindowFactory.class);

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
        allowTabSplitting(toolWindow);
        if (toolWindow instanceof ToolWindowEx toolWindowEx) {
            toolWindowEx.setTabActions(new NewSandboxAction(), new FavouritesPopupAction());
        }
        final HoleEmptyState emptyState = emptyText == null ? null : new HoleEmptyState(project, toolWindow, emptyText);
        if (emptyState != null) {
            emptyState.render();
        }
        // tell the user early when Hole is missing or too old; starts are gated again by every start
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // read the shell environment first, so the probe and the first CLI calls look up `hole` on its PATH
            ShellEnvironment.getInstance().latest();
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

    /**
     * Lets the user split the window into panes. No stable API covers every supported IDE, so this is a known
     * exception to the "no deprecated or experimental API" rule: 2025.2 and 2025.3 enable splitting through the
     * {@code ALLOW_DND_FOR_TABS} client property (deprecated from 2025.3), 2026.1+ only through
     * {@code ToolWindow.setTabsSplittingAllowed(boolean)} ({@code @ApiStatus.Experimental}), which does not exist in
     * 2025.2 and is therefore called reflectively. Replace both with the stable API once there is one and
     * {@code sinceBuild} reaches it.
     */
    @ApiStatus.Experimental
    static void allowTabSplitting(@NotNull ToolWindow toolWindow) {
        toolWindow.getComponent().putClientProperty(ToolWindowContentUi.ALLOW_DND_FOR_TABS, Boolean.TRUE);
        try {
            ToolWindow.class.getMethod("setTabsSplittingAllowed", boolean.class).invoke(toolWindow, true);
        } catch (NoSuchMethodException e) {
            // IDE older than 2026.1: the client property is enough
        } catch (ReflectiveOperationException e) {
            LOG.warn("Cannot allow splitting of the Hole tool window", e);
        }
    }
}
