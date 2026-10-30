package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.WrapLayout;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * The small header above a sandbox terminal: instance ID, Debug Shell pill, Docker, the Global / Project settings
 * links (dimmed when the sandbox does not use that file) and project path; the
 * stopping (or restarting) timer with Force Stop; or "Exited with code N" with Close. Fields Hole reports are skeleton
 * placeholders until the first metadata arrives. The network is deliberately not shown. While the sandbox runs, the
 * Docker pill toggles Docker-in-Docker through a restart, and the toolbar at the right end offers Restart.
 */
final class SandboxHeaderPanel extends JPanel {

    /** Resolved on every paint, so a theme switch applies (a static snapshot would keep the old theme's color). */
    private static final Color MUTED = JBColor.lazy(UIUtil::getContextHelpForeground);
    private static final Color ERROR = JBColor.namedColor("Label.errorForeground", new JBColor(0xD1323F, 0xF27C7C));
    private static final Color OK = new JBColor(0x208A3C, 0x5FB865);
    private static final Color WARN = new JBColor(0xB36B00, 0xE5B550);

    private final JBLabel instance = value();
    private final Pill debugPill = new Pill("DEBUG SHELL", WARN);
    private final JComponent dockerField;
    private final Pill dockerPill = new Pill("NO", MUTED);
    private final Skeleton dockerSkeleton = new Skeleton(26);
    private final JComponent settingsField;
    private final ActionLink globalSettings;
    private final ActionLink projectSettings;
    private final Skeleton settingsSkeleton = new Skeleton(150);
    private final JComponent projectField;
    private final JBLabel project = value();
    private final Skeleton projectSkeleton = new Skeleton(90);
    private final JBLabel status = new JBLabel();
    private final JButton forceStop = new JButton("Force Stop");
    private final JBLabel forceStopHint = new JBLabel("Closes the tab; Hole's teardown continues in the background.");
    private final JButton close = new JButton("Close");
    private final ActionToolbar toolbar;
    /** Last model shown; {@code null} before the first render. Written on the EDT, read by action updates. */
    private volatile HeaderModel model;

    /**
     * @param onForceStop    called when Force Stop is pressed
     * @param onClose        called when Close is pressed
     * @param onToggleDocker called when the Docker pill of a running sandbox is clicked
     * @param onRestart      called when Restart is pressed (only enabled while the sandbox runs)
     * @param onSettings     called with the scope when the Global or Project settings link is clicked
     * @param onNetwork      called when Network is pressed (only enabled while the sandbox runs)
     */
    SandboxHeaderPanel(
        @NotNull Runnable onForceStop,
        @NotNull Runnable onClose,
        @NotNull Runnable onToggleDocker,
        @NotNull Runnable onRestart,
        @NotNull Consumer<HoleSettingsClient.Scope> onSettings,
        @NotNull Runnable onNetwork
    ) {
        super(new BorderLayout());
        setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(2, 6)));
        final JPanel fields = new JPanel(new WrapLayout(FlowLayout.LEFT, JBUI.scale(16), JBUI.scale(4)));
        fields.setOpaque(false);
        fields.add(field("Instance", this.instance));
        fields.add(this.debugPill);
        this.dockerField = field("Docker", this.dockerPill, this.dockerSkeleton);
        this.globalSettings = new ActionLink("Global", event -> {
            onSettings.accept(HoleSettingsClient.Scope.GLOBAL);
        });
        this.projectSettings = new ActionLink("Project", event -> {
            onSettings.accept(HoleSettingsClient.Scope.PROJECT);
        });
        this.settingsField = field("Settings", this.globalSettings, this.projectSettings, this.settingsSkeleton);
        this.projectField = field("Project", this.project, this.projectSkeleton);
        fields.add(this.dockerField);
        fields.add(this.settingsField);
        fields.add(this.projectField);
        this.dockerPill.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SandboxHeaderPanel.this.model != null && SandboxHeaderPanel.this.model.dockerToggle()) {
                    onToggleDocker.run();
                }
            }
        });
        this.status.setForeground(ERROR);
        fields.add(this.status);
        this.forceStop.addActionListener(event -> onForceStop.run());
        fields.add(this.forceStop);
        this.forceStopHint.setForeground(MUTED);
        this.forceStopHint.setFont(JBFont.small());
        fields.add(this.forceStopHint);
        this.close.addActionListener(event -> onClose.run());
        fields.add(this.close);
        // GridBag centres the fields vertically when the toolbar makes the header taller than one row of fields
        final JPanel centre = new JPanel(new GridBagLayout());
        centre.setOpaque(false);
        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.anchor = GridBagConstraints.WEST;
        centre.add(fields, constraints);
        add(centre, BorderLayout.CENTER);

        this.toolbar = ActionManager.getInstance()
            .createActionToolbar("HoleSandboxHeader", new DefaultActionGroup(new RestartAction(onRestart), new NetworkAction(onNetwork)), true);
        this.toolbar.setTargetComponent(this);
        this.toolbar.getComponent().setOpaque(false);
        // no toolbar padding, so the header stays as low as a row of fields
        this.toolbar.getComponent().setBorder(JBUI.Borders.empty());
        add(this.toolbar.getComponent(), BorderLayout.EAST);
    }

    /**
     * Shows the model.
     */
    void render(@NotNull HeaderModel model) {
        this.model = model;
        this.instance.setText(model.instanceId());
        this.debugPill.setVisible(model.debug());

        final boolean fields = model.pending() || model.metadata();
        this.dockerField.setVisible(fields);
        this.settingsField.setVisible(fields);
        this.projectField.setVisible(fields);

        this.dockerSkeleton.setVisible(model.pending());
        this.settingsSkeleton.setVisible(model.pending());
        this.projectSkeleton.setVisible(model.pending());
        this.dockerPill.setVisible(model.metadata());
        this.globalSettings.setVisible(model.metadata());
        this.projectSettings.setVisible(model.metadata());
        this.project.setVisible(model.metadata());
        this.dockerPill.setPill(model.docker() ? "ON" : "NO", model.docker() ? OK : MUTED);
        this.dockerPill.setCursor(model.dockerToggle() ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
        this.dockerPill.setToolTipText(model.dockerToggle()
            ? "Click to restart the sandbox with Docker-in-Docker " + (model.docker() ? "off" : "on")
            : null);
        showSettingsLink(this.globalSettings, model.globalUsed(), "global");
        showSettingsLink(this.projectSettings, model.projectUsed(), "project");
        this.settingsField.setToolTipText(model.settingsTooltip().isEmpty() ? null : "Settings used: " + model.settingsTooltip());
        this.project.setText(model.project());
        this.project.setToolTipText(model.projectPath().isEmpty() ? null : model.projectPath());

        final String text = model.exited() != null ? model.exited() : model.stopping();
        this.status.setText(text == null ? "" : text);
        this.status.setVisible(text != null);
        this.forceStop.setVisible(model.forceStop());
        this.forceStop.setToolTipText(model.forceStopCloses()
            ? "Sends SIGKILL to the Hole process and closes the tab. Hole's watchdog keeps tearing the sandbox down "
                + "in the background."
            : "Sends SIGKILL to the Hole process; the sandbox then starts again in this tab. Hole's watchdog keeps "
                + "tearing the old sandbox down in the background.");
        this.forceStopHint.setVisible(model.forceStopCloses());
        this.close.setVisible(model.exited() != null);
        // the Restart action reads the model; refresh it now instead of on the next periodic update
        this.toolbar.updateActionsAsync();
        revalidate();
        repaint();
    }

    /**
     * Network in the header toolbar: opens the runtime network editor; enabled only while the sandbox runs.
     */
    private final class NetworkAction extends DumbAwareAction {
        private final Runnable onNetwork;

        private NetworkAction(@NotNull Runnable onNetwork) {
            super("Network…", "Change the network allow list of the running sandbox (hole modify)", AllIcons.General.Web);
            this.onNetwork = onNetwork;
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            final HeaderModel current = SandboxHeaderPanel.this.model;
            event.getPresentation().setEnabled(current != null && current.network());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            this.onNetwork.run();
        }
    }

    /**
     * Restart in the header toolbar: enabled only while the sandbox runs. The confirmation is the caller's job.
     */
    private final class RestartAction extends DumbAwareAction {
        private final Runnable onRestart;

        private RestartAction(@NotNull Runnable onRestart) {
            super("Restart Sandbox", "Stop this sandbox and start it again in this tab, e.g. to apply changed settings",
                AllIcons.Actions.Restart);
            this.onRestart = onRestart;
        }

        @Override
        public void update(@NotNull AnActionEvent event) {
            final HeaderModel current = SandboxHeaderPanel.this.model;
            event.getPresentation().setEnabled(current != null && current.restart());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            // reads only the immutable model through a volatile field
            return ActionUpdateThread.BGT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
            this.onRestart.run();
        }
    }

    private void showSettingsLink(@NotNull ActionLink link, boolean used, @NotNull String scope) {
        // a file the sandbox does not use (missing, or created after the start) is dimmed but still opens / creates it
        link.setForeground(used ? JBUI.CurrentTheme.Link.Foreground.ENABLED : MUTED);
        link.setToolTipText(used
            ? "Opens the " + scope + " settings file used by this sandbox"
            : "Not used by this sandbox; restart to apply after creating it");
    }

    @NotNull
    private static JComponent field(@NotNull String key, @NotNull JComponent... values) {
        final JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
        panel.setOpaque(false);
        final JBLabel label = new JBLabel(key);
        label.setForeground(MUTED);
        panel.add(label);
        for (JComponent value : values) {
            panel.add(value);
        }
        return panel;
    }

    @NotNull
    private static JBLabel value() {
        final JBLabel label = new JBLabel();
        label.setFont(JBFont.create(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().getSize() - 1)));
        label.setCopyable(true);
        return label;
    }

    /**
     * Small rounded label with a tinted background.
     */
    private static final class Pill extends JBLabel {
        private Color color;

        private Pill(@NotNull String text, @NotNull Color color) {
            super(text);
            setFont(JBFont.small().asBold());
            setBorder(JBUI.Borders.empty(1, 5));
            setPill(text, color);
        }

        private void setPill(@NotNull String text, @NotNull Color color) {
            this.color = color;
            setText(text);
            setForeground(color);
        }

        @Override
        protected void paintComponent(Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(this.color.getRed(), this.color.getGreen(), this.color.getBlue(), 46));
                final int arc = JBUI.scale(6);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
            } finally {
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Grey bar standing in for a value that is not known yet.
     */
    private static final class Skeleton extends JComponent {
        private static final Color COLOR = new JBColor(0xE4E6EB, 0x393B40);
        private final int width;

        private Skeleton(int width) {
            this.width = width;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(this.width), JBUI.scale(14));
        }

        @Override
        protected void paintComponent(Graphics g) {
            final Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(COLOR);
                final int height = JBUI.scale(10);
                g2.fillRoundRect(0, (getHeight() - height) / 2, getWidth(), height, JBUI.scale(3), JBUI.scale(3));
            } finally {
                g2.dispose();
            }
        }
    }
}
