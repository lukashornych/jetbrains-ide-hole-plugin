package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manager logic (tabs, close veto, history, shutdown) with a recording view and the fake `hole` script in real ptys.
 */
class SandboxSessionManagerTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private Path project;
    private MetadataPoller poller;
    private ExecutorService background;
    /** Single thread standing in for the EDT. */
    private ExecutorService ui;
    private RecordingView view;
    private RecordingNotifier notifier;
    private SandboxSessionManager manager;
    private Supplier<HoleCapability> capability;

    @BeforeEach
    void setUp() throws Exception {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "0.3");
        this.project = Files.createDirectories(this.temp.resolve("demo"));
        final HoleListClient client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.poller = new MetadataPoller(client::listAll, Executors.newSingleThreadScheduledExecutor(), true,
            Duration.ofMillis(100), Duration.ofMillis(300));
        this.background = Executors.newCachedThreadPool();
        this.ui = Executors.newSingleThreadExecutor();
        this.notifier = new RecordingNotifier();
        final HoleExecutable executable = new HoleExecutable(() -> this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.capability = executable::probe;
        final SessionServices services = new SessionServices(
            new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
            new io.github.lukashornych.hole.idea.hole.InstanceIdGenerator()::generate, Clock.systemUTC(), this.background
        );
        this.manager = new SandboxSessionManager(
            this.project, () -> this.capability.get(), this.hole::environment, services, this.ui, this.notifier
        );
        this.view = new RecordingView(this.manager);
        onUi(() -> {
            this.manager.attachView(this.view);
            return null;
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        for (SandboxSession session : this.manager.sessions()) {
            session.forceStop();
        }
        this.poller.dispose();
        // the background threads wait for the processes and post to the UI executor: stop them first
        this.background.shutdownNow();
        this.background.awaitTermination(20, TimeUnit.SECONDS);
        this.ui.shutdownNow();
    }

    @Test
    void closingRunningTabStopsSandboxThenRemovesItAndSelectsPreviousTab() throws Exception {
        final SandboxSession a = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        final SandboxSession b = startAndWait(SandboxConfig.defaults().withAgent("codex"), "bbbbbb");
        final SandboxSession c = startAndWait(SandboxConfig.defaults().withAgent("gemini"), "cccccc");
        onUi(() -> {
            this.view.userSelects(a);
            this.view.userSelects(c);
            return null;
        });
        assertSame(c, onUi(this.manager::selectedSession));
        waitUntil(() -> c.snapshot().state() == SessionState.RUNNING, "running");

        // the user clicks × on the selected tab: vetoed, SIGINT, stopping
        final boolean removedAtOnce = onUi(() -> this.view.userCloses(c));
        assertFalse(removedAtOnce, "removal is vetoed while Hole tears down");
        assertEquals(SessionState.STOPPING, c.snapshot().state());

        waitUntil(() -> this.view.removed.contains(c), "tab removed after Hole exited");
        assertEquals(130, c.snapshot().exitCode());
        final List<String> ops = this.view.ops;
        assertTrue(ops.indexOf("select " + id(a)) < ops.indexOf("remove " + id(c)), "previous tab selected first: " + ops);
        assertEquals("select " + id(a), ops.get(ops.indexOf("remove " + id(c)) - 1), ops.toString());
        assertSame(a, onUi(this.manager::selectedSession), "not the adjacent tab b");
        assertEquals(List.of(a, b), this.manager.sessions());
        assertFalse(this.hole.calls().isEmpty());
    }

    @Test
    void closingNonSelectedTabLeavesSelectionAlone() throws Exception {
        final SandboxSession a = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        final SandboxSession b = startAndWait(SandboxConfig.defaults().withAgent("codex"), "bbbbbb");
        onUi(() -> {
            this.view.userSelects(a);
            this.view.userSelects(b);
            return null;
        });
        waitUntil(() -> a.snapshot().state() == SessionState.RUNNING, "running");
        final int before = this.view.ops.size();

        assertFalse(onUi(() -> this.view.userCloses(a)), "vetoed while Hole tears down");
        waitUntil(() -> this.view.removed.contains(a), "tab removed after Hole exited");

        assertEquals(List.of("remove " + id(a)), this.view.ops.subList(before, this.view.ops.size()), "no selection change");
        assertSame(b, onUi(this.manager::selectedSession));
        assertEquals(List.of(b), onUi(this.manager::selectionHistory));
        assertEquals(List.of(b), this.manager.sessions());
    }

    @Test
    void tabRemovedFromOutsideForgetsAndStopsTheSession() throws Exception {
        // e.g. the tool window is disposed: the view reports a removal the manager did not start
        final SandboxSession a = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        final SandboxSession b = startAndWait(SandboxConfig.defaults().withAgent("codex"), "bbbbbb");
        onUi(() -> {
            this.view.userSelects(b);
            this.view.userSelects(a);
            return null;
        });
        waitUntil(() -> a.snapshot().state() == SessionState.RUNNING, "running");
        final int before = this.view.ops.size();

        onUi(() -> {
            this.manager.tabRemoved(a);
            return null;
        });

        assertEquals(List.of(b), this.manager.sessions());
        assertNull(onUi(this.manager::selectedSession), "the removed tab is no longer selected");
        assertEquals(List.of(b), onUi(this.manager::selectionHistory));
        assertTrue(a.snapshot().closeRequested(), "the session was disposed");
        waitUntil(() -> !a.process().isAlive(), "Hole exits after SIGINT");
        assertEquals(130, a.process().exitValue());
        onUi(() -> null);
        assertEquals(List.of(), this.view.ops.subList(before, this.view.ops.size()), "the manager does not touch the gone tab");
        assertTrue(onUi(() -> this.manager.mayRemove(a)), "an unknown session is not vetoed");
    }

    @Test
    void sandboxEndingWithCodeZeroRemovesTab() throws Exception {
        this.hole.environment().put("FAKE_HOLE_EXIT_AFTER", "1");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> this.view.removed.contains(session), "removed");
        assertEquals(0, session.snapshot().exitCode());
        assertTrue(this.manager.sessions().isEmpty());
        assertNull(onUi(this.manager::selectedSession));
    }

    @Test
    void failedSandboxKeepsTabUntilClosed() throws Exception {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        onUi(() -> null);
        assertTrue(this.view.updated.contains(session), "tab shows the exit");
        assertFalse(this.view.removed.contains(session));

        // the Close button / × of an exited tab removes it without a signal
        onUi(() -> this.view.userCloses(session));
        waitUntil(() -> this.view.removed.contains(session), "removed after Close");
        assertTrue(this.manager.sessions().isEmpty());
    }

    @Test
    void missingHoleNotifiesAndCreatesNoTab() throws Exception {
        this.capability = () -> new HoleCapability(HoleCapability.Status.NOT_FOUND, null, "Hole was not found");
        assertNull(this.manager.start(SandboxConfig.defaults(), false, null).get(20, TimeUnit.SECONDS));
        assertEquals(List.of("cannotStart NOT_FOUND"), this.notifier.events);
        assertTrue(this.view.ops.isEmpty());
    }

    @Test
    void olderHoleNotifiesUpdateAndStartsNothing() throws Exception {
        this.hole.mode("old");
        assertNull(this.manager.start(SandboxConfig.defaults(), false, null).get(20, TimeUnit.SECONDS));
        assertEquals(List.of("cannotStart UNSUPPORTED"), this.notifier.events);
        assertTrue(this.hole.calls().stream().noneMatch(call -> call.startsWith("start")), this.hole.calls().toString());
        assertTrue(this.view.ops.isEmpty());
    }

    @Test
    void unavailableHoleStillStartsSoHoleCanShowItsError() throws Exception {
        // the version cannot be determined (and there is no container engine): Hole's own error goes to the tab
        this.hole.environment().put("FAKE_HOLE_VERSION", "garbage");
        this.hole.mode("fail");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        assertTrue(this.view.added.contains(session));
        assertTrue(this.notifier.events.isEmpty());
    }

    @Test
    void spawnFailureNotifiesAndCreatesNoTab() throws Exception {
        final Path broken = Files.createDirectories(this.temp.resolve("not-a-binary"));
        this.capability = () -> new HoleCapability(HoleCapability.Status.SUPPORTED, broken, "");
        assertNull(this.manager.start(SandboxConfig.defaults().withAgentArgs("--token s3cr3t"), false, null).get(20, TimeUnit.SECONDS));
        assertEquals(1, this.notifier.events.size());
        assertTrue(this.notifier.events.get(0).startsWith("spawnFailed"), this.notifier.events.toString());
        assertFalse(this.notifier.events.get(0).contains("s3cr3t"), "no command line in the message");
        assertTrue(this.view.ops.isEmpty());
    }

    @Test
    void unexpectedExceptionNotifiesAndCompletesWithoutSession() throws Exception {
        // regression: LOG.error popped the IDE's fatal-error balloon and the exceptional future was never observed
        this.capability = () -> {
            throw new IllegalStateException("probe broke");
        };
        final java.util.concurrent.CompletableFuture<SandboxSession> start = this.manager.start(SandboxConfig.defaults(), false, null);
        assertNull(start.get(20, TimeUnit.SECONDS));
        assertFalse(start.isCompletedExceptionally());
        assertEquals(List.of("spawnFailed probe broke"), this.notifier.events);
        assertTrue(this.view.ops.isEmpty());
    }

    @Test
    void preferredInstanceIdIsUsed() throws Exception {
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "r8tq2c");
        assertEquals("r8tq2c", session.snapshot().instanceId());
        waitUntil(() -> this.hole.calls().contains("start claude . --instance-id r8tq2c"), () -> this.hole.calls().toString());
    }

    @Test
    void disposeSendsSigintToAllWithoutWaiting() throws Exception {
        final SandboxSession a = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        final SandboxSession b = startAndWait(SandboxConfig.defaults(), "bbbbbb");
        waitUntil(() -> a.snapshot().state() == SessionState.RUNNING && b.snapshot().state() == SessionState.RUNNING, "running");

        final long begin = System.nanoTime();
        onUi(() -> {
            this.manager.dispose();
            return null;
        });
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).toMillis() < 1_000, "does not wait for teardown");
        assertTrue(a.snapshot().closeRequested() && b.snapshot().closeRequested());
        waitUntil(() -> !a.process().isAlive() && !b.process().isAlive(), "both Holes exit after SIGINT");
        assertEquals(130, a.process().exitValue());
        assertEquals(130, b.process().exitValue());
        assertTrue(onUi(() -> this.manager.mayRemove(a)), "no veto while the project closes");
        assertNull(this.manager.start(SandboxConfig.defaults(), false, null).get(5, TimeUnit.SECONDS), "no starts after dispose");
    }

    @Test
    void projectClosingDuringStartStillStopsTheSpawnedSandbox() throws Exception {
        // regression: a session spawned after dispose() ran was never signalled and kept running invisibly
        final java.util.concurrent.CountDownLatch probing = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final Supplier<HoleCapability> real = this.capability;
        this.capability = () -> {
            probing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return real.get();
        };
        final java.util.concurrent.CompletableFuture<SandboxSession> start = this.manager.start(SandboxConfig.defaults(), false, "aaaaaa");
        assertTrue(probing.await(10, TimeUnit.SECONDS));
        onUi(() -> {
            this.manager.dispose();
            return null;
        });
        release.countDown();

        assertNull(start.get(20, TimeUnit.SECONDS), "no session is handed out after dispose");
        // without the SIGINT the fake Hole would keep running and keep its instance registered
        final Path instance = this.temp.resolve("fake/state/instances/aaaaaa.json");
        io.github.lukashornych.hole.idea.testutil.Waiting.sleep(1_500);
        waitUntil(() -> !Files.exists(instance), "no sandbox left running");
        onUi(() -> null);
        assertTrue(this.view.ops.isEmpty(), "no tab: " + this.view.ops);
    }

    @NotNull
    private SandboxSession startAndWait(@NotNull SandboxConfig config, @NotNull String id) throws Exception {
        final SandboxSession session = this.manager.start(config, false, id).get(20, TimeUnit.SECONDS);
        if (session == null) {
            throw new AssertionError("not started: " + this.notifier.events);
        }
        waitUntil(() -> this.view.added.contains(session), "tab added");
        return session;
    }

    private <T> T onUi(@NotNull Callable<T> callable) throws Exception {
        return this.ui.submit(callable).get(20, TimeUnit.SECONDS);
    }

    @NotNull
    private static String id(@NotNull SandboxSession session) {
        return Integer.toHexString(System.identityHashCode(session));
    }

    /**
     * Behaves like the tool window: selection changes are reported back, the × goes through the veto.
     */
    private static final class RecordingView implements SessionView {
        private final SandboxSessionManager manager;
        final List<String> ops = new CopyOnWriteArrayList<>();
        final List<SandboxSession> added = new CopyOnWriteArrayList<>();
        final List<SandboxSession> updated = new CopyOnWriteArrayList<>();
        final List<SandboxSession> removed = new CopyOnWriteArrayList<>();
        final List<TerminalDrain> terminals = new ArrayList<>();

        RecordingView(@NotNull SandboxSessionManager manager) {
            this.manager = manager;
        }

        @Override
        public void add(@NotNull SandboxSession session) {
            this.ops.add("add " + id(session));
            this.terminals.add(TerminalDrain.start(session.createConnector()));
            this.added.add(session);
        }

        @Override
        public void update(@NotNull SandboxSession session) {
            this.updated.add(session);
        }

        @Override
        public void processRestarted(@NotNull SandboxSession session) {
            this.ops.add("restart " + id(session));
            this.terminals.add(TerminalDrain.start(session.createConnector()));
        }

        @Override
        public void select(@NotNull SandboxSession session) {
            this.ops.add("select " + id(session));
            this.manager.selected(session);
        }

        @Override
        public void remove(@NotNull SandboxSession session) {
            if (!this.manager.mayRemove(session)) {
                throw new AssertionError("the manager's own removal must not be vetoed");
            }
            this.ops.add("remove " + id(session));
            this.removed.add(session);
        }

        void userSelects(@NotNull SandboxSession session) {
            this.manager.selected(session);
        }

        /**
         * The × of a tab: contentRemoveQuery.
         *
         * @return whether the content was removed at once
         */
        boolean userCloses(@NotNull SandboxSession session) {
            if (this.manager.mayRemove(session)) {
                this.removed.add(session);
                this.manager.tabRemoved(session);
                return true;
            }
            this.manager.requestClose(session);
            return false;
        }
    }

    private static final class RecordingNotifier implements StartFailureNotifier {
        final List<String> events = new CopyOnWriteArrayList<>();

        @Override
        public void cannotStart(@NotNull HoleCapability capability) {
            this.events.add("cannotStart " + capability.status());
        }

        @Override
        public void spawnFailed(@NotNull String reason) {
            this.events.add("spawnFailed " + reason);
        }
    }
}
