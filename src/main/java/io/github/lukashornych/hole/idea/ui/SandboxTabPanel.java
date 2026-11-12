package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.JBTerminalWidget;
import io.github.lukashornych.hole.idea.git.WorktreeLister;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.session.SessionPresentation;
import io.github.lukashornych.hole.idea.session.SessionState;
import io.github.lukashornych.hole.idea.ui.library.LibrariesDialog;
import io.github.lukashornych.hole.idea.ui.network.NetworkDialog;
import io.github.lukashornych.hole.idea.ui.network.SettingsEntrySaver;
import io.github.lukashornych.hole.idea.ui.settings.SettingsDocuments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Body of one sandbox tab: the header and, below it, the terminal widget connected to the `hole` process.
 *
 * <p>The widget is registered with the tab's {@code Content} as its parent disposable, so it is disposed with the
 * tab. The `hole` process is not a shell: it is spawned by the session and only attached here through
 * {@link JBTerminalWidget#start(com.jediterm.terminal.TtyConnector)}. A plain {@link JBTerminalWidget} is used, not
 * the Terminal plugin's {@code ShellTerminalWidget}, whose shell-command tracking can consume Enter and refreshes the
 * VFS after every Enter. The agent's OSC 0/2 title is read through jediterm's application title listener,
 * independent of the IDE's title tracking settings. File paths in the output are linked by {@link SandboxPathFilter},
 * as the Terminal plugin's own path filter is not available to a plain widget. {@link ImagePasteHandler} turns a
 * paste of a clipboard image into the path of the saved image; {@link EscapeKeyHandler} keeps `Escape` in the
 * terminal.</p>
 *
 * <p>The header is re-rendered only when its {@link HeaderModel} changed; the 1 s timer (stopping / restarting timer,
 * Force Stop after 30 s) does nothing once the sandbox is no longer alive.</p>
 *
 * <p>Restart and the Network / Docker pill toggles ask for a confirmation (the agent session ends; not for an exited
 * sandbox) and then let the manager restart the sandbox in this tab; the terminal is replaced on {@link #reconnect()}.</p>
 */
final class SandboxTabPanel extends HoleToolWindowView.Tab {

    private static final Logger LOG = Logger.getInstance(SandboxTabPanel.class);

    private final Project project;
    private final SandboxSession session;
    private final SandboxSessionManager manager;
    private final SandboxHeaderPanel header;
    private final Timer timer;
    private Disposable parent;
    /** The open Network window of this sandbox, {@code null} when none. EDT only. */
    private NetworkDialog network;
    /** Instance whose {@link #removedFromSettings} these are; a restart starts a new one. EDT only. */
    private String removedInstance;
    /** Entries the Network window removed from the settings files of {@link #removedInstance}, per setting. */
    private Map<NetworkSetting, Set<String>> removedFromSettings;
    /** The open Libraries window of this sandbox, {@code null} when none. EDT only. */
    private LibrariesDialog libraries;
    /** Instance whose {@link #librariesSnapshot} this is; a restart starts a new one. EDT only. */
    private String librariesInstance;
    /** The settings files' libraries when the Libraries window was first opened for {@link #librariesInstance}. */
    private AtomicReference<List<SettingsLibraries.Library>> librariesSnapshot;
    private JBTerminalWidget widget;
    /** Last model shown in the header; {@code null} before the first render. */
    private HeaderModel rendered;
    /** The git checkout of the project directory; looked up in the background on connect. EDT only. */
    private HeaderModel.Checkout checkout = HeaderModel.Checkout.NONE;

    SandboxTabPanel(@NotNull Project project, @NotNull SandboxSession session, @NotNull SandboxSessionManager manager) {
        this.project = project;
        this.session = session;
        this.manager = manager;
        this.header = new SandboxHeaderPanel(
            session::forceStop, () -> manager.requestClose(session), this::toggleNetwork, this::toggleDocker, this::restart,
            this::openSettings, this::openNetwork, this::openLibraries
        );
        add(this.header, BorderLayout.NORTH);
        // the stopping timer and the Force Stop offer tick locally between polls
        this.timer = new Timer(1_000, event -> tick());
    }

    /**
     * Creates the terminal for the session's current process. Call once, on the EDT, after the tab exists.
     *
     * @param parent the tab's content; disposes the widget
     */
    @Override
    void connect(@NotNull Disposable parent) {
        this.parent = parent;
        createWidget();
        render();
        this.timer.start();
        detectWorktree();
    }

    /**
     * The session restarted its process with a new instance ID: replaces the terminal.
     */
    @Override
    void reconnect() {
        if (this.widget != null) {
            remove(this.widget.getComponent());
            Disposer.dispose(this.widget);
            this.widget = null;
        }
        createWidget();
        render();
    }

    /**
     * Refreshes the header from the session when what it shows has changed.
     */
    @Override
    void render() {
        render(this.session.snapshot());
    }

    private void tick() {
        final SandboxSession.Snapshot snapshot = this.session.snapshot();
        // "Stopping / Restarting for N s" only moves while the sandbox is alive; EXITED / CLOSED change through render()
        if (snapshot.state().isAlive()) {
            render(snapshot);
        }
    }

    private void render(@NotNull SandboxSession.Snapshot snapshot) {
        if (this.network != null && (this.network.isDisposed() || !this.network.instanceId().equals(snapshot.instanceId())
            || snapshot.state() != SessionState.RUNNING)) {
            // the window belongs to one instance: a restart, a stop or the exit ends it
            closeNetwork();
        }
        if (this.libraries != null) {
            if (this.libraries.isDisposed() || !this.libraries.instanceId().equals(snapshot.instanceId())) {
                // stays open in EXITED: a library can be fixed before the sandbox starts again
                closeLibraries();
            } else if (snapshot.state() == SessionState.EXITED) {
                this.libraries.sandboxExited();
            }
        }
        final HeaderModel model = HeaderModel.of(snapshot, this.session.launch().debug(), this.checkout, Instant.now());
        if (!model.equals(this.rendered)) {
            this.rendered = model;
            this.header.render(model);
        }
    }

    /**
     * Finds out whether the project directory lies in the main or a linked git worktree, which the header shows next
     * to the project folder. Decided by git, not by comparing with the IDE project root: the IDE may itself be opened in a worktree.
     * The directory does not change on a restart, so once per tab.
     */
    private void detectWorktree() {
        final Path directory = this.session.launch().projectDirectory();
        // not Disposer.isDisposed(parent), which is deprecated
        final AtomicBoolean disposed = new AtomicBoolean();
        Disposer.register(this.parent, () -> disposed.set(true));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HeaderModel.Checkout found;
            try {
                found = new WorktreeLister(new CliRunner(CliRunner.SHELL_ENVIRONMENT)).isLinked(directory)
                    ? HeaderModel.Checkout.WORKTREE
                    : HeaderModel.Checkout.MAIN;
            } catch (CliException e) {
                // not a git repository, or git missing: no checkout kind shown
                LOG.debug("No worktrees for " + directory + ": " + e.getMessage());
                found = HeaderModel.Checkout.NONE;
            } catch (RuntimeException e) {
                LOG.warn("Could not list worktrees for " + directory, e);
                found = HeaderModel.Checkout.NONE;
            }
            final HeaderModel.Checkout result = found;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed.get()) {
                    return;
                }
                this.checkout = result;
                render();
            }, ModalityState.any());
        });
    }

    /**
     * Global / Project link in the header: opens the file in an editor, offering to create a missing one. The project
     * file is the one of the sandbox's own project directory (a worktree sandbox opens its worktree's file).
     */
    private void openSettings(@NotNull HoleSettingsClient.Scope scope) {
        final InstanceInfo info = this.session.snapshot().info();
        final Path directory = info != null && !info.projectPath().isEmpty()
            ? Path.of(info.projectPath())
            : this.session.launch().projectDirectory();
        SettingsFileOpener.open(this.project, this.header, scope, directory);
    }

    /**
     * Restart from the header toolbar: same configuration. A running sandbox asks
     * for a confirmation first; an exited one has no agent session to lose.
     */
    private void restart() {
        final SessionState state = this.session.snapshot().state();
        if (state == SessionState.EXITED) {
            this.manager.restart(this.session, null);
            return;
        }
        if (state != SessionState.RUNNING) {
            return;
        }
        final SandboxSession.Launch launch = this.session.launch();
        if (confirmRestart(launch, null)) {
            this.manager.restart(this.session, null);
        }
    }

    /**
     * Click on the Docker pill of a running sandbox: restart with Docker-in-Docker toggled, after a confirmation. The
     * shown state comes from Hole's metadata; the favourite the sandbox was started from is not changed.
     */
    private void toggleDocker() {
        final SandboxSession.Snapshot snapshot = this.session.snapshot();
        final InstanceInfo info = snapshot.info();
        if (snapshot.state() != SessionState.RUNNING || info == null) {
            return;
        }
        final SandboxSession.Launch launch = this.session.launch();
        restartWith(launch, SessionPresentation.RestartChange.toggleDocker(info.docker()));
    }

    /**
     * Click on the Network pill of a running sandbox: restart with unrestricted network toggled, after a confirmation.
     * The shown state comes from Hole's metadata; the favourite the sandbox was started from is not changed.
     */
    private void toggleNetwork() {
        final SandboxSession.Snapshot snapshot = this.session.snapshot();
        final InstanceInfo info = snapshot.info();
        if (snapshot.state() != SessionState.RUNNING || info == null) {
            return;
        }
        final SandboxSession.Launch launch = this.session.launch();
        restartWith(launch, SessionPresentation.RestartChange.toggleUnrestricted(info.flags().unrestricted()));
    }

    private void restartWith(@NotNull SandboxSession.Launch launch, @NotNull SessionPresentation.RestartChange change) {
        if (confirmRestart(launch, change)) {
            this.manager.restart(this.session, change.apply(launch.config()));
        }
    }

    private boolean confirmRestart(@NotNull SandboxSession.Launch launch, @Nullable SessionPresentation.RestartChange change) {
        final InstanceInfo info = this.session.snapshot().info();
        final String question = SessionPresentation.restartQuestion(
            launch.config(), SessionPresentation.projectFolder(launch.projectDirectory()), change,
            info == null ? 0 : info.runtimeEntryCount()
        );
        return MessageDialogBuilder.yesNo("Restart Sandbox", question)
            .yesText("Restart")
            .noText("Cancel")
            .ask(this);
    }

    /**
     * Returns the component that should get the focus when the tab is selected.
     */
    @Override
    @Nullable
    JComponent focusComponent() {
        return this.widget == null ? null : this.widget.getPreferredFocusableComponent();
    }

    @Override
    public void dispose() {
        this.timer.stop();
        closeNetwork();
        closeLibraries();
    }

    /**
     * Network in the header: opens the window for the running instance, or brings the open one to the front.
     */
    private void openNetwork() {
        final SandboxSession.Snapshot snapshot = this.session.snapshot();
        final InstanceInfo info = snapshot.info();
        if (snapshot.state() != SessionState.RUNNING || info == null) {
            return;
        }
        if (this.network != null && !this.network.isDisposed()) {
            this.network.getWindow().toFront();
            return;
        }
        final SandboxSession.Launch launch = this.session.launch();
        final Path directory = info.projectPath().isEmpty() ? launch.projectDirectory() : Path.of(info.projectPath());
        final HoleModifyClient client = new HoleModifyClient(
            launch.executable().toString(), HoleExecutable.getInstance().runner(), directory, snapshot.instanceId()
        );
        final SettingsEntrySaver saver = new SettingsEntrySaver(settingsDocuments(launch, directory));
        final String title = "Network" + SessionPresentation.SEPARATOR + SessionPresentation.projectFolder(directory)
            + SessionPresentation.SEPARATOR + launch.config().agentSpec() + " (" + snapshot.instanceId() + ")";
        if (!snapshot.instanceId().equals(this.removedInstance)) {
            this.removedInstance = snapshot.instanceId();
            this.removedFromSettings = new EnumMap<>(NetworkSetting.class);
            for (NetworkSetting setting : NetworkSetting.values()) {
                this.removedFromSettings.put(setting, ConcurrentHashMap.newKeySet());
            }
        }
        this.network = new NetworkDialog(this.project, client, saver, this.removedFromSettings, title, info.flags().unrestricted(), this::restart);
        this.network.show();
    }

    /**
     * Libraries in the header: opens the window for the current instance, or brings the open one to the front.
     */
    private void openLibraries() {
        final SandboxSession.Snapshot snapshot = this.session.snapshot();
        if (!snapshot.state().isAlive() && snapshot.state() != SessionState.EXITED) {
            return;
        }
        if (this.libraries != null && !this.libraries.isDisposed()) {
            this.libraries.getWindow().toFront();
            return;
        }
        final InstanceInfo info = snapshot.info();
        final SandboxSession.Launch launch = this.session.launch();
        final Path directory = info == null || info.projectPath().isEmpty() ? launch.projectDirectory() : Path.of(info.projectPath());
        final String title = "Libraries" + SessionPresentation.SEPARATOR + SessionPresentation.projectFolder(directory)
            + SessionPresentation.SEPARATOR + launch.config().agentSpec() + " (" + snapshot.instanceId() + ")";
        if (!snapshot.instanceId().equals(this.librariesInstance)) {
            this.librariesInstance = snapshot.instanceId();
            this.librariesSnapshot = new AtomicReference<>();
        }
        this.libraries = new LibrariesDialog(
            this.project, settingsDocuments(launch, directory), launch.config().libraries(), this.librariesSnapshot,
            snapshot.instanceId(), title, () -> this.session.snapshot().state(), this::restart
        );
        this.libraries.show();
    }

    private void closeLibraries() {
        if (this.libraries != null) {
            if (!this.libraries.isDisposed()) {
                this.libraries.close(DialogWrapper.CANCEL_EXIT_CODE);
            }
            this.libraries = null;
        }
    }

    @NotNull
    private SettingsDocuments settingsDocuments(@NotNull SandboxSession.Launch launch, @NotNull Path directory) {
        return new SettingsDocuments(this.project, new SettingsFileResolver(
            new HoleSettingsClient(launch.executable().toString(), HoleExecutable.getInstance().runner())
        ), directory);
    }

    private void closeNetwork() {
        if (this.network != null) {
            if (!this.network.isDisposed()) {
                this.network.close(DialogWrapper.CANCEL_EXIT_CODE);
            }
            this.network = null;
        }
    }

    private void createWidget() {
        final JBTerminalSystemSettingsProvider settings = new JBTerminalSystemSettingsProvider();
        this.widget = new JBTerminalWidget(this.project, settings, this.parent);
        this.widget.getTerminal().addApplicationTitleListener(this.session::setAgentTitle);
        final JBTerminalWidget terminal = this.widget;
        this.widget.addMessageFilter(new SandboxPathFilter(
            this.project, this.session.launch().projectDirectory(), () -> terminal.getTerminalTextBuffer().getWidth()
        ));
        new ImagePasteHandler(this.widget, settings, ImagePasteHandler.Target.of(this.session),
            new HoleNotifications(this.project)::imagePasteFailed).install();
        new EscapeKeyHandler(this.widget).install();
        add(this.widget.getComponent(), BorderLayout.CENTER);
        this.widget.start(this.session.createConnector());
        revalidate();
        repaint();
    }
}
