package io.github.lukashornych.hole.idea.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.WrapLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The small header above a sandbox terminal: instance ID, Debug Shell pill, Docker, settings, project path and
 * uptime; the stopping timer with Force Stop; or "Exited with code N" with Close. Fields Hole reports are skeleton
 * placeholders until the first metadata arrives. The network is deliberately not shown.
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
    private final JBLabel settings = value();
    private final Skeleton settingsSkeleton = new Skeleton(150);
    private final JComponent projectField;
    private final JBLabel project = value();
    private final Skeleton projectSkeleton = new Skeleton(180);
    private final JComponent uptimeField;
    private final JBLabel uptime = value();
    private final JBLabel status = new JBLabel();
    private final JButton forceStop = new JButton("Force Stop");
    private final JBLabel forceStopHint = new JBLabel("Closes the tab; Hole's teardown continues in the background.");
    private final JButton close = new JButton("Close");

    /**
     * @param onForceStop called when Force Stop is pressed
     * @param onClose     called when Close is pressed
     */
    SandboxHeaderPanel(@NotNull Runnable onForceStop, @NotNull Runnable onClose) {
        super(new WrapLayout(FlowLayout.LEFT, JBUI.scale(16), JBUI.scale(4)));
        setBorder(JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.borderColor()), JBUI.Borders.empty(2, 6)));
        add(field("Instance", this.instance));
        add(this.debugPill);
        this.dockerField = field("Docker", this.dockerPill, this.dockerSkeleton);
        this.settingsField = field("Settings", this.settings, this.settingsSkeleton);
        this.projectField = field("Project", this.project, this.projectSkeleton);
        this.uptimeField = field("Up", this.uptime);
        add(this.dockerField);
        add(this.settingsField);
        add(this.projectField);
        add(this.uptimeField);
        this.status.setForeground(ERROR);
        add(this.status);
        this.forceStop.setToolTipText("Sends SIGKILL to the Hole process and closes the tab. Hole's watchdog keeps "
            + "tearing the sandbox down in the background.");
        this.forceStop.addActionListener(event -> onForceStop.run());
        add(this.forceStop);
        this.forceStopHint.setForeground(MUTED);
        this.forceStopHint.setFont(JBFont.small());
        add(this.forceStopHint);
        this.close.addActionListener(event -> onClose.run());
        add(this.close);
    }

    /**
     * Shows the model.
     */
    void render(@NotNull HeaderModel model) {
        this.instance.setText(model.instanceId());
        this.debugPill.setVisible(model.debug());

        final boolean fields = model.pending() || model.metadata();
        this.dockerField.setVisible(fields);
        this.settingsField.setVisible(fields);
        this.projectField.setVisible(fields);
        this.uptimeField.setVisible(model.metadata() && !model.uptime().isEmpty());

        this.dockerSkeleton.setVisible(model.pending());
        this.settingsSkeleton.setVisible(model.pending());
        this.projectSkeleton.setVisible(model.pending());
        this.dockerPill.setVisible(model.metadata());
        this.settings.setVisible(model.metadata());
        this.project.setVisible(model.metadata());
        this.dockerPill.setPill(model.docker() ? "ON" : "NO", model.docker() ? OK : MUTED);
        this.settings.setText(model.settings());
        this.project.setText(model.project());
        this.project.setToolTipText(model.project());
        this.uptime.setText(model.uptime());

        final String text = model.exited() != null ? model.exited() : model.stopping();
        this.status.setText(text == null ? "" : text);
        this.status.setVisible(text != null);
        this.forceStop.setVisible(model.forceStop());
        this.forceStopHint.setVisible(model.forceStop());
        this.close.setVisible(model.exited() != null);
        revalidate();
        repaint();
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
