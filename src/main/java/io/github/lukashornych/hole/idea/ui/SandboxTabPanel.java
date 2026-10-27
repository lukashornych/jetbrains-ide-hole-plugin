package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.JBTerminalWidget;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.session.SessionPresentation;
import io.github.lukashornych.hole.idea.session.SessionState;
import io.github.lukashornych.hole.idea.ui.network.NetworkDialog;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Body of one sandbox tab: the header and, below it, the terminal widget connected to the `hole` process.
 *
 * <p>The widget is registered with the tab's {@code Content} as its parent disposable, so it is disposed with the
 * tab. The `hole` process is not a shell: it is spawned by the session and only attached here through
 * {@link JBTerminalWidget#start(com.jediterm.terminal.TtyConnector)}. A plain {@link JBTerminalWidget} is used, not
 * the Terminal plugin's {@code ShellTerminalWidget}, whose shell-command tracking can consume Enter and refreshes the
 * VFS after every Enter. The agent's OSC 0/2 title is read through jediterm's application title listener,
 * independent of the IDE's title tracking settings. File paths in the output are linked by {@link SandboxPathFilter},
 * as the Terminal plugin's own path filter is not available to a plain widget.</p>
 *
 * <p>The header is re-rendered only when its {@link HeaderModel} changed; the 1 s timer (stopping / restarting timer,
 * Force Stop after 30 s) does nothing once the sandbox is no longer alive.</p>
 *
 * <p>Restart and the Docker toggle ask for a confirmation (the agent session ends) and then let the manager restart
 * the sandbox in this tab; the terminal is replaced on {@link #reconnect()}.</p>
 */
final class SandboxTabPanel extends HoleToolWindowView.Tab {

    private final Project project;
    private final SandboxSession session;
    private final SandboxSessionManager manager;
    private final SandboxHeaderPanel header;
    private final Timer timer;
    private Disposable parent;
    /** The open Network window of this sandbox, {@code null} when none. EDT only. */
    private NetworkDialog network;
    private JBTerminalWidget widget;
    /** Last model shown in the header; {@code null} before the first render. */
    private HeaderModel rendered;

    SandboxTabPanel(@NotNull Project project, @NotNull SandboxSession session, @NotNull SandboxSessionManager manager) {
        this.project = project;
        this.session = session;
        this.manager = manager;
        this.header = new SandboxHeaderPanel(
            session::forceStop, () -> manager.requestClose(session), this::toggleDocker, this::restart, this::openSettings,
            this::openNetwork
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
        final HeaderModel model = HeaderModel.of(snapshot, this.session.launch().debug(), Instant.now());
        if (!model.equals(this.rendered)) {
            this.rendered = model;
            this.header.render(model);
        }
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
     * Restart from the header toolbar: same configuration, after a confirmation.
     */
    private void restart() {
        if (this.session.snapshot().state() != SessionState.RUNNING) {
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
        final boolean docker = !info.docker();
        if (confirmRestart(launch, docker)) {
            this.manager.restart(this.session, launch.config().withWithDocker(docker));
        }
    }

    private boolean confirmRestart(@NotNull SandboxSession.Launch launch, @Nullable Boolean docker) {
        final InstanceInfo info = this.session.snapshot().info();
        final String question = SessionPresentation.restartQuestion(
            launch.config(), SessionPresentation.projectFolder(launch.projectDirectory()), docker,
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
        final String title = "Network" + SessionPresentation.SEPARATOR + SessionPresentation.projectFolder(directory)
            + SessionPresentation.SEPARATOR + launch.config().agentSpec() + " (" + snapshot.instanceId() + ")";
        this.network = new NetworkDialog(this.project, client, title, info.flags().unrestricted(), this::restart);
        this.network.show();
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
        this.widget = new JBTerminalWidget(this.project, new JBTerminalSystemSettingsProvider(), this.parent);
        this.widget.getTerminal().addApplicationTitleListener(this.session::setAgentTitle);
        this.widget.addMessageFilter(new SandboxPathFilter(this.project, this.session.launch().projectDirectory()));
        add(this.widget.getComponent(), BorderLayout.CENTER);
        this.widget.start(this.session.createConnector());
        revalidate();
        repaint();
    }
}
