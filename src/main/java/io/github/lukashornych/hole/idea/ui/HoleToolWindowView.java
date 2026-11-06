package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.session.SessionPresentation;
import io.github.lukashornych.hole.idea.session.SessionView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The Hole tool window as a {@link SessionView}: one {@link Content} per session. Closing a tab is vetoed in
 * {@link #tabRemoveQuery(SandboxSession, ContentManagerEvent)} and turned into a stop request; the manager removes the
 * tab when the process has exited. Used on the EDT only.
 *
 * <p>The user may split the window into panes, each with its own nested {@link ContentManager}; the root manager only
 * delegates. So every tab has its own {@link SandboxContentListener}, and every call goes to the manager the tab is in
 * now. While the user drags a tab it is in no manager: a removal is then postponed until the tab is dropped, and a
 * selection is skipped.</p>
 *
 * <p>The tab body ({@link SandboxTabPanel} in the IDE) is the content's component and, being {@link Disposable}, is
 * disposed by the content itself; it is not registered as the content's disposer as well.</p>
 */
final class HoleToolWindowView implements SessionView {

    /** The pane whose tab action was clicked last; the next new tab opens there. */
    private static final Key<WeakReference<ContentManager>> CLICKED_PANE = Key.create("hole.clickedPane");

    private final Project project;
    private final ContentManager contentManager;
    private final SandboxSessionManager manager;
    private final Map<SandboxSession, Content> contents = new HashMap<>();
    private final Map<SandboxSession, Tab> panels = new HashMap<>();
    private final Map<SandboxSession, SandboxContentListener> listeners = new HashMap<>();
    /** Sessions whose tab must go away but is being dragged (in no manager). */
    private final Set<SandboxSession> removePending = new HashSet<>();
    /** Sessions whose tab left its manager to move to another one and has not arrived yet. */
    private final Set<SandboxSession> moving = new HashSet<>();
    private final Function<SandboxSession, Tab> tabs;

    HoleToolWindowView(@NotNull Project project, @NotNull ToolWindow toolWindow, @NotNull SandboxSessionManager manager) {
        this(project, toolWindow.getContentManager(), manager, session -> new SandboxTabPanel(project, session, manager));
    }

    /**
     * @param contentManager the tool window's root content manager
     * @param tabs           creates the body of a session's tab
     */
    HoleToolWindowView(
        @NotNull Project project,
        @NotNull ContentManager contentManager,
        @NotNull SandboxSessionManager manager,
        @NotNull Function<SandboxSession, Tab> tabs
    ) {
        this.project = project;
        this.contentManager = contentManager;
        this.manager = manager;
        this.tabs = tabs;
    }

    /**
     * Remembers the pane of the clicked tab action, so that the sandbox it starts opens in that pane. The tab
     * actions' data context names no content manager, so the pane is found in the Swing hierarchy: the nearest
     * ancestor of the clicked component that shows a sandbox tab is that pane. Call first in
     * {@code actionPerformed} of every tab action that starts a sandbox.
     */
    static void rememberClickedPane(@NotNull AnActionEvent event) {
        final Project project = event.getProject();
        if (project == null) {
            return;
        }
        final ToolWindow toolWindow = event.getData(PlatformDataKeys.TOOL_WINDOW);
        final Component clicked = event.getInputEvent() != null
            ? event.getInputEvent().getComponent()
            : event.getData(PlatformDataKeys.CONTEXT_COMPONENT);
        final Tab tab = clicked == null || toolWindow == null || toolWindow.getContentManager().isEmpty()
            ? null
            : nearestTab(clicked, toolWindow.getComponent());
        rememberPane(project, tab == null || tab.content == null ? null : tab.content.getManager());
    }

    /**
     * Remembers the pane the next new tab opens in; {@code null} forgets it.
     */
    static void rememberPane(@NotNull Project project, @Nullable ContentManager pane) {
        project.putUserData(CLICKED_PANE, pane == null ? null : new WeakReference<>(pane));
    }

    @Override
    public void add(@NotNull SandboxSession session) {
        final Tab panel = this.tabs.apply(session);
        // the content disposes its Disposable component; setDisposer(panel) would hand it to Disposer.dispose twice
        final Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(true);
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
        panel.content = content;
        this.contents.put(session, content);
        this.panels.put(session, panel);
        present(session, content);
        // before the add: the listener must join the manager that fires the add's selection
        this.listeners.put(session, new SandboxContentListener(content, session, this));
        targetPane().addContent(content);
        panel.connect(content);
        content.setPreferredFocusedComponent(panel::focusComponent);
    }

    @Override
    public void update(@NotNull SandboxSession session) {
        final Content content = this.contents.get(session);
        if (content != null) {
            present(session, content);
            this.panels.get(session).render();
        }
    }

    @Override
    public void processRestarted(@NotNull SandboxSession session) {
        final Tab panel = this.panels.get(session);
        if (panel != null) {
            panel.reconnect();
            update(session);
        }
    }

    @Override
    public void select(@NotNull SandboxSession session) {
        final Content content = this.contents.get(session);
        final ContentManager pane = content == null ? null : content.getManager();
        // a dragged tab is in no manager; the selection is skipped (a drop does not necessarily select it)
        if (pane != null) {
            pane.setSelectedContent(content, true);
            // a tab that is already the selected one of its pane fires no selection event
            if (pane.isSelected(content)) {
                this.manager.selected(session);
            }
        }
    }

    @Override
    public void remove(@NotNull SandboxSession session) {
        final Content content = this.contents.get(session);
        if (content == null) {
            return;
        }
        final ContentManager pane = content.getManager();
        // a drag marks the content as moving at once but leaves its manager only later (invokeLater); removing it
        // in between would dispose a tab the drop then adds again
        if (pane == null || Content.TEMPORARY_REMOVED_KEY.get(content, false)) {
            this.removePending.add(session);
            return;
        }
        final SandboxSession selected = this.manager.selectedSession();
        pane.removeContent(content, true);
        // the pane selects a neighbour of the removed tab even when the previously selected tab of another pane was
        // chosen before, and an unsplit after the last tab of a pane selects the first tab moved back; the chosen
        // one stays the selected sandbox (without focus: a sandbox may end in the background)
        if (selected != null && selected != session) {
            final Content kept = this.contents.get(selected);
            final ContentManager keptPane = kept == null ? null : kept.getManager();
            if (keptPane != null && !keptPane.isSelected(kept)) {
                keptPane.setSelectedContent(kept, false);
            }
            this.manager.selected(selected);
        }
    }

    /**
     * The session's tab was added to a manager (also after a move or a drop).
     */
    void tabAdded(@NotNull SandboxSession session) {
        this.moving.remove(session);
        if (this.removePending.contains(session)) {
            // not from within the add: the drop still holds the content as being moved
            ApplicationManager.getApplication().invokeLater(() -> removePendingTab(session), this.project.getDisposed());
        }
    }

    /**
     * The session's tab starts moving to another manager (split, unsplit, drag).
     */
    void tabMoving(@NotNull SandboxSession session) {
        this.moving.add(session);
    }

    /**
     * A tab was selected; while a tab moves, the panes select tabs on their own, not on the user's behalf.
     */
    void tabSelected(@NotNull SandboxSession session) {
        if (this.moving.isEmpty()) {
            this.manager.selected(session);
        }
    }

    /**
     * Someone asks to close the session's tab; a running session vetoes it and gets a stop request.
     */
    void tabRemoveQuery(@NotNull SandboxSession session, @NotNull ContentManagerEvent event) {
        if (this.project.isDisposed() || this.manager.mayRemove(session)) {
            return;
        }
        // no confirmation: SIGINT now, the tab goes away when Hole has exited
        event.consume();
        this.manager.requestClose(session);
    }

    /**
     * The session's tab was removed for good.
     */
    void tabRemoved(@NotNull SandboxSession session) {
        forget(session);
        this.manager.tabRemoved(session);
    }

    private void removePendingTab(@NotNull SandboxSession session) {
        if (!this.removePending.remove(session)) {
            return;
        }
        final Content content = this.contents.get(session);
        final ContentManager pane = content == null ? null : content.getManager();
        if (pane == null) {
            if (content != null) {
                // dragged again: the next drop retries
                this.removePending.add(session);
            }
            return;
        }
        if (!pane.isDisposed()) {
            pane.removeContent(content, true);
        }
        if (content.getManager() == null || pane.isDisposed()) {
            // the listener ignores a removal while the content is still marked as being moved
            forget(session);
        }
    }

    private void forget(@NotNull SandboxSession session) {
        this.contents.remove(session);
        this.panels.remove(session);
        this.removePending.remove(session);
        this.moving.remove(session);
        final SandboxContentListener listener = this.listeners.remove(session);
        if (listener != null) {
            listener.detach();
        }
    }

    /**
     * The pane a new tab opens in: the one whose tab action was clicked, else the pane of the selected sandbox, else
     * the root manager (which delegates to the first pane). An unsplit may have disposed a remembered pane.
     */
    @NotNull
    private ContentManager targetPane() {
        final WeakReference<ContentManager> clicked = this.project.getUserData(CLICKED_PANE);
        this.project.putUserData(CLICKED_PANE, null);
        final ContentManager clickedPane = clicked == null ? null : clicked.get();
        if (clickedPane != null && !clickedPane.isDisposed()) {
            return clickedPane;
        }
        final SandboxSession selected = this.manager.selectedSession();
        final Content selectedContent = selected == null ? null : this.contents.get(selected);
        final ContentManager selectedPane = selectedContent == null ? null : selectedContent.getManager();
        if (selectedPane != null && !selectedPane.isDisposed()) {
            return selectedPane;
        }
        return this.contentManager;
    }

    /**
     * Returns a sandbox tab in the nearest ancestor of the component that holds one: from a button of a pane's
     * header, a tab of that pane. Stops at the first ancestor that holds {@code boundary} (the tool window's root
     * content component; the header of the unsplit window is its sibling, so the boundary itself is no ancestor).
     */
    @Nullable
    static Tab nearestTab(@NotNull Component component, @NotNull Component boundary) {
        // the cell of a pane holds its header and its selected tab; a split cell holds only further cells
        for (Component ancestor = component; ancestor != null; ancestor = ancestor.getParent()) {
            final Tab tab = anyTab(ancestor);
            if (tab != null || SwingUtilities.isDescendingFrom(boundary, ancestor)) {
                return tab;
            }
        }
        return null;
    }

    @Nullable
    private static Tab anyTab(@NotNull Component component) {
        if (component instanceof Tab tab) {
            return tab;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Tab tab = anyTab(child);
                if (tab != null) {
                    return tab;
                }
            }
        }
        return null;
    }

    private static void present(@NotNull SandboxSession session, @NotNull Content content) {
        final SandboxSession.Snapshot snapshot = session.snapshot();
        // read once: a restart may replace the launch
        final SandboxSession.Launch launch = session.launch();
        final Path directory = launch.projectDirectory();
        final InstanceInfo info = snapshot.info();
        content.setDisplayName(SessionPresentation.tabTitle(launch.config(), snapshot.agentTitle(), SessionPresentation.projectFolder(directory)));
        content.setIcon(HoleIcons.tabIcon(snapshot.state(), launch.debug()));
        content.setDescription(SessionPresentation.tooltip(
            snapshot.instanceId(),
            info != null && !info.projectPath().isEmpty() ? info.projectPath() : directory.toString()
        ));
    }

    /**
     * Body of one tab: header and terminal of a session. Disposed with its content.
     */
    abstract static class Tab extends JPanel implements Disposable {

        /** The tab's content, set when the tab is added. */
        @Nullable
        Content content;

        Tab() {
            super(new BorderLayout());
        }

        /**
         * Connects the session's current process. Called once, after the content was added.
         *
         * @param parent the tab's content
         */
        abstract void connect(@NotNull Disposable parent);

        /**
         * The session restarted its process; connects the new one.
         */
        abstract void reconnect();

        /**
         * Refreshes what the tab shows from the session.
         */
        abstract void render();

        /**
         * Returns the component to focus when the tab is selected.
         */
        @Nullable
        abstract JComponent focusComponent();
    }
}
