package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
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
 * Links to Hole's settings files, at the paths Hole reports (`hole settings get`): the global one when Hole could tell
 * where it is (when it does not exist yet, its path is shown dimmed with "(not created)" and without actions; the
 * plugin never creates it), the project one only when it exists for the selected project path. <i>Open</i> closes the dialog and the dialogs it was opened from (like Cancel) and opens the file in an IDE editor, because an editor opened
 * behind a modal dialog cannot be used; the icon next to it opens the file in the system's default application and
 * keeps the dialog open. The plugin has no GUI for the settings content, because Hole's settings keys change
 * often.
 */
final class SettingsFileLinks extends JPanel {

    private static final Logger LOG = Logger.getInstance(SettingsFileLinks.class);

    private final Project project;
    private final Row global;
    private final Row projectRow;

    SettingsFileLinks(@NotNull Project project) {
        super(new GridBagLayout());
        this.project = project;
        setOpaque(false);
        this.global = new Row("Global", 0);
        this.projectRow = new Row("Project", 1);
        this.global.setVisible(false);
        this.projectRow.setVisible(false);
    }

    /**
     * Shows the files found by the last checks.
     *
     * @param globalFile    the global settings file, {@code null} when Hole could not tell (row hidden)
     * @param globalExists  whether it exists; otherwise the path is shown as "(not created)" without actions
     * @param projectFile   the project settings file, {@code null} when it does not exist
     */
    void show(@Nullable Path globalFile, boolean globalExists, @Nullable Path projectFile) {
        if (globalFile == null) {
            this.global.setVisible(false);
        } else {
            this.global.show(globalFile, globalExists);
            this.global.setVisible(true);
        }
        if (projectFile == null) {
            this.projectRow.setVisible(false);
        } else {
            this.projectRow.show(projectFile, true);
            this.projectRow.setVisible(true);
        }
        revalidate();
        repaint();
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
     * One file: label, path, Open link and external icon.
     */
    private final class Row {
        private final JBLabel label;
        private final JBLabel path = new JBLabel();
        private final ActionLink open;
        private final InplaceButton external;
        private Path file;
        private boolean exists;

        private Row(@NotNull String name, int row) {
            this.label = new JBLabel(name);
            this.label.setForeground(UIUtil.getContextHelpForeground());
            this.path.setFont(JBFont.create(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBFont.label().getSize() - 1)));
            this.open = new ActionLink("Open", event -> {
                if (this.file != null) {
                    openInEditor(this.file);
                }
            });
            this.external = new InplaceButton("Open in the system's default application", AllIcons.Ide.External_link_arrow, event -> {
                if (this.file != null) {
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
            c.gridx = 3;
            c.insets = JBUI.insets(1, 0);
            add(this.external, c);
        }

        private void show(@NotNull Path file, boolean exists) {
            this.file = exists ? file : null;
            this.exists = exists;
            this.path.setText(pathText(file, exists));
            this.path.setToolTipText(exists ? file.toString() : file + " does not exist; create it with `hole settings create`");
            this.path.setForeground(exists ? UIUtil.getLabelForeground() : UIUtil.getContextHelpForeground());
            this.open.setToolTipText("Closes the dialog without saving and opens the file in the editor");
        }

        private void setVisible(boolean visible) {
            this.label.setVisible(visible);
            this.path.setVisible(visible);
            this.open.setVisible(visible && this.exists);
            this.external.setVisible(visible && this.exists);
        }
    }
}
