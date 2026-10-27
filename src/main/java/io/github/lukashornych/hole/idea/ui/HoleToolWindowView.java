package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.session.SessionPresentation;
import io.github.lukashornych.hole.idea.session.SessionView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * The Hole tool window as a {@link SessionView}: one {@link Content} per session. Closing a tab is vetoed in
 * {@link #contentRemoveQuery(ContentManagerEvent)} and turned into a stop request; the manager removes the tab when
 * the process has exited. Used on the EDT only.
 *
 * <p>The tab body ({@link SandboxTabPanel} in the IDE) is the content's component and, being {@link Disposable}, is
 * disposed by the content itself; it is not registered as the content's disposer as well.</p>
 */
final class HoleToolWindowView implements SessionView, ContentManagerListener {

    private static final Key<SandboxSession> SESSION = Key.create("hole.session");

    private final Project project;
    private final ContentManager contentManager;
    private final SandboxSessionManager manager;
    private final Map<SandboxSession, Content> contents = new HashMap<>();
    private final Map<SandboxSession, Tab> panels = new HashMap<>();
    private final Function<SandboxSession, Tab> tabs;

    HoleToolWindowView(@NotNull Project project, @NotNull ToolWindow toolWindow, @NotNull SandboxSessionManager manager) {
        this(project, toolWindow.getContentManager(), manager, session -> new SandboxTabPanel(project, session, manager));
    }

    /**
     * @param contentManager the tool window's contents
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
        this.contentManager.addContentManagerListener(this);
    }

    @Override
    public void add(@NotNull SandboxSession session) {
        final Tab panel = this.tabs.apply(session);
        // the content disposes its Disposable component; setDisposer(panel) would hand it to Disposer.dispose twice
        final Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setCloseable(true);
        content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
        content.putUserData(SESSION, session);
        this.contents.put(session, content);
        this.panels.put(session, panel);
        present(session, content);
        this.contentManager.addContent(content);
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
        if (content != null) {
            this.contentManager.setSelectedContent(content, true);
        }
    }

    @Override
    public void remove(@NotNull SandboxSession session) {
        final Content content = this.contents.get(session);
        if (content != null) {
            this.contentManager.removeContent(content, true);
        }
    }

    @Override
    public void selectionChanged(@NotNull ContentManagerEvent event) {
        final SandboxSession session = session(event.getContent());
        if (session != null && event.getOperation() == ContentManagerEvent.ContentOperation.add) {
            this.manager.selected(session);
        }
    }

    @Override
    public void contentRemoveQuery(@NotNull ContentManagerEvent event) {
        final SandboxSession session = session(event.getContent());
        if (session == null || this.project.isDisposed() || this.manager.mayRemove(session)) {
            return;
        }
        // no confirmation: SIGINT now, the tab goes away when Hole has exited
        event.consume();
        this.manager.requestClose(session);
    }

    @Override
    public void contentRemoved(@NotNull ContentManagerEvent event) {
        final SandboxSession session = session(event.getContent());
        if (session != null) {
            this.contents.remove(session);
            this.panels.remove(session);
            this.manager.tabRemoved(session);
        }
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

    @Nullable
    private static SandboxSession session(@Nullable Content content) {
        return content == null ? null : content.getUserData(SESSION);
    }

    /**
     * Body of one tab: header and terminal of a session. Disposed with its content.
     */
    abstract static class Tab extends JPanel implements Disposable {

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
