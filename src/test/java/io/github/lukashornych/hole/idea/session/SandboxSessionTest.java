package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.MutableClock;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session state machine against the fake `hole` script in a real pty, with the real poller and real signals.
 */
class SandboxSessionTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private Path project;
    private MetadataPoller poller;
    private ExecutorService background;
    private MutableClock clock;
    private final Deque<String> nextIds = new ArrayDeque<>();
    /** Disabled unless a test needs the image paste, so the other tests see the command lines without the mount. */
    private PasteDirectory pastes = PasteDirectory.disabled();
    private final List<SandboxSession> started = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.project = Files.createDirectories(this.temp.resolve("demo"));
        final HoleListClient client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.poller = new MetadataPoller(client::listAll, Executors.newSingleThreadScheduledExecutor(), true,
            Duration.ofMillis(100), Duration.ofMillis(300));
        this.background = Executors.newCachedThreadPool();
        this.clock = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "0.3");
    }

    @AfterEach
    void tearDown() {
        for (SandboxSession session : this.started) {
            session.forceStop();
        }
        this.poller.dispose();
        this.background.shutdownNow();
    }

    @Test
    void closeRequestedWhileRunningSendsSigintAndClosesWhatever130() {
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        assertNotNull(session.snapshot().info(), "metadata arrived");
        assertEquals("demo", session.snapshot().info().projectName());

        session.requestClose();
        final SandboxSession.Snapshot stopping = session.snapshot();
        assertEquals(SessionState.STOPPING, stopping.state(), "stopping right away, before Hole reports it");
        assertTrue(stopping.closeRequested());
        assertEquals(this.clock.instant(), stopping.stoppingSince());

        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        assertEquals(130, session.snapshot().exitCode());
        waitUntil(() -> recorder.drain.output().contains("Destroying sandbox aaaaaa"), () -> "teardown output: " + recorder.drain.output());
        assertTrue(this.hole.calls().stream().anyMatch(call -> call.startsWith("start claude . --instance-id aaaaaa")), this.hole.calls().toString());
    }

    @Test
    void closeDuringImageBuildStopsHoleAndTheBuildAtOnce() {
        // Hole 2.6.0 stop contract: SIGINT to the process group, so the running build step stops too
        this.hole.environment().put("FAKE_HOLE_CHILD", "1");
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        final Path childPid = this.hole.stateDirectory().resolve("child.pid");
        waitUntil(() -> Files.exists(childPid), "image build runs");

        session.requestClose();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        assertEquals(130, session.snapshot().exitCode());
        assertTrue(Files.exists(this.hole.stateDirectory().resolve("child.int")), "the build got SIGINT");
    }

    @Test
    void forceStopKillsTheProcessGroup() {
        this.hole.environment().put("FAKE_HOLE_CHILD", "1");
        this.hole.environment().put("FAKE_HOLE_HANG", "1");
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        waitUntil(() -> Files.exists(this.hole.stateDirectory().resolve("child.pid")), "image build runs");

        session.forceStop();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed after SIGKILL");
        assertEquals(137, session.snapshot().exitCode());
    }

    @Test
    void exitCodeZeroFromWithinCloses() {
        this.hole.environment().put("FAKE_HOLE_EXIT_AFTER", "1");
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        assertEquals(0, session.snapshot().exitCode());
        assertFalse(session.snapshot().closeRequested());
    }

    @Test
    void failureWithoutCloseRequestStaysExitedUntilClosed() {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        assertEquals(1, session.snapshot().exitCode());
        waitUntil(() -> recorder.drain.output().contains("fake start failure"), () -> "error output stays readable: " + recorder.drain.output());

        session.requestClose();
        assertEquals(SessionState.CLOSED, session.snapshot().state());
    }

    @Test
    void duplicateInstanceIdIsRetriedOnceWithNewId() {
        this.hole.putInstance("aaaaaa", "running");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);

        waitUntil(() -> recorder.restarts.get() == 1, "process restarted");
        assertEquals("bbbbbb", session.snapshot().instanceId());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "retry runs");
        assertEquals("bbbbbb", session.snapshot().info().instanceId());
    }

    @Test
    void leftoversOfAnAbandonedSandboxAreRetriedOnceWithNewId() {
        this.hole.putLeftovers("aaaaaa");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);

        waitUntil(() -> recorder.restarts.get() == 1, "process restarted");
        assertEquals("bbbbbb", session.snapshot().instanceId());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "retry runs");
    }

    @Test
    void instanceIdConflictMessagesOfHoleAreRecognized() {
        // Hole 2.6.0 prints `[ERROR] …`, coloured in a terminal
        final String red = "\u001b[0;31m";
        final String reset = "\u001b[0m";
        assertTrue(SandboxSession.reportsInstanceIdConflict(
            red + "[ERROR] instance ID 'r8tq2c' is already in use by /Users/me/projects/acme-cms" + reset + "\r\n", "r8tq2c"));
        assertTrue(SandboxSession.reportsInstanceIdConflict(
            "[ERROR] instance ID 'r8tq2c' has leftovers of an abandoned sandbox; run 'hole list' to clean them up\n", "r8tq2c"));
        assertFalse(SandboxSession.reportsInstanceIdConflict(
            "[ERROR] instance ID 'aaaaaa' is already in use by /x\n", "r8tq2c"), "another ID");
        assertFalse(SandboxSession.reportsInstanceIdConflict(
            "[ERROR] invalid instance ID 'BAD': expected 6 characters a-z, 0-9\n", "BAD"), "malformed IDs are not retried");
        assertFalse(SandboxSession.reportsInstanceIdConflict("[ERROR] invalid agent 'x'\n", "r8tq2c"));
    }

    @Test
    void duplicateInstanceIdIsRetriedOnlyOnce() {
        this.hole.putInstance("aaaaaa", "running");
        this.hole.putInstance("bbbbbb", "running");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);

        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "second failure is shown");
        assertEquals(1, recorder.restarts.get());
        assertEquals("bbbbbb", session.snapshot().instanceId());
        assertEquals(1, session.snapshot().exitCode());
    }

    @Test
    void duplicateIdIsNotRetriedWithoutConnectedTerminal() throws Exception {
        // without a terminal the output is unseen; nothing else may read the pty, so the tab shows EXITED
        this.hole.putInstance("aaaaaa", "running");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession.Launch launch = new SandboxSession.Launch(
            SandboxConfig.defaults(), false, this.hole.executable(), this.project, this.project, this.hole.environment()
        );
        final SandboxSession session = new SandboxSession(launch, "aaaaaa", new SessionServices(
            new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
            this.nextIds::poll, this.clock, this.background, PasteDirectory.disabled()
        ), recorder);
        session.start();
        this.started.add(session);
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        assertEquals(0, recorder.restarts.get());
    }

    @Test
    void noSignalAfterTheProcessExited() {
        // regression: the PID of an exited Hole may be reused, so a late close or Force Stop must not signal it
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "3");
        final AtomicInteger signals = new AtomicInteger();
        final Recorder recorder = new Recorder();
        final SandboxSession.Launch launch = new SandboxSession.Launch(
            SandboxConfig.defaults(), false, this.hole.executable(), this.project, this.project, this.hole.environment()
        );
        final SandboxSession session = new SandboxSession(launch, "aaaaaa", new SessionServices(
            new HoleTerminalRunner(), (pid, signal) -> signals.incrementAndGet() > 0, MetadataSource.of(this.poller),
            this.nextIds::poll, this.clock, this.background, PasteDirectory.disabled()
        ), recorder);
        try {
            session.start();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        this.started.add(session);
        waitUntil(() -> !session.process().isAlive(), "Hole exited");
        session.forceStop();
        session.shutdown();
        assertEquals(0, signals.get());
    }

    @Test
    void otherFailuresAreNotRetried() {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        assertEquals(0, recorder.restarts.get());
        assertEquals("aaaaaa", session.snapshot().instanceId());
    }

    @Test
    void forceStopIsOfferedAfterThirtySecondsAndKillsHangingHole() {
        this.hole.environment().put("FAKE_HOLE_HANG", "1");
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");

        session.requestClose();
        assertFalse(session.canForceStop(this.clock.instant()));
        this.clock.advance(Duration.ofSeconds(29));
        assertFalse(session.canForceStop(this.clock.instant()));
        this.clock.advance(Duration.ofSeconds(1));
        assertTrue(session.canForceStop(this.clock.instant()));
        assertEquals(SessionState.STOPPING, session.snapshot().state(), "SIGINT is ignored by the hanging Hole");

        session.forceStop();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed after SIGKILL");
        assertTrue(session.snapshot().closeRequested());
    }

    @Test
    void holeReportingStoppingDoesNotCloseByItself() {
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");

        this.hole.putInstance("aaaaaa", "stopping");
        waitUntil(() -> session.snapshot().state() == SessionState.STOPPING, "stopping from metadata");
        assertFalse(session.snapshot().closeRequested(), "Hole's status is display only");
        assertNotNull(session.snapshot().stoppingSince());

        session.requestClose();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
    }

    @Test
    void debugStartPassesDebugFlagAndLeavesOutAgentArgs() {
        final SandboxConfig config = SandboxConfig.defaults().withProfile("research").withAgentArgs("--model opus");
        final SandboxSession session = start(config, true, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        assertTrue(session.snapshot().info().flags().debug());
        assertEquals("research", session.snapshot().info().profile());
        assertTrue(this.hole.calls().contains("start claude:research . -d --instance-id aaaaaa"), this.hole.calls().toString());
    }

    @Test
    void agentTitleIsRecordedOnce() {
        // not started: no process and no metadata subscription, so only title changes notify (the poller would
        // notify every 100 ms and make the count racy)
        final Recorder recorder = new Recorder();
        final SandboxSession session = new SandboxSession(new SandboxSession.Launch(
            SandboxConfig.defaults(), false, this.hole.executable(), this.project, this.project, this.hole.environment()
        ), "aaaaaa", new SessionServices(
            new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
            this.nextIds::poll, this.clock, this.background, PasteDirectory.disabled()
        ), recorder);
        session.setAgentTitle("✳ Fix EntityIndexTest");
        session.setAgentTitle("✳ Fix EntityIndexTest");
        assertEquals("✳ Fix EntityIndexTest", session.snapshot().agentTitle());
        assertEquals(1, recorder.changes.get());
        session.setAgentTitle(null);
        assertEquals("", session.snapshot().agentTitle());
        assertEquals(2, recorder.changes.get());
    }

    @Test
    void shutdownSendsSigintWithoutWaitingOrNotifying() {
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        final int changes = recorder.changes.get();

        final long begin = System.nanoTime();
        session.shutdown();
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).toMillis() < 500, "does not wait for the teardown");
        assertTrue(session.snapshot().closeRequested());
        waitUntil(() -> !session.process().isAlive(), "Hole exits after SIGINT");
        assertEquals(130, session.process().exitValue());
        assertEquals(changes, recorder.changes.get(), "no events after shutdown");
    }

    @Test
    void exitWaitRunsOnTheSessionExecutorAndNotOnTheCommonPool() {
        // regression: Process.onExit() of a pty4j process (no override) parked a ForkJoinPool common-pool thread in
        // waitFor() for the whole life of every sandbox
        final AtomicInteger running = new AtomicInteger();
        final List<Boolean> inForkJoinPool = new CopyOnWriteArrayList<>();
        final Executor tracking = runnable -> this.background.execute(() -> {
            inForkJoinPool.add(ForkJoinTask.inForkJoinPool());
            running.incrementAndGet();
            try {
                runnable.run();
            } finally {
                running.decrementAndGet();
            }
        });
        final int count = 4;
        final List<SandboxSession> sessions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            sessions.add(start(SandboxConfig.defaults(), false, "aaaaa" + i, new Recorder(), tracking));
        }
        waitUntil(() -> sessions.stream().allMatch(s -> s.snapshot().state() == SessionState.RUNNING), "all running");

        assertEquals(count, running.get(), "one exit wait per running sandbox, on the session executor");
        assertFalse(inForkJoinPool.contains(Boolean.TRUE), "no exit wait inside a ForkJoinPool");
        assertEquals(List.of(), commonPoolThreadsWaitingForAProcess(), "no common-pool thread waits for a sandbox");

        sessions.forEach(SandboxSession::requestClose);
        waitUntil(() -> sessions.stream().allMatch(s -> s.snapshot().state() == SessionState.CLOSED), "all closed");
        waitUntil(() -> running.get() == 0, "exit waits end with the processes");
    }

    @Test
    void launchDescriptionLeavesOutAgentArgsAndEnvironment() {
        final SandboxSession.Launch launch = new SandboxSession.Launch(
            SandboxConfig.defaults().withAgentArgs("--api-key s3cr3t"), false, this.hole.executable(), this.project,
            this.project, java.util.Map.of("TOKEN", "t0k3n")
        );
        assertFalse(launch.toString().contains("s3cr3t"));
        assertFalse(launch.toString().contains("t0k3n"));
        assertTrue(launch.describe().contains("agent claude"));
    }

    @Test
    void pasteDirectoryExistsWhenHoleStartsAndIsDeletedAfterTheExit() {
        final Path root = enableImagePaste();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");

        final Path directory = root.resolve("aaaaaa");
        assertEquals(directory, session.pasteDirectory());
        assertTrue(Files.isDirectory(directory));
        assertEquals(List.of("start claude . --library " + directory + ":/tmp/hole-idea-paste --instance-id aaaaaa"), startCalls());

        session.requestClose();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        waitUntil(() -> !Files.exists(directory), "paste directory deleted");
        assertNull(session.pasteDirectory());
    }

    @Test
    void imagePasteOffStartsWithoutPasteDirectory() {
        final Path root = enableImagePaste();
        final SandboxSession session = start(SandboxConfig.defaults().withImagePaste(false), false, "aaaaaa", new Recorder());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");

        assertNull(session.pasteDirectory());
        assertFalse(Files.exists(root.resolve("aaaaaa")));
        assertEquals(List.of("start claude . --instance-id aaaaaa"), startCalls());
    }

    @Test
    void duplicateIdRetryUsesANewPasteDirectory() {
        final Path root = enableImagePaste();
        this.hole.putInstance("aaaaaa", "running");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);

        waitUntil(() -> recorder.restarts.get() == 1 && session.snapshot().state() == SessionState.RUNNING, "retry runs");
        assertEquals(root.resolve("bbbbbb"), session.pasteDirectory());
        assertTrue(Files.isDirectory(root.resolve("bbbbbb")));
        assertFalse(Files.exists(root.resolve("aaaaaa")), "the rejected attempt's directory is gone");
    }

    @Test
    void restartWithImagePasteToggledChangesTheMount() {
        final Path root = enableImagePaste();
        this.nextIds.add("bbbbbb");
        this.nextIds.add("cccccc");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults().withImagePaste(false), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        assertNull(session.pasteDirectory());

        assertTrue(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())));
        waitUntil(() -> recorder.restarts.get() == 1 && session.snapshot().state() == SessionState.RUNNING, "restarted with image paste");
        assertEquals(root.resolve("bbbbbb"), session.pasteDirectory());

        assertTrue(session.restart(launch(SandboxConfig.defaults().withImagePaste(false), this.hole.environment())));
        waitUntil(() -> recorder.restarts.get() == 2 && session.snapshot().state() == SessionState.RUNNING, "restarted without image paste");
        assertNull(session.pasteDirectory());
        assertFalse(Files.exists(root.resolve("bbbbbb")), "the old process's directory is gone");
        assertEquals(List.of(
            "start claude . --instance-id aaaaaa",
            "start claude . --library " + root.resolve("bbbbbb") + ":/tmp/hole-idea-paste --instance-id bbbbbb",
            "start claude . --instance-id cccccc"
        ), startCalls());
    }

    @Test
    void restartOfARunningSessionStartsAgainInTheSameSessionWithANewInstanceId() {
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        session.setAgentTitle("✳ Old task");
        final com.pty4j.PtyProcess old = session.process();

        assertTrue(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())));
        final SandboxSession.Snapshot restarting = session.snapshot();
        assertEquals(SessionState.STOPPING, restarting.state());
        assertTrue(restarting.restartPending());
        assertFalse(restarting.closeRequested(), "a restart is not a close");

        waitUntil(() -> recorder.restarts.get() == 1, "process restarted");
        assertEquals(130, old.exitValue(), "the old Hole got SIGINT");
        assertEquals("bbbbbb", session.snapshot().instanceId());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        final SandboxSession.Snapshot running = session.snapshot();
        assertEquals("bbbbbb", running.info().instanceId());
        assertFalse(running.restartPending());
        assertNull(running.exitCode());
        assertNull(running.stoppingSince());
        assertEquals("", running.agentTitle(), "the old agent's title is gone");
        assertEquals(List.of("start claude . --instance-id aaaaaa", "start claude . --instance-id bbbbbb"), startCalls());
        assertFalse(recorder.states.contains(SessionState.EXITED), "no exit is shown: " + recorder.states);
        assertFalse(recorder.states.contains(SessionState.CLOSED), "the tab does not close: " + recorder.states);
    }

    @Test
    void restartWithDockerToggledPassesOrLeavesOutWithDocker() {
        this.nextIds.add("bbbbbb");
        this.nextIds.add("cccccc");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        assertFalse(session.snapshot().info().docker());

        assertTrue(session.restart(launch(SandboxConfig.defaults().withWithDocker(true), this.hole.environment())));
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING && "bbbbbb".equals(session.snapshot().instanceId()), "running with Docker");
        assertTrue(session.snapshot().info().docker());
        assertTrue(session.launch().config().withDocker(), "the launch is replaced");

        assertTrue(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())));
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING && "cccccc".equals(session.snapshot().instanceId()), "running without Docker");
        final List<String> starts = startCalls();
        assertEquals(3, starts.size(), starts.toString());
        assertFalse(starts.get(0).contains("--with-docker"), starts.toString());
        assertTrue(starts.get(1).contains("--with-docker") && starts.get(1).contains("--instance-id bbbbbb"), starts.toString());
        assertFalse(starts.get(2).contains("--with-docker"), starts.toString());
    }

    @Test
    void restartIsRefusedWhileStartingAndStopping() {
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "30");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().info() != null, "Hole lists the starting sandbox");

        assertFalse(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())), "STARTING");
        assertEquals(SessionState.STARTING, session.snapshot().state());
        assertFalse(session.snapshot().restartPending());
        assertTrue(session.process().isAlive(), "no SIGINT");

        session.requestClose();
        assertFalse(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())), "STOPPING");
        assertFalse(session.snapshot().restartPending());
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        assertFalse(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())), "CLOSED");
        assertEquals(0, recorder.restarts.get());
        assertEquals(1, startCalls().size(), startCalls().toString());
    }

    @Test
    void restartIsRefusedAfterTheExit() {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");

        assertFalse(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())));
        assertEquals(SessionState.EXITED, session.snapshot().state());
        assertEquals(1, startCalls().size(), startCalls().toString());
    }

    @Test
    void closeRequestedDuringAPendingRestartClosesTheTab() {
        // the hanging Hole keeps the restart pending until the close request has come
        this.hole.environment().put("FAKE_HOLE_HANG", "1");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        assertTrue(session.restart(launch(SandboxConfig.defaults(), withoutKey(this.hole.environment(), "FAKE_HOLE_HANG"))));

        session.requestClose();
        assertTrue(session.snapshot().closeRequested());
        assertFalse(session.snapshot().restartPending(), "the close wins");
        session.forceStop();
        waitUntil(() -> session.snapshot().state() == SessionState.CLOSED, "closed");
        assertEquals(0, recorder.restarts.get());
        assertEquals(1, startCalls().size(), startCalls().toString());
    }

    @Test
    void restartSendsSigintToTheWholeProcessGroup() {
        this.hole.environment().put("FAKE_HOLE_CHILD", "running");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        final Path childPid = this.hole.stateDirectory().resolve("child.pid");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING && Files.exists(childPid), "agent process runs");

        assertTrue(session.restart(launch(SandboxConfig.defaults(), withoutKey(this.hole.environment(), "FAKE_HOLE_CHILD"))));
        waitUntil(() -> recorder.restarts.get() == 1, "process restarted");
        assertTrue(Files.exists(this.hole.stateDirectory().resolve("child.int")), "the agent process got SIGINT");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
    }

    @Test
    void forceStopDuringARestartKillsTheOldProcessAndTheRestartGoesOn() {
        this.hole.environment().put("FAKE_HOLE_HANG", "1");
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        final com.pty4j.PtyProcess old = session.process();

        assertTrue(session.restart(launch(SandboxConfig.defaults(), withoutKey(this.hole.environment(), "FAKE_HOLE_HANG"))));
        this.clock.advance(SandboxSession.FORCE_STOP_AFTER);
        assertTrue(session.canForceStop(this.clock.instant()), "the old Hole ignores SIGINT");
        session.forceStop();
        assertFalse(session.snapshot().closeRequested(), "Force Stop does not cancel the restart");

        waitUntil(() -> recorder.restarts.get() == 1, "process restarted");
        assertEquals(137, old.exitValue());
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        assertEquals("bbbbbb", session.snapshot().instanceId());
        assertFalse(recorder.states.contains(SessionState.CLOSED), recorder.states.toString());
    }

    @Test
    void restartedProcessGetsItsOwnDuplicateIdRetryWithoutTheOutputWait() {
        this.nextIds.add("aaaaab");
        this.nextIds.add("bbbbbb");
        this.nextIds.add("cccccc");
        this.hole.putInstance("aaaaaa", "running");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        // the first process already used its retry
        waitUntil(() -> recorder.restarts.get() == 1 && session.snapshot().state() == SessionState.RUNNING, "retry runs");
        assertEquals("aaaaab", session.snapshot().instanceId());
        this.hole.putLeftovers("bbbbbb");
        final com.pty4j.PtyProcess old = session.process();

        assertTrue(session.restart(launch(SandboxConfig.defaults(), this.hole.environment())));
        waitUntil(() -> !old.isAlive(), "the old Hole exits after SIGINT");
        // measured from the exit: the teardown time of the old Hole does not count
        final long begin = System.nanoTime();
        waitUntil(() -> startCalls().stream().anyMatch(call -> call.endsWith("--instance-id bbbbbb")), () -> startCalls().toString());
        assertTrue(Duration.ofNanos(System.nanoTime() - begin).compareTo(SandboxSession.OUTPUT_DRAIN_TIMEOUT) < 0,
            "no output wait before the restart");

        waitUntil(() -> recorder.restarts.get() == 3, "leftovers of bbbbbb are retried");
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "new sandbox runs");
        assertEquals("cccccc", session.snapshot().instanceId());
    }

    @Test
    void restartWhoseNewProcessCannotBeSpawnedEndsExitedAndIsReported() throws IOException {
        // regression guard: the old retry path left the state to its caller; a restart must end visibly
        this.nextIds.add("bbbbbb");
        final Recorder recorder = new Recorder();
        final SandboxSession session = start(SandboxConfig.defaults(), false, "aaaaaa", recorder);
        waitUntil(() -> session.snapshot().state() == SessionState.RUNNING, "running");
        final Path broken = Files.createDirectories(this.temp.resolve("not-a-binary"));
        final SandboxSession.Launch next = new SandboxSession.Launch(
            SandboxConfig.defaults(), false, broken, this.project, this.project, this.hole.environment()
        );

        assertTrue(session.restart(next));
        waitUntil(() -> session.snapshot().state() == SessionState.EXITED, "exited");
        assertEquals(130, session.snapshot().exitCode(), "the old exit code is shown");
        assertFalse(session.snapshot().restartPending());
        assertEquals(1, recorder.restartFailures.size(), recorder.restartFailures.toString());
        assertEquals(0, recorder.restarts.get());
    }

    @NotNull
    private SandboxSession.Launch launch(@NotNull SandboxConfig config, @NotNull Map<String, String> environment) {
        return new SandboxSession.Launch(config, false, this.hole.executable(), this.project, this.project, environment);
    }

    @NotNull
    private static Map<String, String> withoutKey(@NotNull Map<String, String> environment, @NotNull String key) {
        final Map<String, String> copy = new HashMap<>(environment);
        copy.remove(key);
        return copy;
    }

    @NotNull
    private List<String> startCalls() {
        return this.hole.calls().stream().filter(call -> call.startsWith("start ")).toList();
    }

    @NotNull
    private SandboxSession start(@NotNull SandboxConfig config, boolean debug, @NotNull String id, @NotNull Recorder recorder) {
        return start(config, debug, id, recorder, this.background);
    }

    @NotNull
    private SandboxSession start(
        @NotNull SandboxConfig config, boolean debug, @NotNull String id, @NotNull Recorder recorder, @NotNull Executor background
    ) {
        final SessionServices services = new SessionServices(
            new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(this.poller),
            () -> this.nextIds.isEmpty() ? "zzzzzz" : this.nextIds.poll(), this.clock, background, this.pastes
        );
        final SandboxSession.Launch launch = new SandboxSession.Launch(
            config, debug, this.hole.executable(), this.project, this.project, this.hole.environment()
        );
        final SandboxSession session = new SandboxSession(launch, id, services, recorder);
        recorder.session = session;
        try {
            session.start();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        recorder.drain = TerminalDrain.start(session.createConnector());
        this.started.add(session);
        return session;
    }

    /**
     * Gives the sessions of the test real paste directories, and makes the fake `hole start` fail when one is missing.
     */
    @NotNull
    private Path enableImagePaste() {
        final Path root = this.temp.resolve("paste");
        this.pastes = new PasteDirectory(root, this.clock);
        this.hole.environment().put("FAKE_HOLE_REQUIRE_LIBRARIES", "1");
        return root;
    }

    @NotNull
    private static List<String> commonPoolThreadsWaitingForAProcess() {
        final List<String> waiting = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!entry.getKey().getName().startsWith("ForkJoinPool.commonPool")) {
                continue;
            }
            for (StackTraceElement frame : entry.getValue()) {
                if (frame.getMethodName().startsWith("waitFor") || frame.getClassName().startsWith("com.pty4j")) {
                    waiting.add(entry.getKey().getName() + " in " + frame);
                    break;
                }
            }
        }
        return waiting;
    }

    private static final class Recorder implements SandboxSession.Listener {
        final AtomicInteger changes = new AtomicInteger();
        final AtomicInteger restarts = new AtomicInteger();
        final List<SessionState> states = new CopyOnWriteArrayList<>();
        final List<String> restartFailures = new CopyOnWriteArrayList<>();
        volatile SandboxSession session;
        volatile TerminalDrain drain;

        @Override
        public void sessionChanged(@NotNull SandboxSession session) {
            this.changes.incrementAndGet();
            this.states.add(session.snapshot().state());
        }

        @Override
        public void restartFailed(@NotNull SandboxSession session, @NotNull String reason) {
            this.restartFailures.add(reason);
        }

        @Override
        public void processRestarted(@NotNull SandboxSession session) {
            // like the tab: connect a new terminal to the new process
            this.drain = TerminalDrain.start(session.createConnector());
            this.restarts.incrementAndGet();
        }
    }
}
