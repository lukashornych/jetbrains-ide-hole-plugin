package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.Disposable;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs the poller with short intervals against the fake `hole` script.
 */
class MetadataPollerTest {

    private static final Duration FAST = Duration.ofMillis(100);
    private static final Duration SLOW = Duration.ofMillis(1_500);

    @TempDir
    Path temp;

    private FakeHole hole;
    private ScheduledExecutorService executor;
    private MetadataPoller poller;

    @BeforeEach
    void setUp() {
        this.hole = FakeHole.install(this.temp);
        final HoleListClient client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.poller = new MetadataPoller(client::listAll, this.executor, true, FAST, SLOW);
    }

    @AfterEach
    void tearDown() {
        this.poller.dispose();
    }

    @Test
    void followsInstanceFromNotRegisteredToRunningAndSlowsDown() {
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);

        waitUntil(() -> !listener.updates.isEmpty(), "first poll");
        assertEquals(Optional.empty(), listener.updates.get(0), "not registered yet");
        assertEquals(FAST, this.poller.currentInterval(), "unseen instance polls fast");

        this.hole.putInstance("aaaaaa", "starting");
        waitUntil(() -> listener.lastStatus() == InstanceStatus.STARTING, "starting status");
        assertEquals(FAST, this.poller.currentInterval());

        this.hole.putInstance("aaaaaa", "running");
        waitUntil(() -> listener.lastStatus() == InstanceStatus.RUNNING, "running status");
        waitUntil(() -> this.poller.currentInterval().equals(SLOW), "slow interval");

        // in slow mode there are no polls for a while
        final int calls = this.hole.calls().size();
        sleep(SLOW.toMillis() / 2);
        assertTrue(this.hole.calls().size() <= calls + 1, "polling slowed down: " + this.hole.calls().size() + " vs " + calls);
    }

    @Test
    void oneListCallPerRoundForAllInstances() {
        this.hole.putInstance("aaaaaa", "starting");
        this.hole.putInstance("bbbbbb", "running");
        final RecordingListener a = new RecordingListener();
        final RecordingListener b = new RecordingListener();
        final RecordingListener c = new RecordingListener();
        this.poller.subscribe("aaaaaa", a);
        this.poller.subscribe("bbbbbb", b);
        this.poller.subscribe("cccccc", c);

        waitUntil(() -> a.updates.size() >= 3 && b.updates.size() >= 3 && c.updates.size() >= 3, "three rounds");
        assertEquals(InstanceStatus.STARTING, a.lastStatus());
        assertEquals(InstanceStatus.RUNNING, b.lastStatus());
        assertEquals(Optional.empty(), c.updates.get(c.updates.size() - 1));
        assertTrue(this.hole.calls().stream().allMatch("list --json --no-gc"::equals), "always the unfiltered list without GC: " + this.hole.calls());
        assertTrue(this.hole.calls().size() <= a.updates.size() + 1, "one call per round, not one per instance");
    }

    @Test
    void sessionGoneIsReportedAsNull() {
        this.hole.putInstance("aaaaaa", "running");
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> listener.lastStatus() == InstanceStatus.RUNNING, "running");

        // a new subscription polls right away, so the next round comes without waiting for the slow interval
        this.hole.removeInstance("aaaaaa");
        this.poller.subscribe("zzzzzz", new RecordingListener());
        waitUntil(() -> listener.updates.get(listener.updates.size() - 1).isEmpty(), "instance gone");
    }

    @Test
    void failuresAreReportedAndPollingContinues() {
        this.hole.mode("fail");
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> listener.failures.size() >= 2, "two failed rounds");
        assertTrue(listener.updates.isEmpty());
        assertTrue(listener.failures.get(0).getMessage().contains("exit code: 1"), listener.failures.get(0).getMessage());

        this.hole.mode(null);
        this.hole.putInstance("aaaaaa", "starting");
        waitUntil(() -> listener.lastStatus() == InstanceStatus.STARTING, "recovered");
    }

    @Test
    void stopsWhenLastSubscriptionEnds() {
        final RecordingListener listener = new RecordingListener();
        final Disposable subscription = this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> listener.updates.size() >= 2, "polling");

        subscription.dispose();
        waitUntil(() -> !this.poller.isActive(), "loop stopped");
        final int calls = this.hole.calls().size();
        final int updates = listener.updates.size();
        sleep(FAST.toMillis() * 4);
        assertEquals(calls, this.hole.calls().size());
        assertEquals(updates, listener.updates.size(), "a disposed subscription gets no further updates");
    }

    @Test
    void disposeCancelsLoop() {
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> !listener.updates.isEmpty(), "polling");

        this.poller.dispose();
        assertTrue(this.executor.isShutdown());
        assertFalse(this.poller.isActive());
        assertThrows(IllegalStateException.class, () -> this.poller.subscribe("bbbbbb", new RecordingListener()));
        try {
            assertTrue(this.executor.awaitTermination(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            fail(e);
        }
    }

    @Test
    void concurrentSubscriptionsKeepOneSequentialLoop() throws Exception {
        // regression: a subscribe() racing with a dispatched poll must not fork a second poll chain
        final java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger maxRunning = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        final List<InstanceInfo> none = List.of();
        final ScheduledExecutorService pool = Executors.newScheduledThreadPool(4);
        final MetadataPoller racing = new MetadataPoller(() -> {
            maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
            calls.incrementAndGet();
            sleep(5);
            running.decrementAndGet();
            return none;
        }, pool, true, Duration.ofMillis(200), Duration.ofSeconds(5));
        try {
            final List<Thread> threads = new java.util.ArrayList<>();
            for (int t = 0; t < 4; t++) {
                final int thread = t;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (int i = 0; i < 50; i++) {
                        racing.subscribe("id" + thread + "x" + i, info -> { });
                        sleep(1);
                    }
                }));
            }
            for (Thread thread : threads) {
                thread.join();
            }
            sleep(300);
            final int before = calls.get();
            sleep(1_000);
            // one chain at the fast interval (200 ms) gives about 5 calls per second; two chains would give 10
            assertTrue(calls.get() - before <= 7, "calls in one second: " + (calls.get() - before));
            assertEquals(1, maxRunning.get(), "polls never overlap");
        } finally {
            racing.dispose();
        }
    }

    @Test
    void noListenerIsCalledAfterDisposeEvenWhenPollWasInFlight() throws Exception {
        // regression: a poll that was waiting for Hole while dispose() ran must not deliver its result
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch returned = new java.util.concurrent.CountDownLatch(1);
        final InstanceInfo info = HoleListParser.parse("[{\"instanceId\": \"aaaaaa\", \"status\": \"running\"}]").get(0);
        final ScheduledExecutorService own = Executors.newSingleThreadScheduledExecutor();
        // the executor is not owned, so dispose() does not interrupt the blocked source: the success path is exercised
        final MetadataPoller blocking = new MetadataPoller(() -> {
            entered.countDown();
            release.await();
            returned.countDown();
            return List.of(info);
        }, own, false, FAST, SLOW);
        try {
            final RecordingListener listener = new RecordingListener();
            blocking.subscribe("aaaaaa", listener);
            assertTrue(entered.await(10, TimeUnit.SECONDS), "poll started");

            blocking.dispose();
            release.countDown();
            assertTrue(returned.await(10, TimeUnit.SECONDS));
            sleep(200);
            assertTrue(listener.updates.isEmpty(), "no update after dispose: " + listener.updates);
            assertTrue(listener.failures.isEmpty());
            assertFalse(blocking.isActive());
        } finally {
            own.shutdownNow();
        }
    }

    @Test
    void refreshNowPollsWithoutWaitingForTheSlowInterval() {
        this.hole.putInstance("aaaaaa", "running");
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> this.poller.currentInterval().equals(SLOW) && listener.updates.size() >= 1, "slow interval");
        sleep(100);

        final int updates = listener.updates.size();
        this.hole.putInstance("aaaaaa", "stopping");
        this.poller.refreshNow();
        final long start = System.nanoTime();
        waitUntil(() -> listener.lastStatus() == InstanceStatus.STOPPING, "stopping after refresh");
        assertTrue(listener.updates.size() > updates);
        assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(SLOW) < 0, "faster than the slow interval");
    }

    @Test
    void failingListenerDoesNotBreakOthers() {
        this.hole.putInstance("aaaaaa", "starting");
        this.poller.subscribe("aaaaaa", info -> {
            throw new IllegalStateException("listener bug");
        });
        final RecordingListener listener = new RecordingListener();
        this.poller.subscribe("aaaaaa", listener);
        waitUntil(() -> listener.updates.size() >= 2, "updates keep coming");
    }

    private static void waitUntil(@NotNull BooleanSupplier condition, @NotNull String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        fail("Timed out waiting for: " + what);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }

    private static final class RecordingListener implements MetadataPoller.Listener {
        final List<Optional<InstanceInfo>> updates = new CopyOnWriteArrayList<>();
        final List<Exception> failures = new CopyOnWriteArrayList<>();

        @Override
        public void instanceUpdated(@Nullable InstanceInfo info) {
            this.updates.add(Optional.ofNullable(info));
        }

        @Override
        public void pollFailed(@NotNull Exception failure) {
            this.failures.add(failure);
        }

        InstanceStatus lastStatus() {
            if (this.updates.isEmpty()) {
                return null;
            }
            return this.updates.get(this.updates.size() - 1).map(InstanceInfo::status).orElse(null);
        }
    }
}
