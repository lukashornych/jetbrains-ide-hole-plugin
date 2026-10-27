package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.Alarm;
import com.intellij.util.execution.ParametersListUtil;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleCommandBuilder;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsLocator;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The sandbox form shared by <i>New Sandbox</i> and <i>Edit Favourites</i>: agent, profile, project path, settings
 * links, sandbox options, libraries, agent arguments and the live command preview for Start and Debug.
 *
 * <p>Everything that touches the file system (settings links, agent/profile suggestions, path and library checks)
 * runs debounced on a pooled thread; the results are applied on the EDT and feed {@link #validate()}.</p>
 */
final class SandboxForm implements Disposable {

    private static final int CHECK_DELAY_MS = 300;
    private static final String HOLE = "hole";

    private final Project project;
    private final Path projectRoot;
    private final Runnable onChange;
    private final Supplier<Map<String, String>> environment = CliRunner.SHELL_ENVIRONMENT;
    private final HoleSettingsLocator locator = new HoleSettingsLocator(this.environment);
    private final Alarm checkAlarm;

    private final ComboBox<String> agent = new ComboBox<>(new DefaultComboBoxModel<>(HoleSettingsLocator.BUILTIN_AGENTS.toArray(String[]::new)));
    private final ComboBox<String> profile = new ComboBox<>(new DefaultComboBoxModel<>(new String[]{""}));
    private final ProjectPathCombo path;
    private final SettingsFileLinks links;
    private final JBCheckBox withDocker = option("Docker-in-Docker sidecar", "--with-docker");
    private final JBCheckBox unrestricted = option("Unrestricted network", "-u");
    private final JBCheckBox trustProject = option("Trust project settings", "--trust-project");
    private final JBCheckBox dumpNetwork = option("Log network access on exit", "-n");
    private final JBCheckBox rebuild = option("Rebuild images", "-r");
    private final JBCheckBox updateAgents = option("Update agents before start", "--update-agents");
    private final JPanel libraryRows = new JPanel();
    private final List<LibraryRow> libraries = new ArrayList<>();
    private final JBTextField agentArgs = new JBTextField();
    private final JBTextField startPreview = preview();
    private final JBTextField debugPreview = preview();
    private final JPanel panel;

    private FormChecks checks;
    private long checkSequence;
    private String previewInstanceId = "xxxxxx";
    private String executable = HOLE;
    private boolean loading;
    /**
     * Home directory for `~`: the JVM's until the shell environment was read off the EDT (reading it on the EDT can
     * block while the IDE still loads it).
     */
    private String home = System.getProperty("user.home");

    /**
     * @param project     current project
     * @param projectRoot IDE project root, against which relative paths are checked
     * @param parent      disposes the form
     * @param onChange    called on the EDT after every change of a value or of the background checks
     */
    SandboxForm(@NotNull Project project, @NotNull Path projectRoot, @NotNull Disposable parent, @NotNull Runnable onChange) {
        this.project = project;
        this.projectRoot = projectRoot;
        this.onChange = onChange;
        Disposer.register(parent, this);
        this.checkAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
        this.path = new ProjectPathCombo(project, projectRoot);
        this.links = new SettingsFileLinks(project);

        this.agent.setEditable(true);
        this.profile.setEditable(true);
        editor(this.profile).putClientProperty("JTextField.placeholderText", "none");
        if (editor(this.profile) instanceof JBTextField field) {
            field.getEmptyText().setText("none");
        }
        this.agentArgs.getEmptyText().setText("--model opus");
        this.agentArgs.setFont(mono());
        this.libraryRows.setLayout(new BoxLayout(this.libraryRows, BoxLayout.Y_AXIS));
        this.libraryRows.setOpaque(false);

        final JPanel options = new JPanel(new GridLayout(0, 3, JBUI.scale(18), JBUI.scale(4)));
        options.setOpaque(false);
        for (JBCheckBox box : List.of(this.withDocker, this.unrestricted, this.trustProject, this.dumpNetwork, this.rebuild, this.updateAgents)) {
            options.add(box);
            box.addItemListener(event -> changed());
        }
        final JPanel librariesPanel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        librariesPanel.setOpaque(false);
        librariesPanel.add(this.libraryRows, BorderLayout.CENTER);
        final ActionLink addLibrary = new ActionLink("Add library", event -> {
            addLibraryRow(new Library("", false));
            changed();
        });
        librariesPanel.add(addLibrary, BorderLayout.SOUTH);

        final JPanel previewPanel = new JPanel(new GridBagLayout());
        previewPanel.setOpaque(false);
        addPreviewRow(previewPanel, 0, "Start", this.startPreview);
        addPreviewRow(previewPanel, 1, "Debug", this.debugPreview);

        this.panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Agent:", this.agent)
            .addLabeledComponent("Profile:", this.profile)
            .addLabeledComponent("Project path:", this.path)
            .addComponentToRightColumn(hint("<html><code>.</code> is the IDE project root. Type any absolute path, or pick a git worktree.</html>"))
            .addLabeledComponent("Hole settings:", this.links)
            .addComponent(new TitledSeparator("Sandbox"))
            .addComponent(options)
            .addLabeledComponent("Libraries:", librariesPanel, true)
            .addLabeledComponent("Agent arguments:", this.agentArgs)
            .addComponentToRightColumn(hint("<html>Passed after <code>--</code>. A debug start opens a shell instead of the agent, so it leaves these out.</html>"))
            .addComponent(new TitledSeparator("Command"))
            .addComponent(previewPanel)
            // takes the extra height of an enlarged dialog, so the form stays at the top instead of centred
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        this.panel.setBorder(JBUI.Borders.empty(4));

        final DocumentAdapter documentListener = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                changed();
            }
        };
        editor(this.agent).getDocument().addDocumentListener(documentListener);
        editor(this.profile).getDocument().addDocumentListener(documentListener);
        this.path.editor().getDocument().addDocumentListener(documentListener);
        this.agentArgs.getDocument().addDocumentListener(documentListener);

        this.path.loadWorktrees(this);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final String located = HoleExecutable.getInstance().locate().map(Path::toString).orElse(HOLE);
            final String shellHome = this.environment.get().get("HOME");
            // any(): the dialog's modality may not be entered yet when this was scheduled; only own components change
            ApplicationManager.getApplication().invokeLater(() -> {
                this.executable = located;
                if (shellHome != null && !shellHome.isBlank()) {
                    this.home = shellHome;
                }
                updatePreview();
            }, ModalityState.any());
        });
        setConfig(SandboxConfig.defaults());
    }

    /**
     * Returns the form component.
     */
    @NotNull
    JComponent component() {
        return this.panel;
    }

    /**
     * Returns the component to focus first.
     */
    @NotNull
    JComponent preferredFocus() {
        return this.agent;
    }

    /**
     * Shows a configuration.
     */
    void setConfig(@NotNull SandboxConfig config) {
        this.loading = true;
        try {
            setText(this.agent, config.agent());
            setText(this.profile, config.profile());
            this.path.setText(config.projectPath().isBlank() ? SandboxConfig.DEFAULT_PROJECT_PATH : config.projectPath());
            this.withDocker.setSelected(config.withDocker());
            this.unrestricted.setSelected(config.unrestrictedNetwork());
            this.trustProject.setSelected(config.trustProject());
            this.dumpNetwork.setSelected(config.dumpNetworkAccess());
            this.rebuild.setSelected(config.rebuild());
            this.updateAgents.setSelected(config.updateAgents());
            for (LibraryRow row : List.copyOf(this.libraries)) {
                removeLibraryRow(row);
            }
            config.libraries().forEach(this::addLibraryRow);
            this.agentArgs.setText(config.agentArgs());
        } finally {
            this.loading = false;
        }
        changed();
    }

    /**
     * Returns the configuration: values trimmed, `~` in the project path expanded (Hole gets the path without a
     * shell), blank library rows left out.
     */
    @NotNull
    SandboxConfig getConfig() {
        final List<Library> libs = new ArrayList<>();
        for (LibraryRow row : this.libraries) {
            libs.add(new Library(row.path.getText().trim(), row.readWrite.isSelected()));
        }
        return new SandboxConfig(
            text(this.agent).trim(),
            text(this.profile).trim(),
            SandboxFormValidator.normalizeProjectPath(this.path.getText(), this.home),
            this.withDocker.isSelected(),
            this.unrestricted.isSelected(),
            this.trustProject.isSelected(),
            this.dumpNetwork.isSelected(),
            this.rebuild.isSelected(),
            this.updateAgents.isSelected(),
            libs,
            this.agentArgs.getText()
        );
    }

    /**
     * Sets the instance ID shown in the command preview (the one a start from this form uses).
     */
    void setPreviewInstanceId(@NotNull String instanceId) {
        this.previewInstanceId = instanceId;
        updatePreview();
    }

    /**
     * Validates the current values; problems carry the component they belong to.
     */
    @NotNull
    List<ValidationInfo> validate() {
        final SandboxConfig config = getConfig();
        final List<ValidationInfo> result = new ArrayList<>();
        final List<Library> libs = config.libraries();
        for (SandboxFormValidator.Problem problem : SandboxFormValidator.validate(config, this.checks)) {
            final JComponent component = switch (problem.field()) {
                case AGENT -> this.agent;
                case PROFILE -> this.profile;
                case PROJECT_PATH -> this.path;
                case LIBRARY -> problem.index() >= 0 && problem.index() < libs.size()
                    ? this.libraries.get(problem.index()).path.getTextField()
                    : this.libraryRows;
            };
            result.add(new ValidationInfo(problem.message(), component));
        }
        return result;
    }

    @Override
    public void dispose() {
        this.checkSequence++;
    }

    private void changed() {
        if (this.loading) {
            return;
        }
        updatePreview();
        scheduleChecks();
        this.onChange.run();
    }

    private void updatePreview() {
        final SandboxConfig config = getConfig();
        this.startPreview.setText(command(() -> HoleCommandBuilder.start(this.executable, config, this.previewInstanceId)));
        this.debugPreview.setText(command(() -> HoleCommandBuilder.debug(this.executable, config, this.previewInstanceId)));
        this.startPreview.setCaretPosition(0);
        this.debugPreview.setCaretPosition(0);
    }

    @NotNull
    private static String command(@NotNull Supplier<List<String>> builder) {
        try {
            // shown as a shell would need it typed; the process gets the argument list itself
            return ParametersListUtil.join(builder.get());
        } catch (IllegalArgumentException e) {
            return "—";
        }
    }

    private void scheduleChecks() {
        final SandboxConfig config = getConfig();
        final long sequence = ++this.checkSequence;
        this.checkAlarm.cancelAllRequests();
        this.checkAlarm.addRequest(() -> {
            final HoleExecutable hole = HoleExecutable.getInstance();
            final HoleSettingsClient settings = hole.locate()
                .map(executable -> new HoleSettingsClient(executable.toString(), hole.runner()))
                .orElse(null);
            final FormChecks result = FormChecks.run(this.locator, settings, this.projectRoot, config);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (sequence == this.checkSequence) {
                    applyChecks(result);
                }
            }, ModalityState.any());
        }, CHECK_DELAY_MS);
    }

    private void applyChecks(@NotNull FormChecks result) {
        this.checks = result;
        this.links.show(
            result.globalSettings() == null ? null
                : new HoleSettingsClient.SettingsFile(result.globalSettings(), result.globalSettingsExists()),
            result.projectSettings(),
            result.projectDirectory(),
            this::scheduleChecks
        );
        this.loading = true;
        try {
            setItems(this.agent, result.agents());
            final List<String> profiles = new ArrayList<>();
            profiles.add("");
            profiles.addAll(result.profiles());
            setItems(this.profile, profiles);
        } finally {
            this.loading = false;
        }
        this.onChange.run();
    }

    private void addLibraryRow(@NotNull Library library) {
        final LibraryRow row = new LibraryRow(library);
        this.libraries.add(row);
        this.libraryRows.add(row.panel);
        this.libraryRows.revalidate();
        this.libraryRows.repaint();
    }

    private void removeLibraryRow(@NotNull LibraryRow row) {
        this.libraries.remove(row);
        this.libraryRows.remove(row.panel);
        this.libraryRows.revalidate();
        this.libraryRows.repaint();
    }

    /**
     * Replaces the combo's suggestions. The checks run after every keystroke, so the model is replaced only when the
     * suggestions changed (a new model closes the dropdown and resets the editor); the typed text, the caret and an
     * open dropdown are kept when it is replaced.
     */
    private static void setItems(@NotNull ComboBox<String> combo, @NotNull List<String> items) {
        if (!shouldReplaceItems(items(combo.getModel()), items)) {
            return;
        }
        final JTextComponent editor = editor(combo);
        final String text = editor.getText();
        final int caret = editor.getCaretPosition();
        final boolean popupVisible = combo.isPopupVisible();
        combo.setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
        setText(combo, text);
        editor.setCaretPosition(Math.min(caret, editor.getDocument().getLength()));
        if (popupVisible && combo.isShowing()) {
            combo.setPopupVisible(true);
        }
    }

    /**
     * Returns {@code true} when the suggestions differ from the ones the combo shows (order matters).
     */
    static boolean shouldReplaceItems(@NotNull List<String> current, @NotNull List<String> next) {
        return !current.equals(next);
    }

    /**
     * Returns the items of a combo model.
     */
    @NotNull
    static List<String> items(@NotNull ComboBoxModel<String> model) {
        final List<String> items = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            items.add(model.getElementAt(i));
        }
        return items;
    }

    @NotNull
    private static String text(@NotNull ComboBox<String> combo) {
        return editor(combo).getText();
    }

    private static void setText(@NotNull ComboBox<String> combo, @NotNull String text) {
        combo.getEditor().setItem(text);
    }

    @NotNull
    private static JTextComponent editor(@NotNull ComboBox<String> combo) {
        return (JTextComponent) combo.getEditor().getEditorComponent();
    }

    @NotNull
    private static JBCheckBox option(@NotNull String label, @NotNull String flag) {
        final JBCheckBox box = new JBCheckBox("<html>" + label + "<br><small><code>" + flag + "</code></small></html>");
        box.setOpaque(false);
        return box;
    }

    @NotNull
    private static JBLabel hint(@NotNull String text) {
        final JBLabel label = new JBLabel(text);
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setFont(JBFont.small());
        return label;
    }

    @NotNull
    private static JBTextField preview() {
        final JBTextField field = new JBTextField();
        field.setEditable(false);
        field.setFont(mono());
        field.setColumns(60);
        return field;
    }

    @NotNull
    private static Font mono() {
        return JBFont.create(new Font(Font.MONOSPACED, Font.PLAIN, JBFont.label().getSize()));
    }

    private static void addPreviewRow(@NotNull JPanel panel, int row, @NotNull String label, @NotNull JComponent field) {
        final GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = JBUI.insets(2, 0, 2, 10);
        final JBLabel title = new JBLabel(label);
        title.setForeground(UIUtil.getContextHelpForeground());
        panel.add(title, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = JBUI.insets(2, 0);
        panel.add(field, c);
    }

    /**
     * One `--library` row: path, `:rw` and remove.
     */
    private final class LibraryRow {
        private final JPanel panel = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        private final TextFieldWithBrowseButton path = new TextFieldWithBrowseButton();
        private final JBCheckBox readWrite = new JBCheckBox(":rw");

        private LibraryRow(@Nullable Library library) {
            this.panel.setOpaque(false);
            this.panel.setBorder(JBUI.Borders.emptyBottom(4));
            this.path.addBrowseFolderListener(SandboxForm.this.project, FileChooserDescriptorFactory.singleDir().withTitle("Library"));
            this.path.getTextField().setFont(mono());
            this.readWrite.setToolTipText("Mount writable (PATH:rw); read-only otherwise");
            this.readWrite.setOpaque(false);
            if (library != null) {
                this.path.setText(library.path());
                this.readWrite.setSelected(library.readWrite());
            }
            final InplaceButton remove = new InplaceButton("Remove library", AllIcons.General.Remove, event -> {
                removeLibraryRow(this);
                changed();
            });
            final JPanel east = new JPanel(new BorderLayout(JBUI.scale(6), 0));
            east.setOpaque(false);
            east.add(this.readWrite, BorderLayout.WEST);
            east.add(remove, BorderLayout.EAST);
            this.panel.add(this.path, BorderLayout.CENTER);
            this.panel.add(east, BorderLayout.EAST);
            this.path.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent e) {
                    changed();
                }
            });
            this.readWrite.addItemListener(event -> changed());
        }
    }
}
