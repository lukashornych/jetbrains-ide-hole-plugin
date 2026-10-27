package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.awt.Desktop;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Links to Hole's settings files, at the paths Hole reports (`hole settings get`): the global one and the project one
 * of the selected project path, each shown when Hole could tell where it is. An existing file has <i>Open</i>, which
 * closes the dialog and the dialogs it was opened from (like Cancel) and opens the file in an IDE editor, because an
 * editor opened behind a modal dialog cannot be used; the icon next to it opens the file in the system's default
 * application and keeps the dialog open. A missing file is shown dimmed with "(not created)" and a <i>Create</i> link:
 * after a confirmation, `hole settings create` writes Hole's template (see {@link SettingsFileResolver}), the dialog
 * stays open and the form re-runs its checks, so the row switches to <i>Open</i>. The plugin has no GUI for the
 * settings content, because Hole's settings keys change often.
 */
final class SettingsFileLinks extends JPanel {

    private static final Logger LOG = Logger.getInstance(SettingsFileLinks.class);

    private final Project project;
    private final Row global;
    private final Row projectRow;
    /** Re-runs the form's checks after a file was created. EDT only. */
    private Runnable onCreated = () -> {
    };

    SettingsFileLinks(@NotNull Project project) {
        super(new GridBagLayout());
        this.project = project;
        setOpaque(false);
        this.global = new Row("Global", HoleSettingsClient.Scope.GLOBAL, 0);
        this.projectRow = new Row("Project", HoleSettingsClient.Scope.PROJECT, 1);
        this.global.show(null, null);
        this.projectRow.show(null, null);
    }

    /**
     * Shows the files found by the last checks.
     *
     * @param globalFile       the global settings file, {@code null} when Hole could not tell (row hidden)
     * @param projectFile      the project settings file, {@code null} when Hole could not tell (row hidden)
     * @param projectDirectory the resolved project directory the project file belongs to (for <i>Create</i>)
     * @param onCreated        called on the EDT after a file was created, to re-run the checks
     */
    void show(
        @Nullable HoleSettingsClient.SettingsFile globalFile,
        @Nullable HoleSettingsClient.SettingsFile projectFile,
        @Nullable Path projectDirectory,
        @NotNull Runnable onCreated
    ) {
        this.onCreated = onCreated;
        this.global.show(globalFile, null);
        this.projectRow.show(projectDirectory == null ? null : projectFile, projectDirectory);
        revalidate();
        repaint();
    }

    /**
     * Returns what a row offers for a file: <i>Open</i> for an existing one, <i>Create</i> for a missing one, nothing
     * (row hidden) when Hole could not tell.
     */
    @NotNull
    static RowAction actionFor(@Nullable HoleSettingsClient.SettingsFile file) {
        if (file == null) {
            return RowAction.HIDDEN;
        }
        return file.exists() ? RowAction.OPEN : RowAction.CREATE;
    }

    /**
     * What a settings file row offers.
     */
    enum RowAction {
        /** The file exists: Open and the external icon. */
        OPEN,
        /** The file is missing: "(not created)" and Create. */
        CREATE,
        /** Hole could not tell where the file is: the row is hidden. */
        HIDDEN
    }

    /**
     * Returns the text shown for a settings file: the path relative to the home directory, followed by
     * "(not created)" when the file does not exist.
     */
    @NotNull
    static String pathText(@NotNull Path file, boolean exists) {
        final String location = FileUtil.getLocationRelativeToUserHome(file.toString());
        return exists ? location : location + " (not created)";
    }

    private void openInEditor(@NotNull Path file) {
        // every dialog of the chain (e.g. Edit Favourites opened from New Sandbox), else the editor stays blocked
        DialogWrapper dialog = DialogWrapper.findInstance(this);
        while (dialog != null) {
            final Window owner = dialog.getWindow().getOwner();
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE);
            dialog = owner == null ? null : DialogWrapper.findInstance(owner);
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
            if (virtualFile == null) {
                return;
            }
            // nonModal(): opens once no modal dialog is left (e.g. New Sandbox below Edit Favourites)
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!this.project.isDisposed()) {
                    // the file may be gone by now (the runnable waits for modal dialogs to close)
                    final OpenFileDescriptor descriptor = new OpenFileDescriptor(this.project, virtualFile);
                    if (descriptor.canNavigate()) {
                        descriptor.navigate(true);
                    }
                }
            }, ModalityState.nonModal());
        });
    }

    /**
     * Create link: asks, then runs `hole settings create` off the EDT and refreshes the rows through the form.
     */
    private void create(@NotNull HoleSettingsClient.Scope scope, @NotNull Path file, @Nullable Path directory) {
        final boolean confirmed = MessageDialogBuilder.yesNo(
                "Create Hole Settings File",
                "The " + scope.label() + " settings file " + file + " does not exist. Create it from Hole's template?")
            .yesText("Create")
            .noText("Cancel")
            .ask(this);
        if (!confirmed) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleExecutable hole = HoleExecutable.getInstance();
            final SettingsFileResolver.Result result = hole.locate()
                .map(executable -> new SettingsFileResolver(new HoleSettingsClient(executable.toString(), hole.runner())))
                .map(resolver -> resolver.create(scope, directory))
                .orElseGet(() -> new SettingsFileResolver.Result(SettingsFileResolver.Outcome.FAILED, null, "Hole was not found"));
            ApplicationManager.getApplication().invokeLater(() -> {
                if (result.openable()) {
                    this.onCreated.run();
                } else if (isShowing()) {
                    Messages.showErrorDialog(this, "Hole could not create the settings file: " + result.message(),
                        "Create Hole Settings File");
                }
            }, ModalityState.any());
        });
    }

    private static void openExternally(@NotNull Path file) {
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(file.toFile());
                } else {
                    BrowserUtil.browse(file);
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("Could not open " + file + " in the default application: " + e.getMessage());
            }
        });
    }

    /**
     * One file: label, path, and Open link with external icon, or Create link.
     */
    private final class Row {
        private final JBLabel label;
        private final JBLabel path = new JBLabel();
        private final ActionLink open;
        private final ActionLink createLink;
        private final InplaceButton external;
        private Path file;
        private Path directory;
        private RowAction action = RowAction.HIDDEN;

        private Row(@NotNull String name, @NotNull HoleSettingsClient.Scope scope, int row) {
            this.label = new JBLabel(name);
            this.label.setForeground(UIUtil.getContextHelpForeground());
            this.path.setFont(JBFont.create(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().getSize() - 1)));
            this.open = new ActionLink("Open", event -> {
                if (this.file != null && this.action == RowAction.OPEN) {
                    openInEditor(this.file);
                }
            });
            this.open.setToolTipText("Closes the dialog without saving and opens the file in the editor");
            this.createLink = new ActionLink("Create", event -> {
                if (this.file != null && this.action == RowAction.CREATE) {
                    create(scope, this.file, this.directory);
                }
            });
            this.createLink.setToolTipText("Creates the file from Hole's template (hole settings create); the dialog stays open");
            this.external = new InplaceButton("Open in the system's default application", AllIcons.Ide.External_link_arrow, event -> {
                if (this.file != null && this.action == RowAction.OPEN) {
                    openExternally(this.file);
                }
            });
            final GridBagConstraints c = new GridBagConstraints();
            c.gridy = row;
            c.insets = JBUI.insets(1, 0, 1, 10);
            c.anchor = GridBagConstraints.WEST;
            c.gridx = 0;
            add(this.label, c);
            c.gridx = 1;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            add(this.path, c);
            c.gridx = 2;
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            add(this.open, c);
            add(this.createLink, c);
            c.gridx = 3;
            c.insets = JBUI.insets(1, 0);
            add(this.external, c);
        }

        private void show(@Nullable HoleSettingsClient.SettingsFile settingsFile, @Nullable Path projectDirectory) {
            this.action = actionFor(settingsFile);
            this.file = settingsFile == null ? null : settingsFile.path();
            this.directory = projectDirectory;
            if (settingsFile != null) {
                final boolean exists = settingsFile.exists();
                this.path.setText(pathText(settingsFile.path(), exists));
                this.path.setToolTipText(exists ? settingsFile.path().toString() : settingsFile.path() + " does not exist");
                this.path.setForeground(exists ? UIUtil.getLabelForeground() : UIUtil.getContextHelpForeground());
            }
            final boolean visible = this.action != RowAction.HIDDEN;
            this.label.setVisible(visible);
            this.path.setVisible(visible);
            this.open.setVisible(this.action == RowAction.OPEN);
            this.external.setVisible(this.action == RowAction.OPEN);
            this.createLink.setVisible(this.action == RowAction.CREATE);
        }
    }
}
