package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.process.ShellEnvironment;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.FakeShell;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manager logic (tabs, close veto, history, shutdown) with a recording view and the fake `hole` script in real ptys.
 * Every start reads its environment through the fake login shell, launched like from a terminal (`SHLVL=2`).
 */
class SandboxSessionManagerTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private FakeShell shell;
    private ShellEnvironment shellEnvironment;
    /** Runs before every shell environment read of the manager, with the 1-based number of the read. */
    private volatile java.util.function.IntConsumer beforeEnvironmentRead = read -> { };
    private final java.util.concurrent.atomic.AtomicInteger environmentReads = new java.util.concurrent.atomic.AtomicInteger();
    /** When set, the next task given to the background executor waits for this latch. */
    private final java.util.concurrent.atomic.AtomicReference<CountDownLatch> holdNextTask = new java.util.concurrent.atomic.AtomicReference<>();
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
        this.shell = FakeShell.install(this.temp.resolve("shell"));
        this.shellEnvironment = new ShellEnvironment(this::baseEnvironment, Map::of, Duration.ofSeconds(10), true);
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
            new io.github.lukashornych.hole.idea.hole.InstanceIdGenerator()::generate, Clock.systemUTC(), this::executeInBackground,
            PasteDirectory.disabled()
        );
        this.manager = new SandboxSessionManager(
            this.project, () -> this.capability.get(), () -> {
                this.beforeEnvironmentRead.accept(this.environmentReads.incrementAndGet());
                return this.shellEnvironment.read();
            }, services, this.ui, this.notifier
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

    @Test
    void restartProbesOffTheUiThreadAndStartsTheNewConfigInTheSameTab() throws Exception {
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        final Thread uiThread = onUi(Thread::currentThread);
        final List<Thread> probes = new CopyOnWriteArrayList<>();
        final Supplier<HoleCapability> real = this.capability;
        this.capability = () -> {
            probes.add(Thread.currentThread());
            return real.get();
        };

        assertTrue(this.manager.restart(session, SandboxConfig.defaults().withProfile("research")).get(20, TimeUnit.SECONDS));
        assertEquals(1, probes.size(), "Hole is probed again");
        assertFalse(probes.contains(uiThread), "probe and spawn run off the UI thread");

        waitUntil(() -> this.view.ops.contains("restart " + id(session)), () -> "terminal reconnected: " + this.view.ops);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        assertEquals("research", session.launch().config().profile());
        assertTrue(this.hole.calls().stream().anyMatch(call -> call.startsWith("start claude:research . --instance-id ")),
            this.hole.calls().toString());
        assertEquals(List.of(session), this.manager.sessions(), "same tab");
        assertFalse(this.view.removed.contains(session));
    }

    @Test
    void restartOfADebugShellStartsADebugShellAgain() throws Exception {
        final SandboxSession session = this.manager.start(SandboxConfig.defaults(), true, "aaaaaa").get(20, TimeUnit.SECONDS);
        waitUntil(() -> session != null && this.view.added.contains(session), "tab added");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");

        assertTrue(this.manager.restart(session, null).get(20, TimeUnit.SECONDS));
        waitUntil(() -> this.hole.calls().stream().filter(call -> call.startsWith("start ")).count() == 2, () -> this.hole.calls().toString());
        final List<String> starts = this.hole.calls().stream().filter(call -> call.startsWith("start ")).toList();
        assertTrue(starts.get(1).startsWith("start claude . -d --instance-id "), starts.toString());
        assertTrue(session.launch().debug());
    }

    @Test
    void restartIsRefusedWhenTheProbeFailsAndLeavesTheSandboxRunning() throws Exception {
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        this.capability = () -> new HoleCapability(HoleCapability.Status.NOT_FOUND, null, "Hole was not found");

        assertFalse(this.manager.restart(session, null).get(20, TimeUnit.SECONDS));
        assertEquals(List.of("cannotStart NOT_FOUND"), this.notifier.events);
        assertEquals(SessionState.RUNNING, session.snapshot().state());
        assertTrue(session.process().isAlive(), "no SIGINT");
        assertEquals(1, this.hole.calls().stream().filter(call -> call.startsWith("start ")).count());
    }

    @Test
    void restartOfANotRunningSessionDoesNotProbe() throws Exception {
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "30");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        assertEquals(SessionState.STARTING, session.snapshot().state());
        this.capability = () -> {
            throw new AssertionError("no probe for a refused restart");
        };
        assertFalse(this.manager.restart(session, null).get(20, TimeUnit.SECONDS));
        assertTrue(this.notifier.events.isEmpty(), this.notifier.events.toString());
        assertEquals(1, this.shell.calls().size(), "no shell environment read: " + this.shell.calls());
    }

    @Test
    void restartOfAnExitedSessionReadsTheEnvironmentAgainAndStartsInTheSameTab() throws Exception {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        // the user fixed the cause (e.g. in the shell profile): the next read no longer carries it
        this.hole.environment().remove("FAKE_HOLE_START_EXIT");
        final List<Thread> probes = new CopyOnWriteArrayList<>();
        final Supplier<HoleCapability> real = this.capability;
        this.capability = () -> {
            probes.add(Thread.currentThread());
            return real.get();
        };

        assertTrue(this.manager.restart(session, null).get(20, TimeUnit.SECONDS));
        assertEquals(2, this.shell.calls().size(), "the shell environment is read again: " + this.shell.calls());
        assertEquals(1, probes.size(), "Hole is probed again");
        waitUntil(() -> this.view.ops.contains("restart " + id(session)), () -> "terminal reconnected: " + this.view.ops);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        assertNotEquals("aaaaaa", session.snapshot().instanceId(), "a new instance ID");
        assertNull(session.snapshot().exitCode());
        assertEquals(2, this.hole.calls().stream().filter(call -> call.startsWith("start ")).count());
        assertEquals(List.of(session), this.manager.sessions(), "same tab");
        assertFalse(this.view.removed.contains(session));
        assertTrue(this.notifier.events.isEmpty(), this.notifier.events.toString());
    }

    @Test
    void restartWhoseNewProcessCannotBeSpawnedNotifiesAndKeepsTheTabExited() throws Exception {
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        final Path broken = Files.createDirectories(this.temp.resolve("not-a-binary"));
        this.capability = () -> new HoleCapability(HoleCapability.Status.SUPPORTED, broken, "");

        assertTrue(this.manager.restart(session, SandboxConfig.defaults().withAgentArgs("--token s3cr3t")).get(20, TimeUnit.SECONDS));
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        waitUntil(() -> !this.notifier.events.isEmpty(), "notified");
        assertTrue(this.notifier.events.get(0).startsWith("restartFailed"), this.notifier.events.toString());
        assertFalse(this.notifier.events.get(0).contains("s3cr3t"), "no command line in the message");
        onUi(() -> null);
        assertFalse(this.view.removed.contains(session), "the tab stays with the old output");
    }

    @Test
    void staleRestartOfAnExitedSessionDoesNotStopItAfterAnotherRestartBroughtItBack() throws Exception {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        this.hole.environment().remove("FAKE_HOLE_START_EXIT");
        // a double click on Restart of the exited sandbox: both clicks happen before the first relaunch
        final CountDownLatch secondClicked = new CountDownLatch(1);
        this.beforeEnvironmentRead = read -> {
            if (read == 2) {
                await(secondClicked);
            }
        };

        final CompletableFuture<Boolean> first = this.manager.restart(session, null);
        // the task of the second click starts only once the first relaunch runs
        final CountDownLatch secondTask = new CountDownLatch(1);
        this.holdNextTask.set(secondTask);
        final CompletableFuture<Boolean> second = this.manager.restart(session, null);
        secondClicked.countDown();
        assertTrue(first.get(20, TimeUnit.SECONDS));
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "the first restart runs");
        secondTask.countDown();

        assertFalse(second.get(20, TimeUnit.SECONDS), "the stale click is refused");
        assertEquals(SessionState.RUNNING, session.snapshot().state(), "no unconfirmed stop of the running sandbox");
        assertFalse(session.snapshot().restartPending());
        assertTrue(session.process().isAlive());
        assertEquals(2, this.hole.calls().stream().filter(call -> call.startsWith("start ")).count(), this.hole.calls().toString());
    }

    @Test
    void sandboxGetsVariablesFromTheShellProfileWhenTheIdeWasLaunchedFromATerminal() throws Exception {
        // regression: the platform's snapshot skips the shell with SHLVL > 0, so Hole saw `$PROJECT_PATH` unexpanded
        this.shell.export("PROJECT_PATH", "/home/user/project");
        this.hole.environment().put("FAKE_HOLE_PRINT_ENV", "PROJECT_PATH");
        assertFalse(this.baseEnvironment().containsKey("PROJECT_PATH"));

        startAndWait(SandboxConfig.defaults(), "aaaaaa");

        waitUntil(() -> this.view.terminals.get(0).output().contains("PROJECT_PATH=/home/user/project"),
            () -> this.view.terminals.get(0).output());
    }

    @Test
    void everyStartReadsTheShellEnvironmentAgain() throws Exception {
        this.shell.export("PROJECT_PATH", "/home/user/first");
        this.hole.environment().put("FAKE_HOLE_PRINT_ENV", "PROJECT_PATH");
        startAndWait(SandboxConfig.defaults(), "aaaaaa");
        this.shell.export("PROJECT_PATH", "/home/user/second");
        startAndWait(SandboxConfig.defaults(), "bbbbbb");

        waitUntil(() -> this.view.terminals.get(1).output().contains("PROJECT_PATH=/home/user/second"),
            () -> this.view.terminals.get(1).output());
        assertTrue(this.view.terminals.get(0).output().contains("PROJECT_PATH=/home/user/first"));
        assertEquals(2, this.shell.calls().size(), this.shell.calls().toString());
    }

    @Test
    void unreadableShellEnvironmentStartsNothingAndRetryStarts() throws Exception {
        this.shell.environment().put("FAKE_SHELL_EXIT", "1");
        assertNull(this.manager.start(SandboxConfig.defaults(), false, "aaaaaa").get(20, TimeUnit.SECONDS));
        assertEquals(List.of("environmentUnavailable " + this.shell.executable() + ": exited with code 1"), this.notifier.events);
        assertTrue(this.hole.calls().stream().noneMatch(call -> call.startsWith("start") || call.startsWith("version")),
            "neither probed nor started: " + this.hole.calls());
        assertTrue(this.view.ops.isEmpty());

        // the user fixed the shell profile and clicks Retry
        this.shell.environment().remove("FAKE_SHELL_EXIT");
        this.notifier.retries.get(0).run();
        waitUntil(() -> this.view.added.size() == 1, () -> "started on retry: " + this.notifier.events);
        assertEquals("aaaaaa", this.view.added.get(0).snapshot().instanceId());
        assertEquals(1, this.notifier.events.size());
    }

    @Test
    void cancelledShellEnvironmentReadStartsNothingWithoutADialog() throws Exception {
        final SandboxSessionManager cancelling = new SandboxSessionManager(
            this.project, () -> this.capability.get(), () -> {
                throw io.github.lukashornych.hole.idea.process.ShellEnvironmentException.cancelled("/bin/zsh");
            },
            new SessionServices(new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
                () -> "aaaaaa", Clock.systemUTC(), this.background, PasteDirectory.disabled()),
            this.ui, this.notifier
        );
        assertNull(cancelling.start(SandboxConfig.defaults(), false, "aaaaaa").get(20, TimeUnit.SECONDS));
        assertTrue(this.notifier.events.isEmpty(), this.notifier.events.toString());
        assertTrue(this.hole.calls().isEmpty(), this.hole.calls().toString());
    }

    @Test
    void restartWhoseShellEnvironmentCannotBeReadLeavesTheSandboxRunning() throws Exception {
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        this.shell.environment().put("FAKE_SHELL_EXIT", "1");

        assertFalse(this.manager.restart(session, null).get(20, TimeUnit.SECONDS));
        assertEquals(1, this.notifier.events.size(), this.notifier.events.toString());
        assertTrue(this.notifier.events.get(0).startsWith("environmentUnavailable"), this.notifier.events.toString());
        assertEquals(SessionState.RUNNING, session.snapshot().state());
        assertTrue(session.process().isAlive(), "no SIGINT");
        assertEquals(1, this.hole.calls().stream().filter(call -> call.startsWith("start ")).count());
    }

    @Test
    void holeIsLocatedOnThePathTheStartHasJustRead() throws Exception {
        // `hole` is only on the PATH an rc file sets (like Homebrew's in ~/.zprofile); the probe reads latest()
        final HoleExecutable detecting = new HoleExecutable(() -> "", new CliRunner(this.shellEnvironment::latest));
        this.capability = detecting::probe;
        final String systemPath = this.baseEnvironment().get("PATH");
        assertFalse(systemPath.contains(this.hole.binDirectory().toString()));
        this.shellEnvironment.read();
        assertTrue(detecting.locate().isEmpty(), "not found with the environment read so far");

        this.shell.export("PATH", this.hole.binDirectory() + ":" + systemPath);
        final SandboxSession session = startAndWait(SandboxConfig.defaults(), "aaaaaa");

        assertEquals(this.hole.executable().toAbsolutePath(), session.launch().executable());
    }

    /**
     * Environment of the IDE process: launched from a terminal, nothing from the shell profile.
     */
    @NotNull
    private Map<String, String> baseEnvironment() {
        final Map<String, String> base = new HashMap<>(this.hole.environment());
        base.put("SHELL", this.shell.executable().toString());
        base.put("SHLVL", "2");
        base.putAll(this.shell.environment());
        return base;
    }

    private void executeInBackground(@NotNull Runnable task) {
        final CountDownLatch hold = this.holdNextTask.getAndSet(null);
        this.background.execute(hold == null ? task : () -> {
            await(hold);
            task.run();
        });
    }

    private static void await(@NotNull CountDownLatch latch) {
        try {
            assertTrue(latch.await(20, TimeUnit.SECONDS), "latch released");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
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
        final List<TerminalDrain> terminals = new CopyOnWriteArrayList<>();

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
        final List<Runnable> retries = new CopyOnWriteArrayList<>();

        @Override
        public void environmentUnavailable(@NotNull String shell, @NotNull String reason, @NotNull Runnable retry) {
            this.events.add("environmentUnavailable " + shell + ": " + reason);
            this.retries.add(retry);
        }

        @Override
        public void cannotStart(@NotNull HoleCapability capability) {
            this.events.add("cannotStart " + capability.status());
        }

        @Override
        public void spawnFailed(@NotNull String reason) {
            this.events.add("spawnFailed " + reason);
        }

        @Override
        public void restartFailed(@NotNull String reason) {
            this.events.add("restartFailed " + reason);
        }
    }
}
