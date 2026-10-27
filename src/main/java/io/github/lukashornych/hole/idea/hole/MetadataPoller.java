package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import io.github.lukashornych.hole.idea.process.CliException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Polls sandbox metadata for all sessions of all projects with <b>one</b> loop: each round runs an unfiltered
 * `hole list --json --no-gc` and fans the result out by instance ID. One loop instead of one per session, because
 * every `hole list` asks the container engine. `--no-gc` keeps the polls from running Hole's cleanup of dead
 * sandboxes every second; Hole still cleans up on every `hole start`.
 *
 * <p>The interval is {@link #FAST_INTERVAL} while any subscribed instance has not been seen yet or reports
 * {@link InstanceStatus#STARTING}, and {@link #SLOW_INTERVAL} otherwise. The loop runs only while there are
 * subscribers and stops on {@link #dispose()}. A new subscription triggers a poll right away.</p>
 *
 * <p>Listeners are called on a background thread; UI code must switch to the EDT itself.</p>
 */
@Service(Service.Level.APP)
public final class MetadataPoller implements Disposable {

    /** Interval while a sandbox is starting. */
    public static final Duration FAST_INTERVAL = Duration.ofSeconds(1);
    /** Interval once all sandboxes run; uptime is computed locally, so this only refreshes status and metadata. */
    public static final Duration SLOW_INTERVAL = Duration.ofSeconds(10);

    private static final Logger LOG = Logger.getInstance(MetadataPoller.class);

    private final InstanceSource source;
    private final ScheduledExecutorService executor;
    private final boolean ownsExecutor;
    private final Duration fastInterval;
    private final Duration slowInterval;

    private final Object lock = new Object();
    /** Subscriptions by instance ID. Guarded by {@link #lock}. */
    private final Map<String, List<Subscription>> subscriptions = new LinkedHashMap<>();
    /** Last status seen per subscribed instance; absent = not seen yet. Guarded by {@link #lock}. */
    private final Map<String, InstanceStatus> lastStatus = new HashMap<>();
    /** Next scheduled poll, {@code null} when none. Guarded by {@link #lock}. */
    private ScheduledFuture<?> scheduled;
    /** A poll is running right now. Guarded by {@link #lock}. */
    private boolean polling;
    /** Guarded by {@link #lock}. */
    private boolean disposed;
    /** Guarded by {@link #lock}. */
    private boolean lastPollFailed;
    /** {@link #refreshNow()} was called during a poll; the next one follows without delay. Guarded by {@link #lock}. */
    private boolean refreshRequested;
    /**
     * Incremented on every scheduling; a poll whose generation is outdated does nothing. Prevents a second poll chain
     * when a cancelled future had already been handed to the executor. Guarded by {@link #lock}.
     */
    private long generation;

    /**
     * Platform constructor: lists through the located `hole` executable on a dedicated pooled executor.
     */
    public MetadataPoller() {
        this(
            () -> {
                final HoleExecutable holeExecutable = HoleExecutable.getInstance();
                final String executable = holeExecutable.locate()
                    .orElseThrow(() -> new CliException("Hole executable not found", HoleCommandBuilder.listJson("hole", null), null))
                    .toString();
                return new HoleListClient(executable, holeExecutable.runner()).listAll();
            },
            AppExecutorUtil.createBoundedScheduledExecutorService("Hole metadata poller", 1),
            true,
            FAST_INTERVAL,
            SLOW_INTERVAL
        );
    }

    /**
     * @param source       lists all running instances (blocking)
     * @param executor     executor the polls run on; it must allow blocking tasks
     * @param ownsExecutor shut the executor down on {@link #dispose()}
     * @param fastInterval interval while an instance is starting
     * @param slowInterval interval otherwise
     */
    @NonInjectable
    public MetadataPoller(
        @NotNull InstanceSource source,
        @NotNull ScheduledExecutorService executor,
        boolean ownsExecutor,
        @NotNull Duration fastInterval,
        @NotNull Duration slowInterval
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownsExecutor = ownsExecutor;
        this.fastInterval = Objects.requireNonNull(fastInterval, "fastInterval");
        this.slowInterval = Objects.requireNonNull(slowInterval, "slowInterval");
    }

    /**
     * Returns the application-wide instance.
     */
    @NotNull
    public static MetadataPoller getInstance() {
        return ApplicationManager.getApplication().getService(MetadataPoller.class);
    }

    /**
     * Subscribes to the metadata of one instance. When no poll is running, the next one is scheduled immediately;
     * a poll that is running already schedules its successor at the fast interval, because the new instance has not
     * been seen yet.
     *
     * @param instanceId instance to follow
     * @param listener   called on the poller thread after every poll round in which the subscription is active
     * @return a handle that ends the subscription when disposed; the loop stops when the last subscription ends.
     * Dispose it when the sandbox process exits, because an instance that is never listed keeps the loop at the
     * fast interval.
     */
    @NotNull
    public Disposable subscribe(@NotNull String instanceId, @NotNull Listener listener) {
        final Subscription subscription = new Subscription(instanceId, listener);
        synchronized (this.lock) {
            if (this.disposed) {
                throw new IllegalStateException("MetadataPoller is disposed.");
            }
            this.subscriptions.computeIfAbsent(instanceId, id -> new CopyOnWriteArrayList<>()).add(subscription);
            if (!this.polling) {
                scheduleLocked(Duration.ZERO);
            }
            // a poll in progress reschedules itself with the fast interval, because this instance is not seen yet
        }
        return subscription;
    }

    /**
     * Polls as soon as possible: right away when no poll is running, otherwise right after the running one. Used when
     * the plugin knows the state changed (e.g. it has just sent SIGINT), so the UI does not wait for the slow
     * interval. Does nothing without subscriptions or after {@link #dispose()}.
     */
    public void refreshNow() {
        synchronized (this.lock) {
            if (this.disposed || this.subscriptions.isEmpty()) {
                return;
            }
            if (this.polling) {
                this.refreshRequested = true;
            } else {
                scheduleLocked(Duration.ZERO);
            }
        }
    }

    /**
     * Returns the interval the next poll would be scheduled with.
     */
    @NotNull
    public Duration currentInterval() {
        synchronized (this.lock) {
            return intervalLocked();
        }
    }

    /**
     * Returns {@code true} while a poll is scheduled or running.
     */
    public boolean isActive() {
        synchronized (this.lock) {
            return this.polling || this.scheduled != null;
        }
    }

    @Override
    public void dispose() {
        synchronized (this.lock) {
            this.disposed = true;
            // a poll in flight holds a snapshot of the subscriptions; inactive ones are skipped when it delivers
            for (List<Subscription> list : this.subscriptions.values()) {
                for (Subscription subscription : list) {
                    subscription.active = false;
                }
            }
            this.subscriptions.clear();
            this.lastStatus.clear();
            if (this.scheduled != null) {
                this.scheduled.cancel(false);
                this.scheduled = null;
            }
        }
        if (this.ownsExecutor) {
            this.executor.shutdownNow();
        }
    }

    private void poll(long scheduledGeneration) {
        final Map<String, List<Subscription>> targets;
        synchronized (this.lock) {
            if (scheduledGeneration != this.generation || this.polling) {
                // superseded by a later scheduling (its cancel came too late) or another poll is running
                return;
            }
            this.scheduled = null;
            if (this.disposed || this.subscriptions.isEmpty()) {
                return;
            }
            this.polling = true;
            targets = snapshotLocked();
        }

        List<InstanceInfo> instances = null;
        Exception failure = null;
        try {
            instances = this.source.listAll();
        } catch (Exception e) {
            failure = e;
        }

        if (instances != null) {
            final Map<String, InstanceInfo> byId = new HashMap<>();
            for (InstanceInfo instance : instances) {
                byId.put(instance.instanceId(), instance);
            }
            synchronized (this.lock) {
                if (this.disposed) {
                    // dispose() came while Hole was answering; nobody may be called any more
                    this.polling = false;
                    return;
                }
                this.lastPollFailed = false;
                for (String id : targets.keySet()) {
                    final InstanceInfo info = byId.get(id);
                    if (info != null && this.subscriptions.containsKey(id)) {
                        this.lastStatus.put(id, info.status());
                    }
                }
            }
            for (Map.Entry<String, List<Subscription>> entry : targets.entrySet()) {
                final InstanceInfo info = byId.get(entry.getKey());
                for (Subscription subscription : entry.getValue()) {
                    subscription.deliver(info);
                }
            }
        } else {
            synchronized (this.lock) {
                if (this.disposed) {
                    // dispose() interrupted the running poll; nothing to report
                    this.polling = false;
                    return;
                }
            }
            reportFailure(failure);
            for (List<Subscription> list : targets.values()) {
                for (Subscription subscription : list) {
                    subscription.fail(failure);
                }
            }
        }

        synchronized (this.lock) {
            this.polling = false;
            final boolean refresh = this.refreshRequested;
            this.refreshRequested = false;
            if (!this.disposed && !this.subscriptions.isEmpty() && this.scheduled == null) {
                scheduleLocked(refresh ? Duration.ZERO : intervalLocked());
            }
        }
    }

    private void reportFailure(@NotNull Exception failure) {
        final boolean first;
        synchronized (this.lock) {
            first = !this.lastPollFailed;
            this.lastPollFailed = true;
        }
        // the message names the command and exit code; the output itself is not logged
        if (first) {
            LOG.warn("Polling sandbox metadata failed: " + failure.getMessage());
        } else {
            LOG.debug("Polling sandbox metadata failed again: " + failure.getMessage());
        }
    }

    @NotNull
    private Map<String, List<Subscription>> snapshotLocked() {
        final Map<String, List<Subscription>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<Subscription>> entry : this.subscriptions.entrySet()) {
            copy.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return copy;
    }

    @NotNull
    private Duration intervalLocked() {
        for (String id : this.subscriptions.keySet()) {
            final InstanceStatus status = this.lastStatus.get(id);
            if (status == null || status == InstanceStatus.STARTING) {
                return this.fastInterval;
            }
        }
        return this.slowInterval;
    }

    private void scheduleLocked(@NotNull Duration delay) {
        if (this.scheduled != null) {
            this.scheduled.cancel(false);
        }
        final long scheduledGeneration = ++this.generation;
        this.scheduled = this.executor.schedule(() -> poll(scheduledGeneration), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void unsubscribe(@NotNull Subscription subscription) {
        synchronized (this.lock) {
            final List<Subscription> list = this.subscriptions.get(subscription.instanceId);
            if (list == null) {
                return;
            }
            list.remove(subscription);
            if (list.isEmpty()) {
                this.subscriptions.remove(subscription.instanceId);
                this.lastStatus.remove(subscription.instanceId);
            }
            if (this.subscriptions.isEmpty() && this.scheduled != null) {
                this.scheduled.cancel(false);
                this.scheduled = null;
            }
        }
    }

    /**
     * Lists all running instances; implemented by {@link HoleListClient#listAll()}.
     */
    @FunctionalInterface
    public interface InstanceSource {
        @NotNull
        List<InstanceInfo> listAll() throws Exception;
    }

    /**
     * Receives the metadata of one instance after every poll.
     */
    public interface Listener {

        /**
         * @param info the instance's metadata, or {@code null} when Hole does not list it (not registered yet, or
         *             gone)
         */
        void instanceUpdated(@Nullable InstanceInfo info);

        /**
         * The poll failed (Hole error, timeout, unparsable output). The loop keeps running.
         */
        default void pollFailed(@NotNull Exception failure) {
        }
    }

    private final class Subscription implements Disposable {

        private final String instanceId;
        private final Listener listener;
        private volatile boolean active = true;

        private Subscription(@NotNull String instanceId, @NotNull Listener listener) {
            this.instanceId = instanceId;
            this.listener = listener;
        }

        private void deliver(@Nullable InstanceInfo info) {
            if (!this.active) {
                return;
            }
            try {
                this.listener.instanceUpdated(info);
            } catch (RuntimeException e) {
                LOG.warn("Metadata listener of instance " + this.instanceId + " failed", e);
            }
        }

        private void fail(@NotNull Exception failure) {
            if (!this.active) {
                return;
            }
            try {
                this.listener.pollFailed(failure);
            } catch (RuntimeException e) {
                LOG.warn("Metadata listener of instance " + this.instanceId + " failed", e);
            }
        }

        @Override
        public void dispose() {
            this.active = false;
            unsubscribe(this);
        }
    }
}
