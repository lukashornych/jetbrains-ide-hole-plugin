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
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.session.HoleTerminalRunner;
import io.github.lukashornych.hole.idea.session.MetadataSource;
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

import javax.swing.JComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
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
 * The tab bodies are fakes that read the pty like a terminal, so no terminal widget is created.
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
            new InstanceIdGenerator()::generate, Clock.systemUTC(), this.background
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
        return tab == null ? null : this.contentManager.getContent(tab);
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
