package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import com.intellij.ui.content.impl.ContentImpl;
import com.intellij.ui.content.impl.ContentManagerImpl;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.session.ClipboardSink;
import io.github.lukashornych.hole.idea.session.HoleTerminalRunner;
import io.github.lukashornych.hole.idea.session.MetadataSource;
import io.github.lukashornych.hole.idea.session.PasteDirectory;
import io.github.lukashornych.hole.idea.session.ProcessSignaller;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.session.SessionServices;
import io.github.lukashornych.hole.idea.session.SessionState;
import io.github.lukashornych.hole.idea.session.StartFailureNotifier;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * The tool window view against a real {@code ContentManagerImpl} (the headless {@code MockToolWindow} has a mock
 * content manager that never asks {@code contentRemoveQuery}), the real session manager and the fake `hole` script.
 * The tab bodies are fakes that read the pty like a terminal, so no terminal widget is created. Panes of a split tool
 * window are nested managers of the root, and moves between them are done the way the platform does them.
 */
public class HoleToolWindowViewTest extends BasePlatformTestCase {

    private Path temp;
    private FakeHole hole;
    private MetadataPoller poller;
    private ExecutorService background;
    private SandboxSessionManager manager;
    private ContentManager contentManager;
    private final Map<SandboxSession, FakeTab> tabs = new ConcurrentHashMap<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.temp = Files.createTempDirectory("hole-view-test");
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "0.3");
        final Path project = Files.createDirectories(this.temp.resolve("demo"));
        final HoleListClient client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.poller = new MetadataPoller(client::listAll, Executors.newSingleThreadScheduledExecutor(), true,
            Duration.ofMillis(100), Duration.ofMillis(300));
        this.background = Executors.newCachedThreadPool();
        final HoleExecutable executable = new HoleExecutable(() -> this.hole.executable().toString(), new CliRunner(this.hole::environment));
        final SessionServices services = new SessionServices(
            new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
            new InstanceIdGenerator()::generate, Clock.systemUTC(), this.background, PasteDirectory.disabled(), ClipboardSink.NONE
        );
        this.manager = new SandboxSessionManager(
            project, executable::probe, this.hole::environment, services,
            runnable -> ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any()),
            new StartFailureNotifier() {
                @Override
                public void cannotStart(@NotNull HoleCapability capability) {
                    fail("cannot start: " + capability.message());
                }

                @Override
                public void environmentUnavailable(@NotNull String shell, @NotNull String reason, @NotNull Runnable retry) {
                    fail("environment unavailable: " + reason);
                }

                @Override
                public void spawnFailed(@NotNull String reason) {
                    fail("spawn failed: " + reason);
                }

                @Override
                public void restartFailed(@NotNull String reason) {
                    fail("restart failed: " + reason);
                }
            }
        );
        this.contentManager = ContentFactory.getInstance().createContentManager(true, getProject());
        Disposer.register(getTestRootDisposable(), this.contentManager);
        final HoleToolWindowView view = new HoleToolWindowView(getProject(), this.contentManager, this.manager, session -> {
            final FakeTab tab = new FakeTab(session);
            this.tabs.put(session, tab);
            return tab;
        });
        this.manager.attachView(view);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            for (SandboxSession session : this.manager.sessions()) {
                session.forceStop();
            }
            this.manager.dispose();
            this.poller.dispose();
            this.background.shutdownNow();
            FileUtil.delete(this.temp);
        } catch (Throwable e) {
            addSuppressedException(e);
        } finally {
            super.tearDown();
        }
    }

    public void testClosingSelectedTabIsVetoedUntilHoleExitsThenPreviousTabIsSelected() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession c = start("cccccc");
        assertSame("a new tab is selected", content(c), this.contentManager.getSelectedContent());
        assertSame(c, this.manager.selectedSession());

        // the user selects a, then c: closing c must go back to a, not to the adjacent b
        this.contentManager.setSelectedContent(content(a));
        assertSame(a, this.manager.selectedSession());
        this.contentManager.setSelectedContent(content(c));
        assertSame(c, this.manager.selectedSession());
        waitOnEdt(() -> c.snapshot().state() == SessionState.RUNNING, "running");

        final Content closed = content(c);
        this.contentManager.removeContent(closed, true);
        assertEquals("vetoed while Hole tears down", 3, this.contentManager.getContentCount());
        assertEquals(SessionState.STOPPING, c.snapshot().state());
        assertTrue(c.snapshot().closeRequested());

        waitOnEdt(() -> this.contentManager.getIndexOfContent(closed) < 0, "tab removed after Hole exited");
        assertEquals(Integer.valueOf(130), c.snapshot().exitCode());
        assertSame(content(a), this.contentManager.getSelectedContent());
        assertSame(a, this.manager.selectedSession());
        assertEquals(java.util.List.of(a, b), this.manager.sessions());
        // the tab body is the content's component and is disposed with it, once
        assertEquals(1, this.tabs.get(c).disposed);
    }

    public void testClosingAnExitedTabRemovesItAtOnce() {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final SandboxSession a = start("aaaaaa");
        waitOnEdt(() -> a.snapshot().state() == SessionState.EXITED, "exited");
        final Content content = content(a);
        waitOnEdt(() -> this.tabs.get(a).rendered > 0, "the tab shows the exit");

        this.contentManager.removeContent(content, true);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(0, this.contentManager.getContentCount());
        assertTrue(this.manager.sessions().isEmpty());
        assertEquals(1, this.tabs.get(a).disposed);
    }

    public void testTabTitleFollowsTheRestartedConfig() {
        final SandboxSession a = start("aaaaaa");
        waitOnEdt(() -> a.snapshot().state() == SessionState.RUNNING, "running");
        final Content content = content(a);
        assertEquals("claude · demo", content.getDisplayName());

        final CompletableFuture<Boolean> restart = this.manager.restart(a, SandboxConfig.defaults().withProfile("research"));
        waitOnEdt(restart::isDone, "restart requested");
        assertTrue(restart.join());
        waitOnEdt(() -> "claude:research · demo".equals(content.getDisplayName()), "title of the new config");
        waitOnEdt(() -> a.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        assertEquals("same tab", 1, this.contentManager.getContentCount());
        assertSame(content, content(a));
    }

    public void testMovingTabsIntoPanesKeepsTheirSandboxesAndTheSelectionHistory() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        waitOnEdt(() -> a.snapshot().state() == SessionState.RUNNING && b.snapshot().state() == SessionState.RUNNING, "running");
        this.contentManager.setSelectedContent(content(a));
        final List<SandboxSession> history = this.manager.selectionHistory();

        // Split Right: both tabs move from the root into two new panes, b's pane is filled last
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(a), left);
        move(content(b), right);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertSame(left, content(a).getManager());
        assertSame(right, content(b).getManager());
        assertEquals(0, this.contentManager.getContentCount());
        for (SandboxSession session : List.of(a, b)) {
            assertEquals(SessionState.RUNNING, session.snapshot().state());
            assertFalse(session.snapshot().closeRequested());
            assertEquals(0, this.tabs.get(session).disposed);
        }
        assertEquals(List.of(a, b), this.manager.sessions());
        assertEquals("a move is not the user's selection", history, this.manager.selectionHistory());
        assertSame(a, this.manager.selectedSession());

        // Unsplit: back into the root
        move(content(b), this.contentManager);
        move(content(a), this.contentManager);
        assertEquals(2, this.contentManager.getContentCount());
        assertEquals(SessionState.RUNNING, b.snapshot().state());
        assertEquals(List.of(a, b), this.manager.sessions());
    }

    public void testClosingATabInAPaneStopsTheSandboxAndRemovesTheTabFromThatPane() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession c = start("cccccc");
        final SandboxSession d = start("dddddd");
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(a), left);
        move(content(b), left);
        move(content(c), right);
        move(content(d), right);
        waitOnEdt(() -> c.snapshot().state() == SessionState.RUNNING, "running");
        left.setSelectedContent(content(b));
        right.setSelectedContent(content(d));
        // the user selects a in the left pane, then c in the right one
        left.setSelectedContent(content(a));
        right.setSelectedContent(content(c));
        assertSame(c, this.manager.selectedSession());

        final Content closed = content(c);
        right.removeContent(closed, true);
        assertEquals("vetoed while Hole tears down", 2, right.getContentCount());
        assertEquals(SessionState.STOPPING, c.snapshot().state());
        assertTrue(c.snapshot().closeRequested());

        waitOnEdt(() -> right.getIndexOfContent(closed) < 0, "tab removed from its pane after Hole exited");
        assertEquals(Integer.valueOf(130), c.snapshot().exitCode());
        assertEquals(List.of(a, b, d), this.manager.sessions());
        assertEquals(1, this.tabs.get(c).disposed);
        // a was already the selected tab of its pane, and the right pane selected d on its own
        assertSame("the previously selected tab, across panes", a, this.manager.selectedSession());
        assertSame(content(a), left.getSelectedContent());
        assertSame(content(d), right.getSelectedContent());
    }

    public void testClosingAnExitedTabInAPaneRemovesIt() {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final SandboxSession a = start("aaaaaa");
        waitOnEdt(() -> a.snapshot().state() == SessionState.EXITED, "exited");
        final ContentManager left = pane();
        pane();
        move(content(a), left);

        left.removeContent(content(a), true);
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
        assertEquals(0, left.getContentCount());
        assertTrue(this.manager.sessions().isEmpty());
        assertEquals(1, this.tabs.get(a).disposed);
    }

    public void testSelectingATabInAPaneIsRecorded() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession c = start("cccccc");
        final SandboxSession d = start("dddddd");
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(a), left);
        move(content(b), left);
        move(content(c), right);
        move(content(d), right);
        left.setSelectedContent(content(b));
        right.setSelectedContent(content(d));

        left.setSelectedContent(content(a));
        assertSame(a, this.manager.selectedSession());
        right.setSelectedContent(content(c));
        assertSame(c, this.manager.selectedSession());
        assertEquals(List.of(a, c), this.manager.selectionHistory().subList(2, 4));
    }

    public void testNewSandboxOpensInTheClickedPaneElseInThePaneOfTheSelectedSandbox() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession x = start("xxxxxx");
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(x), left);
        move(content(a), left);
        move(content(b), right);
        left.setSelectedContent(content(x));
        left.setSelectedContent(content(a));
        assertSame(a, this.manager.selectedSession());

        HoleToolWindowView.rememberPane(getProject(), right);
        final SandboxSession c = start("cccccc");
        assertSame("the clicked pane", right, content(c).getManager());

        // the remembered pane is used once; c is selected now
        final SandboxSession d = start("dddddd");
        assertSame("the pane of the selected sandbox", right, content(d).getManager());

        left.setSelectedContent(content(x));
        left.setSelectedContent(content(a));
        final SandboxSession e = start("eeeeee");
        assertSame(left, content(e).getManager());

        // a pane disposed by an unsplit is not used
        final ContentManager gone = pane();
        HoleToolWindowView.rememberPane(getProject(), gone);
        Disposer.dispose(gone);
        final SandboxSession f = start("ffffff");
        assertSame(left, content(f).getManager());
    }

    public void testClosingTheLastTabOfAPaneKeepsThePreviouslySelectedTabSelectedAfterTheUnsplit() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession c = start("cccccc");
        // the user selected b, then c
        this.contentManager.setSelectedContent(content(b));
        this.contentManager.setSelectedContent(content(c));
        final List<ContentManager> panes = splitWithUnsplitOnEmptyPane();
        final ContentManager left = panes.get(0);
        final ContentManager right = panes.get(1);
        move(content(a), left);
        move(content(b), left);
        move(content(c), right);
        waitOnEdt(() -> c.snapshot().state() == SessionState.RUNNING, "running");
        assertSame(c, this.manager.selectedSession());
        assertSame(content(a), left.getSelectedContent());

        this.manager.requestClose(c);
        waitOnEdt(() -> !this.manager.sessions().contains(c), "closed after Hole exited");
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue();

        assertEquals(2, this.contentManager.getContentCount());
        assertSame(b, this.manager.selectedSession());
        assertSame("the root selected the first tab moved back, the previously selected one is shown",
            content(b), this.contentManager.getSelectedContent());
        assertTrue("the unsplit disposed the panes", left.isDisposed() && right.isDisposed());
        assertEquals(1, this.tabs.get(c).disposed);
        assertEquals(0, this.tabs.get(a).disposed);
    }

    public void testClickedPaneIsTheCellOfTheClickedButton() {
        final HoleToolWindowView.Tab leftTab = new EmptyTab();
        final HoleToolWindowView.Tab rightTab = new EmptyTab();
        final JButton leftPlus = new JButton("+");
        final JButton rightPlus = new JButton("+");
        final JPanel leftCell = cell(leftPlus, leftTab);
        final JPanel rightCell = cell(rightPlus, rightTab);
        final JPanel split = new JPanel();
        split.add(leftCell);
        split.add(rightCell);

        final JPanel window = new JPanel();
        window.add(split);
        window.add(new EmptyTab());
        assertSame(leftTab, HoleToolWindowView.nearestTab(leftPlus, split));
        assertSame(rightTab, HoleToolWindowView.nearestTab(rightPlus, split));
        assertNull(HoleToolWindowView.nearestTab(new JButton("+"), split));
        // not above the ancestor that holds the boundary
        final JButton outside = new JButton("+");
        final JPanel empty = new JPanel();
        empty.add(outside);
        split.add(empty);
        assertNull(HoleToolWindowView.nearestTab(outside, empty));
    }

    public void testATabMarkedForDragButStillInItsPaneIsRemovedOnlyAfterTheDrop() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final SandboxSession c = start("cccccc");
        final SandboxSession d = start("dddddd");
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(a), left);
        move(content(b), left);
        move(content(c), right);
        move(content(d), right);
        waitOnEdt(() -> b.snapshot().state() == SessionState.RUNNING, "running");

        // the drag starts: the mark is set at once, the removal from the pane follows in a later event
        final Content dragged = content(b);
        dragged.putUserData(Content.TEMPORARY_REMOVED_KEY, Boolean.TRUE);
        this.manager.requestClose(b);
        waitOnEdt(() -> !this.manager.sessions().contains(b), "session gone after Hole exited");
        assertSame("not removed during the drag", left, dragged.getManager());
        assertEquals(0, this.tabs.get(b).disposed);

        left.removeContent(dragged, false);
        right.addContent(dragged);
        dragged.putUserData(Content.TEMPORARY_REMOVED_KEY, null);
        waitOnEdt(() -> right.getIndexOfContent(dragged) < 0, "the dropped tab is removed");
        assertEquals(1, this.tabs.get(b).disposed);
        assertEquals(List.of(a, c, d), this.manager.sessions());

        // selections are recorded again
        right.setSelectedContent(content(d));
        right.setSelectedContent(content(c));
        assertSame(c, this.manager.selectedSession());
    }

    public void testRemovalOfADraggedTabIsAppliedWhenItIsDropped() {
        final SandboxSession a = start("aaaaaa");
        final SandboxSession b = start("bbbbbb");
        final ContentManager left = pane();
        final ContentManager right = pane();
        move(content(a), left);
        move(content(b), right);
        waitOnEdt(() -> b.snapshot().state() == SessionState.RUNNING, "running");

        // the user drags b: it belongs to no manager until the drop
        final Content dragged = content(b);
        dragged.putUserData(Content.TEMPORARY_REMOVED_KEY, Boolean.TRUE);
        right.removeContent(dragged, false);
        assertNull(dragged.getManager());

        // the sandbox ends meanwhile
        this.manager.requestClose(b);
        waitOnEdt(() -> !this.manager.sessions().contains(b), "session gone after Hole exited");
        assertEquals(0, this.tabs.get(b).disposed);

        // drop into the left pane: the platform clears the mark only after the add
        left.addContent(dragged);
        dragged.putUserData(Content.TEMPORARY_REMOVED_KEY, null);
        waitOnEdt(() -> left.getIndexOfContent(dragged) < 0, "the dropped tab is removed");
        assertEquals(1, this.tabs.get(b).disposed);
        assertEquals(List.of(a), this.manager.sessions());
        assertSame(content(a), left.getSelectedContent());
    }

    /**
     * A pane's cell as the platform lays it out: the header with the tab actions next to the content.
     */
    @NotNull
    private static JPanel cell(@NotNull JButton plus, @NotNull HoleToolWindowView.Tab tab) {
        final JPanel header = new JPanel();
        header.add(plus);
        final JPanel body = new JPanel();
        body.add(tab);
        final JPanel cell = new JPanel();
        cell.add(header);
        cell.add(body);
        return cell;
    }

    /**
     * A new pane of the root, like a split creates one.
     */
    @NotNull
    private ContentManager pane() {
        final ContentManagerImpl pane = (ContentManagerImpl) ContentFactory.getInstance().createContentManager(true, getProject());
        Disposer.register(this.contentManager, pane);
        ((ContentManagerImpl) this.contentManager).addNestedManager(pane);
        return pane;
    }

    /**
     * Two panes that unsplit like the platform's cell decorators: a listener registered before any content (so it
     * runs before the tabs' listeners) moves the other pane's tabs back into the root once a pane is empty, and
     * disposes both panes.
     */
    @NotNull
    private List<ContentManager> splitWithUnsplitOnEmptyPane() {
        final ContentManager left = pane();
        final ContentManager right = pane();
        final boolean[] unsplitting = {false};
        final ContentManagerListener unsplit = new ContentManagerListener() {
            @Override
            public void contentRemoved(@NotNull ContentManagerEvent event) {
                if (unsplitting[0] || Content.TEMPORARY_REMOVED_KEY.get(event.getContent(), false)
                    || !(left.isEmpty() || right.isEmpty())) {
                    return;
                }
                unsplitting[0] = true;
                try {
                    for (ContentManager pane : List.of(left, right)) {
                        for (Content moved : pane.getContents()) {
                            move(moved, HoleToolWindowViewTest.this.contentManager);
                        }
                    }
                    Disposer.dispose(left);
                    Disposer.dispose(right);
                } finally {
                    unsplitting[0] = false;
                }
            }
        };
        left.addContentManagerListener(unsplit);
        right.addContentManagerListener(unsplit);
        return List.of(left, right);
    }

    /**
     * Moves a tab to another manager as the platform's split and unsplit do ({@code InternalDecoratorImpl.moveContent}).
     */
    private static void move(@NotNull Content content, @NotNull ContentManager target) {
        content.putUserData(Content.TEMPORARY_REMOVED_KEY, Boolean.TRUE);
        try {
            final ContentManager source = content.getManager();
            assertNotNull(source);
            source.removeContent(content, false);
            ((ContentImpl) content).setManager(target);
            target.addContent(content);
        } finally {
            content.putUserData(Content.TEMPORARY_REMOVED_KEY, null);
        }
    }

    @NotNull
    private SandboxSession start(@NotNull String id) {
        final CompletableFuture<SandboxSession> future = this.manager.start(SandboxConfig.defaults(), false, id);
        waitOnEdt(future::isDone, "started " + id);
        final SandboxSession session = future.join();
        assertNotNull("started " + id, session);
        waitOnEdt(() -> contentOrNull(session) != null, "tab of " + id);
        return session;
    }

    @NotNull
    private Content content(@NotNull SandboxSession session) {
        final Content content = contentOrNull(session);
        assertNotNull(content);
        return content;
    }

    @Nullable
    private Content contentOrNull(@NotNull SandboxSession session) {
        final FakeTab tab = this.tabs.get(session);
        return tab == null ? null : tab.content;
    }

    private static void waitOnEdt(@NotNull BooleanSupplier condition, @NotNull String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
        fail("Timed out waiting for: " + what);
    }

    private static final class EmptyTab extends HoleToolWindowView.Tab {
        @Override
        void connect(@NotNull Disposable parent) {
        }

        @Override
        void reconnect() {
        }

        @Override
        void render() {
        }

        @Override
        @Nullable
        JComponent focusComponent() {
            return null;
        }

        @Override
        public void dispose() {
        }
    }

    /**
     * Tab body without a terminal widget: reads the pty like the widget would and counts renders and disposals.
     */
    private static final class FakeTab extends HoleToolWindowView.Tab {
        private final SandboxSession session;
        int rendered;
        int disposed;

        private FakeTab(@NotNull SandboxSession session) {
            this.session = session;
        }

        @Override
        void connect(@NotNull Disposable parent) {
            TerminalDrain.start(this.session.createConnector());
        }

        @Override
        void reconnect() {
            TerminalDrain.start(this.session.createConnector());
        }

        @Override
        void render() {
            this.rendered++;
        }

        @Override
        @Nullable
        JComponent focusComponent() {
            return null;
        }

        @Override
        public void dispose() {
            this.disposed++;
        }
    }
}
