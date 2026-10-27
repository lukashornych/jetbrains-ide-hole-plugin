package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import io.github.lukashornych.hole.idea.git.Worktree;
import io.github.lukashornych.hole.idea.git.WorktreeLister;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Editable project path combo: `.` by default (the IDE project root), any typed path, the git worktrees of the
 * project as suggestions (loaded in the background) and a <i>Browse…</i> item.
 */
final class ProjectPathCombo extends ComboBox<String> {

    /** Item that opens the directory chooser. */
    static final String BROWSE = "Browse…";

    private static final Logger LOG = Logger.getInstance(ProjectPathCombo.class);

    private final Project project;
    private final Path projectRoot;
    private final Map<String, String> branches = new HashMap<>();
    private String lastText = SandboxConfig.DEFAULT_PROJECT_PATH;

    ProjectPathCombo(@NotNull Project project, @NotNull Path projectRoot) {
        super(new DefaultComboBoxModel<>(new String[]{BROWSE}));
        this.project = project;
        this.projectRoot = projectRoot;
        setEditable(true);
        setText(SandboxConfig.DEFAULT_PROJECT_PATH);
        setRenderer(new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(
                @NotNull JList<? extends String> list, String value, int index, boolean selected, boolean hasFocus
            ) {
                final String branch = value == null ? null : ProjectPathCombo.this.branches.get(value);
                append(value == null ? "" : branch == null ? value : value + "  [" + branch + "]");
            }
        });
        addActionListener(event -> {
            if (BROWSE.equals(getSelectedItem())) {
                final String previous = this.lastText;
                SwingUtilities.invokeLater(() -> {
                    setText(previous);
                    browse();
                });
            }
        });
        editor().getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                final String text = editor().getText();
                if (!BROWSE.equals(text)) {
                    ProjectPathCombo.this.lastText = text;
                }
            }
        });
    }

    /**
     * Loads the worktree suggestions with `git worktree list` in the background.
     *
     * @param parent stops applying the result once disposed
     */
    void loadWorktrees(@NotNull Disposable parent) {
        // not Disposer.isDisposed(parent), which is deprecated
        final AtomicBoolean disposed = new AtomicBoolean();
        Disposer.register(parent, () -> disposed.set(true));
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final List<Worktree> worktrees;
            try {
                worktrees = new WorktreeLister(new CliRunner(CliRunner.SHELL_ENVIRONMENT)).list(this.projectRoot);
            } catch (CliException e) {
                // not a git repository, or git missing: no suggestions
                LOG.debug("No worktree suggestions: " + e.getMessage());
                return;
            }
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed.get()) {
                    setWorktrees(worktrees);
                }
            }, ModalityState.any());
        });
    }

    /**
     * Returns the typed text.
     */
    @NotNull
    String getText() {
        final String text = editor().getText();
        return BROWSE.equals(text) ? this.lastText : text;
    }

    /**
     * Replaces the typed text.
     */
    void setText(@NotNull String text) {
        getEditor().setItem(text);
        this.lastText = text;
    }

    /**
     * The text field of the combo; listeners for typed changes go on its document.
     */
    @NotNull
    JTextComponent editor() {
        return (JTextComponent) getEditor().getEditorComponent();
    }

    private void setWorktrees(@NotNull List<Worktree> worktrees) {
        final String text = getText();
        final List<String> items = new ArrayList<>();
        this.branches.clear();
        for (Worktree worktree : worktrees) {
            if (worktree.bare()) {
                continue;
            }
            items.add(worktree.path());
            if (worktree.branch() != null) {
                this.branches.put(worktree.path(), worktree.branch());
            }
        }
        items.add(BROWSE);
        setModel(new DefaultComboBoxModel<>(items.toArray(String[]::new)));
        setText(text);
    }

    private void browse() {
        FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleDir().withTitle("Project Path"),
            this.project,
            this,
            LocalFileSystem.getInstance().findFileByNioFile(this.projectRoot),
            file -> setText(file.getPath())
        );
    }
}
