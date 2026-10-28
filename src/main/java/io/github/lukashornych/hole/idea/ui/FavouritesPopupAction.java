package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.storage.GlobalFavouritesService;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * The arrow next to {@code +}: favourites of this project and of all projects (click starts, Shift+click or
 * Shift+Enter starts in Debug Shell), then <i>New Sandbox…</i> and <i>Edit Favourites…</i>.
 */
final class FavouritesPopupAction extends DumbAwareAction {

    /** Ad text of the popup. */
    static final String DEBUG_HINT = "Shift+click or Shift+Enter starts in Debug Shell";

    FavouritesPopupAction() {
        super("Favourites", "Start a favourite sandbox", AllIcons.General.ChevronDown);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project == null) {
            return;
        }
        final Component component = e.getInputEvent() == null ? null : e.getInputEvent().getComponent();
        show(project, popup -> {
            // also places the worktree popup, which may come up to 10 s later (`git worktree list`)
            if (component != null && component.isShowing()) {
                popup.showUnderneathOf(component);
            } else {
                popup.showInFocusCenter();
            }
        });
    }

    /**
     * Shows the favourites dropdown (also from <i>More…</i> on the empty start page).
     *
     * @param project   the IDE project
     * @param placement shows the popup, e.g. underneath the clicked component
     */
    static void show(@NotNull Project project, @NotNull Consumer<ListPopup> placement) {
        // non-blocking: the start probes again and reports a missing or old Hole itself
        final HoleCapability capability = HoleExecutable.getInstance().lastCapability();
        final FavouritesPopupStep step = new FavouritesPopupStep(project, GlobalFavouritesService.all(project), capability, placement);
        final ListPopup popup = JBPopupFactory.getInstance().createListPopup(step);
        popup.setAdText(capability != null && !capability.canStart() ? capability.message() : DEBUG_HINT, SwingConstants.LEFT);
        // public ListPopup API only: Shift+Enter chooses like a Shift+click, the step reads the Shift modifier
        final JComponent content = popup.getContent();
        content.registerKeyboardAction(
            event -> popup.handleSelect(true, new KeyEvent(
                content, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_ENTER, '\n'
            )),
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        );
        placement.accept(popup);
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
