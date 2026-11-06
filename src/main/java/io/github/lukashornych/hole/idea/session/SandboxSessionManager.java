package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.serviceContainer.NonInjectable;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsLocator;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.ShellEnvironment;
import io.github.lukashornych.hole.idea.process.ShellEnvironmentException;
import io.github.lukashornych.hole.idea.ui.HoleNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Owns the sandbox sessions of one project: starts them, keeps the tab selection history, decides when a tab may
 * be removed, and stops all sandboxes when the project closes.
 *
 * <p>Closing a tab is a request: the view asks {@link #mayRemove(SandboxSession)} from its
 * {@code contentRemoveQuery}, vetoes the removal and calls {@link #requestClose(SandboxSession)}; the session sends
 * SIGINT and the manager removes the tab itself when the process has exited. Before it removes a selected tab it
 * selects the previously selected one.</p>
 *
 * <p>Every start and restart reads the environment of the user's shell first, then probes Hole and spawns `hole start`
 * with that environment. A failed read starts nothing and lets the user retry.</p>
 *
 * <p>Threading: {@link #start} and {@link #restart} read, probe and spawn on a background thread; all view calls and
 * the session list are confined to the UI executor (the EDT in the IDE).</p>
 */
@Service(Service.Level.PROJECT)
public final class SandboxSessionManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(SandboxSessionManager.class);

    private final Path projectRoot;
    private final Supplier<HoleCapability> capability;
    private final EnvironmentSource environment;
    private final SessionServices services;
    private final Executor ui;
    private final StartFailureNotifier notifier;

    private final List<SandboxSession> sessions = new CopyOnWriteArrayList<>();
    /** UI-confined. */
    private final TabHistory<SandboxSession> history = new TabHistory<>();
    private final SandboxSession.Listener sessionListener = new SessionListener();
    /** UI-confined. */
    private SessionView view;
    /** UI-confined. */
    private SandboxSession selected;
    /** Session whose tab the manager is removing right now (no veto). UI-confined. */
    private SandboxSession removing;
    private volatile boolean disposing;

    /**
     * Platform constructor.
     */
    public SandboxSessionManager(@NotNull Project project) {
        this(
            projectRoot(project),
            () -> HoleExecutable.getInstance().probe(),
            () -> ShellEnvironment.getInstance().readShowingProgress(project),
            SessionServices.platform(),
            // no expiry condition: the runnables check `disposing` themselves, so a session spawned while the
            // project closes still gets its SIGINT
            runnable -> ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any()),
            new HoleNotifications(project)
        );
        this.services.background().execute(() -> sweepPasteDirectories(this.services.pastes()));
    }

    /**
     * @param projectRoot  IDE project root: working directory of `hole start`
     * @param capability   locates and probes Hole (blocking)
     * @param environment  reads the user's shell environment (blocking)
     * @param services     what sessions need
     * @param ui           runs view updates (the EDT in the IDE)
     * @param notifier     reports starts that produce no tab
     */
    @NonInjectable
    public SandboxSessionManager(
        @NotNull Path projectRoot,
        @NotNull Supplier<HoleCapability> capability,
        @NotNull EnvironmentSource environment,
        @NotNull SessionServices services,
        @NotNull Executor ui,
        @NotNull StartFailureNotifier notifier
    ) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
        this.capability = Objects.requireNonNull(capability, "capability");
        this.environment = Objects.requireNonNull(environment, "environment");
        this.services = Objects.requireNonNull(services, "services");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
    }

    /**
     * Returns the project's instance.
     */
    @NotNull
    public static SandboxSessionManager getInstance(@NotNull Project project) {
        return project.getService(SandboxSessionManager.class);
    }

    /**
     * Returns the IDE project root.
     */
    @NotNull
    public Path projectRoot() {
        return this.projectRoot;
    }

    /**
     * Connects the tool window. Sessions that exist already get their tabs. Call on the UI thread.
     */
    public void attachView(@NotNull SessionView view) {
        this.view = view;
        for (SandboxSession session : this.sessions) {
            view.add(session);
        }
        if (this.selected != null) {
            view.select(this.selected);
        }
    }

    /**
     * Starts a sandbox in a new tab. The shell environment is read and Hole is probed first; when the environment
     * cannot be read, Hole is missing or too old, or the process cannot be spawned, the user is told and no tab is
     * created. Errors Hole itself prints (Docker not running,
     * unknown profile) stay readable in the tab, which then shows "Exited with code N".
     *
     * @param config     sandbox configuration
     * @param debug      open a debug shell instead of the agent
     * @param instanceId instance ID to use (e.g. the one shown in the command preview), or {@code null} to generate one
     * @return completes with the session once its tab is requested, or with {@code null} when nothing was started
     * (the user was notified then, also after an unexpected exception); completes exceptionally only on an
     * {@link Error}
     */
    @NotNull
    public CompletableFuture<SandboxSession> start(@NotNull SandboxConfig config, boolean debug, @Nullable String instanceId) {
        final CompletableFuture<SandboxSession> result = new CompletableFuture<>();
        if (this.disposing) {
            result.complete(null);
            return result;
        }
        this.services.background().execute(() -> {
            try {
                result.complete(startInBackground(config, debug, instanceId));
            } catch (RuntimeException e) {
                // a bug or an unexpected platform failure: tell the user like a failed spawn instead of the IDE's
                // fatal-error balloon; callers do not observe the future, so it completes normally with null
                LOG.warn("Starting a sandbox failed unexpectedly (agent " + config.agent() + ")", e);
                this.notifier.spawnFailed(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
                result.complete(null);
            } catch (Error e) {
                result.completeExceptionally(e);
                throw e;
            }
        });
        return result;
    }

    /**
     * Restarts a RUNNING sandbox in its tab (see {@link SandboxSession#restart(SandboxSession.Launch)}), e.g. to apply
     * changed settings files or another config, or starts an EXITED one again (see
     * {@link SandboxSession#relaunch(SandboxSession.Launch)}). The shell environment is read and Hole is probed again
     * first, so the restart uses the current executable and environment; when either fails now, the user is told and
     * the sandbox is left alone. Does nothing unless the session is RUNNING or EXITED.
     *
     * @param session   the session to restart
     * @param newConfig the config of the new sandbox, or {@code null} to keep the current one
     * @return completes with {@code true} once the old process got SIGINT (RUNNING) or the new process was spawned or
     * its failure reported (EXITED), or with {@code false} when nothing was restarted (the user was notified when Hole
     * could not be used)
     */
    @NotNull
    public CompletableFuture<Boolean> restart(@NotNull SandboxSession session, @Nullable SandboxConfig newConfig) {
        final CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (this.disposing) {
            result.complete(false);
            return result;
        }
        // the state the user acted on, taken at the click: the background task may start much later
        final SessionState clicked = session.snapshot().state();
        this.services.background().execute(() -> {
            try {
                result.complete(restartInBackground(session, newConfig, clicked));
            } catch (RuntimeException e) {
                LOG.warn("Restarting a sandbox failed unexpectedly (instance " + session.snapshot().instanceId() + ")", e);
                this.notifier.spawnFailed(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
                result.complete(false);
            } catch (Error e) {
                result.completeExceptionally(e);
                throw e;
            }
        });
        return result;
    }

    /**
     * Returns the sessions with a tab, in start order.
     */
    @NotNull
    public List<SandboxSession> sessions() {
        return List.copyOf(this.sessions);
    }

    /**
     * Returns the selected session, {@code null} when none. UI thread only.
     */
    @Nullable
    public SandboxSession selectedSession() {
        return this.selected;
    }

    /**
     * Returns the selection history, most recent last. UI thread only.
     */
    @NotNull
    public List<SandboxSession> selectionHistory() {
        return this.history.asList();
    }

    /**
     * The view reports that a tab was selected. UI thread only.
     */
    public void selected(@NotNull SandboxSession session) {
        if (!this.sessions.contains(session)) {
            return;
        }
        this.selected = session;
        this.history.selected(session);
    }

    /**
     * Returns {@code true} when the view may remove the session's tab now: the manager itself removes it, the session
     * is unknown, or the project is closing. Otherwise the view vetoes the removal and calls
     * {@link #requestClose(SandboxSession)}. UI thread only.
     */
    public boolean mayRemove(@NotNull SandboxSession session) {
        return this.disposing || session == this.removing || !this.sessions.contains(session);
    }

    /**
     * The user closes the session's tab (× or the Close button of an exited sandbox). No confirmation: a running
     * sandbox gets SIGINT and its tab closes when Hole exits.
     */
    public void requestClose(@NotNull SandboxSession session) {
        session.requestClose();
    }

    /**
     * The view reports a removed tab. Removals the manager did itself are ignored; others (e.g. while the tool window
     * is disposed) make it forget the session. UI thread only.
     */
    public void tabRemoved(@NotNull SandboxSession session) {
        if (session != this.removing && this.sessions.remove(session)) {
            forget(session);
            session.dispose();
        }
    }

    /**
     * Project close or IDE exit: every sandbox gets SIGINT, marked as requested by the plugin. Nothing waits for the
     * teardown, because no tab needs to be selected afterwards. Hole's watchdog finishes the teardown even when the
     * JVM is gone; the pty hang-up on JVM exit is a further safety net.
     */
    @Override
    public void dispose() {
        this.disposing = true;
        for (SandboxSession session : this.sessions) {
            session.shutdown();
        }
        this.sessions.clear();
    }

    @Nullable
    private SandboxSession startInBackground(@NotNull SandboxConfig config, boolean debug, @Nullable String preferredId) {
        // before the probe: it locates `hole` on the PATH the read has just refreshed
        final Map<String, String> env = readEnvironment(() -> start(config, debug, preferredId));
        if (env == null) {
            return null;
        }
        final HoleCapability hole = this.capability.get();
        if (!hole.canStart() || hole.executable() == null) {
            this.notifier.cannotStart(hole);
            return null;
        }
        if (hole.status() == HoleCapability.Status.UNAVAILABLE) {
            // Hole prints the reason (e.g. no container engine) in the tab itself
            LOG.info("Starting a sandbox although the Hole probe failed: " + hole.message());
        }
        final String instanceId = InstanceIdGenerator.isValid(preferredId) ? preferredId : this.services.instanceIds().get();
        final SandboxSession.Launch launch = launch(config, debug, hole.executable(), env);
        final SandboxSession session = new SandboxSession(launch, instanceId, this.services, this.sessionListener);
        try {
            session.start();
        } catch (IOException | RuntimeException e) {
            // the command line is not logged: agent arguments may carry secrets
            LOG.warn("Could not start sandbox (" + launch.describe() + ", instance " + instanceId + "): " + e.getMessage());
            this.notifier.spawnFailed(Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            return null;
        }
        if (this.disposing) {
            // the project closed while Hole was probed and spawned; dispose() did not see this session
            session.shutdown();
            return null;
        }
        this.ui.execute(() -> added(session));
        return session;
    }

    /**
     * @param clicked the state when the user asked for the restart; an EXITED one (no confirmation was asked) only
     *                relaunches, so a stale click never stops a sandbox that another restart brought back to RUNNING
     */
    private boolean restartInBackground(
        @NotNull SandboxSession session, @Nullable SandboxConfig newConfig, @NotNull SessionState clicked
    ) {
        if (this.disposing || !restartable(clicked) || session.snapshot().state() != clicked) {
            return false;
        }
        final Map<String, String> env = readEnvironment(() -> restart(session, newConfig));
        if (env == null) {
            return false;
        }
        final HoleCapability hole = this.capability.get();
        if (!hole.canStart() || hole.executable() == null) {
            this.notifier.cannotStart(hole);
            return false;
        }
        final SandboxSession.Launch current = session.launch();
        final SandboxConfig config = newConfig != null ? newConfig : current.config();
        final SandboxSession.Launch next = launch(config, current.debug(), hole.executable(), env);
        // each call refuses any state other than its own
        return clicked == SessionState.EXITED ? session.relaunch(next) : session.restart(next);
    }

    private static boolean restartable(@NotNull SessionState state) {
        return state == SessionState.RUNNING || state == SessionState.EXITED;
    }

    /**
     * Reads the user's shell environment for a start; on failure tells the user, who may {@code retry}.
     *
     * @return the environment, or {@code null} when nothing may be started
     */
    @Nullable
    private Map<String, String> readEnvironment(@NotNull Runnable retry) {
        try {
            return this.environment.read();
        } catch (ShellEnvironmentException e) {
            if (!e.isCancelled() && !this.disposing) {
                LOG.info("Sandbox not started: the shell environment could not be read (" + e.getMessage() + ")");
                this.notifier.environmentUnavailable(e.getShell(), e.getReason(), retry);
            }
            return null;
        }
    }

    /**
     * Builds what a session starts.
     *
     * @param env the environment just read from the user's shell
     */
    @NotNull
    private SandboxSession.Launch launch(
        @NotNull SandboxConfig config,
        boolean debug,
        @NotNull Path executable,
        @NotNull Map<String, String> env
    ) {
        final Path projectDirectory = new HoleSettingsLocator(() -> env)
            .resolveProjectDirectory(this.projectRoot, config.projectPath())
            .orElse(this.projectRoot);
        return new SandboxSession.Launch(config, debug, executable, this.projectRoot, projectDirectory, env);
    }

    private void added(@NotNull SandboxSession session) {
        if (this.disposing) {
            session.shutdown();
            return;
        }
        if (session.snapshot().state() == SessionState.CLOSED) {
            // ended with code 0 before its tab existed
            session.dispose();
            return;
        }
        this.sessions.add(session);
        if (this.view != null) {
            this.view.add(session);
            this.view.select(session);
        }
    }

    private void remove(@NotNull SandboxSession session) {
        if (!this.sessions.contains(session)) {
            return;
        }
        if (this.view != null) {
            this.removing = session;
            try {
                if (session == this.selected) {
                    final SandboxSession previous = this.history.previous(session);
                    if (previous != null && this.sessions.contains(previous)) {
                        this.view.select(previous);
                    }
                }
                this.view.remove(session);
            } finally {
                this.removing = null;
            }
        }
        this.sessions.remove(session);
        forget(session);
        session.dispose();
    }

    private void forget(@NotNull SandboxSession session) {
        this.history.remove(session);
        if (this.selected == session) {
            this.selected = null;
        }
    }

    /**
     * Removes the paste directories of sandboxes that are gone: {@link #dispose()} does not wait for the exits, and
     * after a SIGKILL of the IDE nothing deletes them. Blocking.
     */
    private static void sweepPasteDirectories(@NotNull PasteDirectory pastes) {
        final HoleExecutable hole = HoleExecutable.getInstance();
        hole.locate().ifPresent(executable -> {
            final int removed = pastes.sweep(new HoleListClient(executable.toString(), hole.runner()));
            if (removed > 0) {
                LOG.info("Removed " + removed + " paste directories of ended sandboxes");
            }
        });
    }

    @NotNull
    private static Path projectRoot(@NotNull Project project) {
        final String basePath = project.getBasePath();
        return Path.of(basePath != null ? basePath : System.getProperty("user.home"));
    }

    /**
     * Reads the environment of the user's shell for a start or restart.
     */
    @FunctionalInterface
    public interface EnvironmentSource {

        /**
         * Reads the environment. Blocking.
         *
         * @throws ShellEnvironmentException when it cannot be read or the user cancelled waiting for it
         */
        @NotNull
        Map<String, String> read() throws ShellEnvironmentException;
    }

    private final class SessionListener implements SandboxSession.Listener {

        @Override
        public void sessionChanged(@NotNull SandboxSession session) {
            SandboxSessionManager.this.ui.execute(() -> {
                if (SandboxSessionManager.this.disposing || !SandboxSessionManager.this.sessions.contains(session)) {
                    return;
                }
                if (session.snapshot().state() == SessionState.CLOSED) {
                    remove(session);
                } else if (SandboxSessionManager.this.view != null) {
                    SandboxSessionManager.this.view.update(session);
                }
            });
        }

        @Override
        public void processRestarted(@NotNull SandboxSession session) {
            SandboxSessionManager.this.ui.execute(() -> {
                if (SandboxSessionManager.this.sessions.contains(session) && SandboxSessionManager.this.view != null) {
                    SandboxSessionManager.this.view.processRestarted(session);
                }
            });
        }

        @Override
        public void restartFailed(@NotNull SandboxSession session, @NotNull String reason) {
            SandboxSessionManager.this.notifier.restartFailed(reason);
        }
    }
}
