package io.github.lukashornych.hole.idea.ui.library;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.Component;
import java.util.Optional;

/**
 * Add or Edit of a library in the Libraries window: host path (folder chooser; fixed when editing), mount path
 * (prefilled with the host path, Hole's `--library` default — the settings file needs it written out), read-write,
 * and for an add the file it is saved to. The checks that need the file system and the save run off the EDT; a
 * failure is shown in the dialog, which stays open.
 */
final class LibraryInputDialog extends DialogWrapper {

    /** Hole gates `libraries` of a project settings file behind its trust prompt. */
    static final String PROJECT_TRUST_HINT = "The next start asks you to trust the project's settings again";

    private final TextFieldWithBrowseButton host = new TextFieldWithBrowseButton();
    private final JBTextField mount = new JBTextField(30);
    private final JBCheckBox readWrite = new JBCheckBox("Read-write (mounted read-only otherwise)");
    private final JBRadioButton global = new JBRadioButton("Global settings (every project)");
    private final JBRadioButton project = new JBRadioButton("Project settings");
    private final JBLabel trustHint = new JBLabel(PROJECT_TRUST_HINT);
    private final boolean editing;
    /** The row being edited, {@code null} for an add. */
    private final LibraryTableModel.Row original;
    private final HoleSettingsClient.Scope fixedScope;
    private final Applier apply;
    private final Runnable done;
    /** The mount path still follows the host path (not typed by the user). */
    private boolean mountFollowsHost;
    private boolean applying;

    /**
     * Opens an Add.
     *
     * @param parent the Libraries window
     * @param apply  validates and saves (blocking; called off the EDT)
     * @param done   called on the EDT once the dialog closed after a save
     */
    static LibraryInputDialog add(@NotNull Component parent, @NotNull Applier apply, @NotNull Runnable done) {
        return new LibraryInputDialog(parent, null, null, apply, done);
    }

    /**
     * Opens an Edit of a file library.
     */
    static LibraryInputDialog edit(
        @NotNull Component parent, @NotNull LibraryTableModel.Row row, @NotNull HoleSettingsClient.Scope scope,
        @NotNull Applier apply, @NotNull Runnable done
    ) {
        return new LibraryInputDialog(parent, row, scope, apply, done);
    }

    private LibraryInputDialog(
        @NotNull Component parent, @Nullable LibraryTableModel.Row row, @Nullable HoleSettingsClient.Scope scope,
        @NotNull Applier apply, @NotNull Runnable done
    ) {
        super(parent, false);
        this.editing = row != null;
        this.original = row;
        this.fixedScope = scope;
        this.apply = apply;
        this.done = done;
        setTitle(this.editing ? "Edit Library" : "Add Library");
        setOKButtonText(this.editing ? "Save" : "Add");
        this.host.addBrowseFolderListener(null, FileChooserDescriptorFactory.singleDir().withTitle("Library Directory"));
        if (row != null) {
            this.host.setText(row.hostPath());
            this.host.setEditable(false);
            this.host.setButtonEnabled(false);
            this.mount.setText(row.mountPath());
            this.readWrite.setSelected(row.readWrite());
        } else {
            this.mountFollowsHost = true;
            this.host.getTextField().getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent event) {
                    if (LibraryInputDialog.this.mountFollowsHost) {
                        LibraryInputDialog.this.mount.setText(LibraryInputDialog.this.host.getText().trim());
                    }
                }
            });
            this.mount.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent event) {
                    if (LibraryInputDialog.this.mount.hasFocus()) {
                        LibraryInputDialog.this.mountFollowsHost = false;
                    }
                }
            });
        }
        final ButtonGroup group = new ButtonGroup();
        group.add(this.global);
        group.add(this.project);
        this.global.setSelected(true);
        this.global.addActionListener(event -> updateTrustHint());
        this.project.addActionListener(event -> updateTrustHint());
        this.trustHint.setForeground(UIUtil.getContextHelpForeground());
        updateTrustHint();
        init();
    }

    private void updateTrustHint() {
        this.trustHint.setVisible(scope() == HoleSettingsClient.Scope.PROJECT);
    }

    @NotNull
    private HoleSettingsClient.Scope scope() {
        if (this.fixedScope != null) {
            return this.fixedScope;
        }
        return this.project.isSelected() ? HoleSettingsClient.Scope.PROJECT : HoleSettingsClient.Scope.GLOBAL;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        final FormBuilder form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Host directory:", this.host)
            .addLabeledComponent("Mount path:", this.mount)
            .addTooltip("Where the directory appears inside the sandbox, e.g. /libs/shared-lib or the host path itself")
            .addComponent(this.readWrite);
        if (this.fixedScope == null) {
            final JPanel scopes = new JPanel();
            scopes.setLayout(new BoxLayout(scopes, BoxLayout.Y_AXIS));
            scopes.add(this.global);
            scopes.add(this.project);
            form.addLabeledComponent("Save to:", scopes);
        } else {
            form.addLabeledComponent("Saved in:", new JBLabel(this.fixedScope.label() + " settings"));
        }
        form.addComponentToRightColumn(this.trustHint);
        final JPanel panel = form.getPanel();
        panel.setPreferredSize(JBUI.size(520, -1));
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return this.editing ? this.mount : this.host.getTextField();
    }

    @Override
    protected void doOKAction() {
        if (this.applying) {
            return;
        }
        // checked here, not in doValidate(): its periodic re-validation would clear a failure reported by the save
        if (this.host.getText().isBlank()) {
            setErrorText("Choose the directory to mount", this.host.getTextField());
            return;
        }
        final Optional<String> mountProblem = SettingsLibraries.mountPathProblem(this.mount.getText());
        if (mountProblem.isPresent()) {
            setErrorText(mountProblem.get(), this.mount);
            return;
        }
        this.applying = true;
        getOKAction().setEnabled(false);
        setErrorText(null);
        final String hostPath = this.host.getText().trim();
        final String mountPath = this.mount.getText().trim();
        final boolean writable = this.readWrite.isSelected();
        final HoleSettingsClient.Scope scope = scope();
        if (this.original != null && unchanged(this.original, mountPath, writable)) {
            // nothing to write: a rewrite of an override row would still count as a change and offer a restart
            close(CANCEL_EXIT_CODE);
            return;
        }
        // the input is modal: a save that waits for the EDT must run in its modality, not after it closed
        final ModalityState modality = ModalityState.stateForComponent(getContentPanel());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Optional<String> failure;
            try {
                failure = this.apply.apply(scope, hostPath, mountPath, writable, modality);
            } catch (RuntimeException e) {
                failure = Optional.of(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            final Optional<String> result = failure;
            ApplicationManager.getApplication().invokeLater(() -> applied(result), ModalityState.any());
        });
    }

    /**
     * Returns {@code true} when an Edit keeps the values the row shows.
     */
    static boolean unchanged(@NotNull LibraryTableModel.Row row, @NotNull String mountPath, boolean readWrite) {
        return row.valid() && row.mountPath().equals(mountPath) && row.readWrite() == readWrite;
    }

    private void applied(@NotNull Optional<String> failure) {
        this.applying = false;
        if (isDisposed()) {
            return;
        }
        getOKAction().setEnabled(true);
        if (failure.isPresent()) {
            setErrorText(failure.get());
            return;
        }
        close(OK_EXIT_CODE);
        this.done.run();
    }

    /**
     * Validates and saves a library; blocking, called off the EDT.
     */
    @FunctionalInterface
    interface Applier {
        /**
         * @param scope     the settings file
         * @param hostPath  the host path as typed (the literal key)
         * @param mountPath the container path
         * @param readWrite mounted read-write
         * @param modality  modality of the input, for work that has to wait for the EDT
         * @return the reason it failed, or nothing on success
         */
        @NotNull
        Optional<String> apply(
            @NotNull HoleSettingsClient.Scope scope, @NotNull String hostPath, @NotNull String mountPath, boolean readWrite,
            @NotNull ModalityState modality
        );
    }
}
