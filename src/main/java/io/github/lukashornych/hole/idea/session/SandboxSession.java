package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.pty4j.PtyProcess;
import io.github.lukashornych.hole.idea.hole.HoleCommandBuilder;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One sandbox: the `hole start` process in its pty, its lifecycle state, the metadata Hole reports for it and the
 * title the agent sets. Free of Swing, so the state machine is tested against the fake `hole` script; the tab
 * ({@code SandboxTabPanel}) connects a terminal widget through {@link #createConnector()}.
 *
 * <p>State machine (see {@link SessionState}): Hole's metadata moves the session from STARTING to RUNNING and to
 * STOPPING; {@link #requestClose()} sends SIGINT to the process group of `hole start` (see {@link ProcessSignaller}) and
 * moves it to STOPPING. Whether the tab closes when the process
 * exits depends only on {@link Snapshot#closeRequested()} and the exit code, never on Hole's `stopping` status, which
 * can be missed between two polls:</p>
 * <ul>
 *     <li>stop requested by the plugin (tab close, Force Stop, IDE exit) → {@link SessionState#CLOSED}, whatever the
 *     exit code (130 after SIGINT, 137 after SIGKILL),</li>
 *     <li>exit code 0 (the sandbox ended from within, e.g. `/exit`) → {@link SessionState#CLOSED},</li>
 *     <li>otherwise → {@link SessionState#EXITED}: the tab stays with "Exited with code N".</li>
 * </ul>
 *
 * <p>When Hole rejects the chosen instance ID (in use, or leftovers of an abandoned sandbox), the session starts once
 * more with a new ID in the same tab.</p>
 *
 * <p>Thread-safe. Listeners are called on the thread that caused the change (process reaper, poller, terminal
 * emulator or caller thread) and must switch to the EDT themselves.</p>
 */
public final class SandboxSession {

    /** How long Stopping lasts before Force Stop is offered. */
    public static final Duration FORCE_STOP_AFTER = Duration.ofSeconds(30);
    /** How long to wait for the rest of the output of a failed process before looking for the instance ID errors. */
    static final Duration OUTPUT_DRAIN_TIMEOUT = Duration.ofSeconds(3);

    private static final Logger LOG = Logger.getInstance(SandboxSession.class);
    private static final int EARLY_OUTPUT_LIMIT = 16 * 1024;

    private final Launch launch;
    private final SessionServices services;
    private final Listener listener;

    /** Current process; {@code null} before {@link #start()}. Guarded by {@code this}. */
    private Attempt attempt;
    /** Guarded by {@code this}. */
    private String instanceId;
    /** Guarded by {@code this}. */
    private SessionState state = SessionState.STARTING;
    /** Latest metadata of the current instance. Guarded by {@code this}. */
    private InstanceInfo info;
    /** Guarded by {@code this}. */
    private Integer exitCode;
    /** Guarded by {@code this}. */
    private Instant stoppingSince;
    /** Guarded by {@code this}. */
    private String agentTitle = "";
    /** The plugin stopped the sandbox; the tab closes whatever the exit code. Guarded by {@code this}. */
    private boolean closeRequested;
    /** The duplicate-ID retry was used. Guarded by {@code this}. */
    private boolean retried;
    /** No listener calls any more. Guarded by {@code this}. */
    private boolean disposed;

    /**
     * @param launch     what to start
     * @param instanceId the instance ID for the first attempt
     * @param services   process, signal and metadata services
     * @param listener   notified about every change
     */
    public SandboxSession(
        @NotNull Launch launch,
        @NotNull String instanceId,
        @NotNull SessionServices services,
        @NotNull Listener listener
    ) {
        this.launch = Objects.requireNonNull(launch, "launch");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.services = Objects.requireNonNull(services, "services");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Returns what this session started.
     */
    @NotNull
    public Launch launch() {
        return this.launch;
    }

    /**
     * Spawns the `hole start` process and subscribes to its metadata. Blocking; call off the EDT.
     *
     * @throws IOException when the process cannot be spawned; the session is unusable then
     */
    public void start() throws IOException {
        final String id;
        synchronized (this) {
            if (this.attempt != null) {
                throw new IllegalStateException("Session " + this.instanceId + " is already started.");
            }
            id = this.instanceId;
        }
        final Attempt first = spawn(id);
        synchronized (this) {
            this.attempt = first;
        }
        watch(first);
    }

    /**
     * Returns a consistent copy of the session's state for display.
     */
    @NotNull
    public synchronized Snapshot snapshot() {
        return new Snapshot(
            this.state, this.instanceId, this.info, this.exitCode, this.stoppingSince, this.agentTitle,
            this.closeRequested
        );
    }

    /**
     * Returns the current `hole` process, {@code null} before {@link #start()}.
     */
    @Nullable
    public synchronized PtyProcess process() {
        return this.attempt == null ? null : this.attempt.process;
    }

    /**
     * Creates the connector for a terminal widget that shows the current process. Call again after
     * {@link Listener#processRestarted(SandboxSession)}.
     */
    @NotNull
    public synchronized HoleTtyConnector createConnector() {
        if (this.attempt == null) {
            throw new IllegalStateException("Session " + this.instanceId + " is not started.");
        }
        this.attempt.connected = true;
        return new HoleTtyConnector(this.attempt.process, this.attempt);
    }

    /**
     * Records the terminal title the agent set (OSC 0/2).
     */
    public void setAgentTitle(@Nullable String title) {
        final String value = title == null ? "" : title;
        synchronized (this) {
            if (value.equals(this.agentTitle)) {
                return;
            }
            this.agentTitle = value;
        }
        fireChanged();
    }

    /**
     * The user closes the tab (or presses Close on an exited one). A running sandbox gets SIGINT (to its process group)
     * and switches to STOPPING; its tab closes when the process exits. An exited one is CLOSED at once.
     */
    public void requestClose() {
        final Attempt current;
        synchronized (this) {
            if (this.state == SessionState.CLOSED) {
                return;
            }
            if (this.state == SessionState.EXITED) {
                this.state = SessionState.CLOSED;
                current = null;
            } else {
                this.closeRequested = true;
                enterStoppingLocked();
                current = this.attempt;
            }
        }
        if (current != null) {
            signal(current, ProcessSignaller.SIGINT, "SIGINT");
            this.services.metadata().refreshNow();
        }
        fireChanged();
    }

    /**
     * Force Stop: SIGKILL to the process group of `hole start`. The tab closes when it exits; Hole's watchdog, which
     * runs in its own session and is therefore not killed, keeps tearing the sandbox down in the background.
     */
    public void forceStop() {
        final Attempt current;
        synchronized (this) {
            if (!this.state.isAlive()) {
                return;
            }
            this.closeRequested = true;
            enterStoppingLocked();
            current = this.attempt;
        }
        if (current != null) {
            signal(current, ProcessSignaller.SIGKILL, "SIGKILL");
        }
        fireChanged();
    }

    /**
     * Returns {@code true} when Force Stop should be offered: the sandbox has been stopping for at least
     * {@link #FORCE_STOP_AFTER}.
     */
    public boolean canForceStop(@NotNull Instant now) {
        final Snapshot snapshot = snapshot();
        return snapshot.state() == SessionState.STOPPING
            && snapshot.stoppingSince() != null
            && !now.isBefore(snapshot.stoppingSince().plus(FORCE_STOP_AFTER));
    }

    /**
     * Project or IDE shutdown: marks the stop as requested, sends SIGINT to the process group and does not wait. No listener is called
     * afterwards.
     */
    public void shutdown() {
        final Attempt current;
        synchronized (this) {
            this.disposed = true;
            current = this.attempt;
            if (this.state.isAlive()) {
                this.closeRequested = true;
                enterStoppingLocked();
            }
        }
        if (current != null) {
            current.unsubscribe();
            if (current.process.isAlive()) {
                signal(current, ProcessSignaller.SIGINT, "SIGINT");
            }
        }
    }

    /**
     * The tab is gone: ends the metadata subscription. A process that still runs (it should not) gets SIGINT.
     */
    public void dispose() {
        shutdown();
    }

    private void enterStoppingLocked() {
        if (this.state != SessionState.STOPPING) {
            this.state = SessionState.STOPPING;
        }
        if (this.stoppingSince == null) {
            this.stoppingSince = this.services.clock().instant();
        }
    }

    @NotNull
    private Attempt spawn(@NotNull String id) throws IOException {
        final String executable = this.launch.executable().toString();
        final List<String> command = this.launch.debug()
            ? HoleCommandBuilder.debug(executable, this.launch.config(), id)
            : HoleCommandBuilder.start(executable, this.launch.config(), id);
        final PtyProcess process = this.services.runner().spawn(command, this.launch.workingDirectory(), this.launch.environment());
        return new Attempt(id, process);
    }

    private void watch(@NotNull Attempt current) {
        current.subscription = this.services.metadata().subscribe(current.instanceId, new MetadataPoller.Listener() {
            @Override
            public void instanceUpdated(@Nullable InstanceInfo update) {
                metadataReceived(current, update);
            }
        });
        // not Process.onExit(): pty4j does not override it, and the JDK default parks a ForkJoinPool common-pool
        // thread in waitFor() for the whole life of the sandbox
        this.services.background().execute(() -> {
            if (awaitExit(current.process)) {
                processExited(current);
            }
        });
    }

    /**
     * Blocks the calling background thread until the process has exited.
     *
     * @return {@code false} when the thread was interrupted first (executor shutdown); the exit is not handled then
     */
    private static boolean awaitExit(@NotNull PtyProcess process) {
        try {
            process.waitFor();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void metadataReceived(@NotNull Attempt from, @Nullable InstanceInfo update) {
        if (update == null) {
            // not registered yet, or already gone while the process exits
            return;
        }
        synchronized (this) {
            if (from != this.attempt || !this.state.isAlive()) {
                return;
            }
            this.info = update;
            switch (update.status()) {
                case RUNNING -> {
                    if (this.state == SessionState.STARTING) {
                        this.state = SessionState.RUNNING;
                    }
                }
                case STOPPING -> enterStoppingLocked();
                default -> {
                    // STARTING keeps the state; UNKNOWN (a newer Hole) changes nothing
                }
            }
        }
        fireChanged();
    }

    private void processExited(@NotNull Attempt exited) {
        // the PID is reaped and may be reused: no signal may go to it any more
        exited.exited = true;
        final int code = exitValue(exited.process);
        final boolean mayRetry;
        synchronized (this) {
            if (exited != this.attempt) {
                return;
            }
            mayRetry = !this.closeRequested && code != 0 && !this.retried && !this.disposed;
        }
        exited.unsubscribe();

        if (mayRetry && exited.connected && exited.reportsDuplicateId()) {
            final String newId = this.services.instanceIds().get();
            synchronized (this) {
                this.retried = true;
            }
            LOG.info("Hole rejected instance ID " + exited.instanceId + " (in use or leftovers); starting sandbox again as " + newId);
            if (restart(newId)) {
                return;
            }
        }

        synchronized (this) {
            this.exitCode = code;
            this.state = this.closeRequested || code == 0 ? SessionState.CLOSED : SessionState.EXITED;
        }
        if (code != 0) {
            LOG.info("Sandbox " + exited.instanceId + " (" + this.launch.describe() + ") exited with code " + code
                + (this.snapshot().closeRequested() ? " after a stop request" : ""));
        }
        fireChanged();
    }

    /**
     * Replaces the exited process by a new one with another instance ID.
     *
     * @return {@code false} when the session is closing or the new process could not be spawned
     */
    private boolean restart(@NotNull String newId) {
        synchronized (this) {
            if (this.closeRequested || this.disposed) {
                return false;
            }
        }
        final Attempt next;
        try {
            next = spawn(newId);
        } catch (IOException e) {
            LOG.warn("Could not start sandbox again (" + this.launch.describe() + ", instance " + newId + "): " + e.getMessage());
            return false;
        }
        final boolean closing;
        synchronized (this) {
            this.attempt = next;
            this.instanceId = newId;
            this.info = null;
            this.state = SessionState.STARTING;
            closing = this.disposed || this.closeRequested;
        }
        watch(next);
        if (closing) {
            // shutdown() or a close request came while the new process was spawned and signalled the old one
            signal(next, ProcessSignaller.SIGINT, "SIGINT");
            return true;
        }
        this.listener.processRestarted(this);
        fireChanged();
        return true;
    }

    private void signal(@NotNull Attempt target, int signal, @NotNull String name) {
        if (target.exited || !target.process.isAlive()) {
            return;
        }
        final long pid = target.process.pid();
        if (!this.services.signaller().send(pid, signal)) {
            LOG.warn("Could not send " + name + " to Hole process " + pid + " of instance " + target.instanceId);
        }
    }

    private void fireChanged() {
        synchronized (this) {
            if (this.disposed) {
                return;
            }
        }
        this.listener.sessionChanged(this);
    }

    /**
     * Returns {@code true} when Hole's output rejects the instance ID: it is in use by a running sandbox
     * (`instance ID '<id>' is already in use by <path>`), or an abandoned sandbox with the same instance name left
     * leftovers (`instance ID '<id>' has leftovers of an abandoned sandbox; …`). Both are solved by another ID. Hole
     * prints them as `[ERROR] …` lines, coloured in a terminal; the colour codes wrap the whole line, so a plain
     * substring search is enough.
     */
    static boolean reportsInstanceIdConflict(@NotNull CharSequence output, @NotNull String instanceId) {
        final String text = output.toString();
        final String prefix = "instance ID '" + instanceId + "' ";
        return text.contains(prefix + "is already in use") || text.contains(prefix + "has leftovers of an abandoned sandbox");
    }

    private static int exitValue(@NotNull PtyProcess process) {
        // called after awaitExit(): the process has exited
        return process.exitValue();
    }

    /**
     * What a session starts.
     *
     * @param config           form values
     * @param debug            start a debug shell (`-d`) instead of the agent
     * @param executable       absolute path of `hole`
     * @param workingDirectory the IDE project root
     * @param projectDirectory {@code config.projectPath()} resolved against the project root (for titles)
     * @param environment      environment of the user's shell
     */
    public record Launch(
        @NotNull SandboxConfig config,
        boolean debug,
        @NotNull Path executable,
        @NotNull Path workingDirectory,
        @NotNull Path projectDirectory,
        @NotNull Map<String, String> environment
    ) {

        public Launch {
            environment = Map.copyOf(environment);
        }

        /**
         * Returns a description for logs: agent, profile, project path and mode. Never the command line, whose agent
         * arguments may carry secrets, nor the environment.
         */
        @NotNull
        public String describe() {
            return "agent " + this.config.agent()
                + (this.config.profile().isBlank() ? "" : ", profile " + this.config.profile())
                + ", project " + this.projectDirectory
                + (this.debug ? ", debug shell" : "");
        }

        @Override
        public String toString() {
            // the environment and agent arguments stay out of logs and exception messages
            return "Launch[" + describe() + "]";
        }
    }

    /**
     * Consistent view of a session.
     *
     * @param state          lifecycle state
     * @param instanceId     current instance ID
     * @param info           latest metadata, {@code null} until Hole lists the instance
     * @param exitCode       exit code once the process ended
     * @param stoppingSince  when STOPPING began
     * @param agentTitle     terminal title set by the agent, empty when none
     * @param closeRequested the plugin stopped the sandbox
     */
    public record Snapshot(
        @NotNull SessionState state,
        @NotNull String instanceId,
        @Nullable InstanceInfo info,
        @Nullable Integer exitCode,
        @Nullable Instant stoppingSince,
        @NotNull String agentTitle,
        boolean closeRequested
    ) {
    }

    /**
     * Notified about changes; called on background threads.
     */
    public interface Listener {

        /**
         * State, metadata or title changed.
         */
        void sessionChanged(@NotNull SandboxSession session);

        /**
         * The process was replaced (retry with a new instance ID); terminals must reconnect.
         */
        void processRestarted(@NotNull SandboxSession session);
    }

    /**
     * One `hole start` process and the output seen from it.
     */
    private final class Attempt implements HoleTtyConnector.OutputTap {

        private final String instanceId;
        private final PtyProcess process;
        private final StringBuilder earlyOutput = new StringBuilder();
        private final CompletableFuture<Void> outputClosed = new CompletableFuture<>();
        private volatile Disposable subscription;
        /** The process has exited (the exit wait returned). */
        private volatile boolean exited;
        /**
         * A terminal reads the output. Without one the duplicate-ID error cannot be seen, and nothing else may read
         * the pty, because the output would then be missing from the tab.
         */
        private volatile boolean connected;

        private Attempt(@NotNull String instanceId, @NotNull PtyProcess process) {
            this.instanceId = instanceId;
            this.process = process;
        }

        @Override
        public void output(char @NotNull [] buffer, int offset, int length) {
            synchronized (this.earlyOutput) {
                final int room = EARLY_OUTPUT_LIMIT - this.earlyOutput.length();
                if (room > 0) {
                    this.earlyOutput.append(buffer, offset, Math.min(room, length));
                }
            }
        }

        @Override
        public void closed() {
            this.outputClosed.complete(null);
        }

        private void unsubscribe() {
            final Disposable current = this.subscription;
            if (current != null) {
                current.dispose();
            }
        }

        /**
         * Waits for the rest of the output and looks for Hole's rejection of the instance ID.
         */
        private boolean reportsDuplicateId() {
            try {
                this.outputClosed.get(OUTPUT_DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // no terminal read the output in time; decide on what was read
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            synchronized (this.earlyOutput) {
                return reportsInstanceIdConflict(this.earlyOutput, this.instanceId);
            }
        }
    }
}
