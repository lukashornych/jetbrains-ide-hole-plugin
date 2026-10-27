package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.terminal.JBTerminalWidget;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.time.Instant;

/**
 * Body of one sandbox tab: the header and, below it, the terminal widget connected to the `hole` process.
 *
 * <p>The widget is registered with the tab's {@code Content} as its parent disposable, so it is disposed with the
 * tab. The `hole` process is not a shell: it is spawned by the session and only attached here through
 * {@link JBTerminalWidget#start(com.jediterm.terminal.TtyConnector)}. A plain {@link JBTerminalWidget} is used, not
 * the Terminal plugin's {@code ShellTerminalWidget}, whose shell-command tracking can consume Enter and refreshes the
 * VFS after every Enter. The agent's OSC 0/2 title is read through jediterm's application title listener,
 * independent of the IDE's title tracking settings.</p>
 *
 * <p>The header is re-rendered only when its {@link HeaderModel} changed; the 1 s timer (uptime, stopping timer)
 * does nothing once the sandbox is no longer alive.</p>
 */
final class SandboxTabPanel extends HoleToolWindowView.Tab {

    private final Project project;
    private final SandboxSession session;
    private final SandboxHeaderPanel header;
    private final Timer timer;
    private Disposable parent;
    private JBTerminalWidget widget;
    /** Last model shown in the header; {@code null} before the first render. */
    private HeaderModel rendered;

    SandboxTabPanel(@NotNull Project project, @NotNull SandboxSession session, @NotNull SandboxSessionManager manager) {
        this.project = project;
        this.session = session;
        this.header = new SandboxHeaderPanel(session::forceStop, () -> manager.requestClose(session));
        add(this.header, BorderLayout.NORTH);
        // uptime and the stopping timer tick locally between polls
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
        // uptime and "Stopping for N s" only move while the sandbox is alive; EXITED / CLOSED change through render()
        if (snapshot.state().isAlive()) {
            render(snapshot);
        }
    }

    private void render(@NotNull SandboxSession.Snapshot snapshot) {
        final HeaderModel model = HeaderModel.of(snapshot, this.session.launch().debug(), Instant.now());
        if (!model.equals(this.rendered)) {
            this.rendered = model;
            this.header.render(model);
        }
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
    }

    private void createWidget() {
        this.widget = new JBTerminalWidget(this.project, new JBTerminalSystemSettingsProvider(), this.parent);
        this.widget.getTerminal().addApplicationTitleListener(this.session::setAgentTitle);
        add(this.widget.getComponent(), BorderLayout.CENTER);
        this.widget.start(this.session.createConnector());
        revalidate();
        repaint();
    }
}
