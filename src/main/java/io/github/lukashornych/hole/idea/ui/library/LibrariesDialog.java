package io.github.lukashornych.hole.idea.ui.library;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.session.SessionState;
import io.github.lukashornych.hole.idea.ui.settings.SettingsDocuments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Non-modal window that lists and edits the top-level `libraries` of the sandbox's global and project settings files
 * ({@link LibraryFiles}), plus the read-only `--library` values of this start. Hole cannot change the mounts of a
 * running sandbox, so every change applies on the next start: rows that differ from the snapshot taken when the
 * window was first opened for this instance are marked, and closing the window after a change offers the restart
 * (while the sandbox runs) or says how to apply it (once it has exited). The window closed by the sandbox tab asks
 * nothing.
 */
public final class LibrariesDialog extends DialogWrapper {

    /** Note under the table: what the window does not list. */
    static final String NOT_LISTED = "Libraries of profiles and git worktree links are not listed. Changes apply when the sandbox starts again.";
    /** Hint for a selected `--library` row. */
    static final String THIS_START_HINT = "Given with --library for this start — read-only here";
    /** Hint for a selected library that left the files. */
    static final String REMOVED_HINT = "Removed from the settings — still mounted until the sandbox restarts";
    /** Shown after a change once the sandbox has exited. */
    static final String EXITED_HINT = "The sandbox has exited: press Restart in the sandbox header to start it with the changed libraries.";

    private final LibraryFiles files;
    private final List<Library> thisStart;
    /** File libraries when the window was first opened for this instance; kept by the tab, {@code null} until taken. */
    private final AtomicReference<List<SettingsLibraries.Library>> snapshot;
    private final String instanceId;
    private final Supplier<SessionState> state;
    private final Runnable onRestart;
    private final LibraryTableModel model = new LibraryTableModel();
    private final JBTable table = new JBTable(this.model);
    private final JButton add = new JButton("Add…");
    private final JButton edit = new JButton("Edit…");
    private final JButton remove = new JButton("Remove");
    private final JButton refresh = new JButton("Refresh");
    private final JBLabel banner = new JBLabel();
    private final JBLabel hint = new JBLabel(" ");
    private final JBLabel exitedHint = new JBLabel(EXITED_HINT);
    /** A library was added, changed or removed in this window: closing it offers the restart. EDT only. */
    private boolean changed;
    /** Calls in flight. EDT only. */
    private int running;

    /**
     * @param project    the IDE project
     * @param documents  the sandbox's settings files
     * @param thisStart  the `--library` rows of the sandbox's start
     * @param snapshot   the file libraries of this instance, kept by the sandbox tab so a reopened window still marks
     *                   the changes; filled by the first load when empty
     * @param instanceId the sandbox instance; the window closes when the tab gets another one
     * @param title      e.g. {@code Libraries · my-project · claude (k3x9a2)}
     * @param state      the sandbox's current state (a restart is offered only while it runs)
     * @param onRestart  asks for and runs the restart of the running sandbox
     */
    public LibrariesDialog(
        @NotNull Project project,
        @NotNull SettingsDocuments documents,
        @NotNull List<Library> thisStart,
        @NotNull AtomicReference<List<SettingsLibraries.Library>> snapshot,
        @NotNull String instanceId,
        @NotNull String title,
        @NotNull Supplier<SessionState> state,
        @NotNull Runnable onRestart
    ) {
        super(project, true);
        this.files = new LibraryFiles(documents, Path.of(System.getProperty("user.home")));
        this.thisStart = List.copyOf(thisStart);
        this.snapshot = snapshot;
        this.instanceId = instanceId;
        this.state = state;
        this.onRestart = onRestart;
        setModal(false);
        setTitle(title);
        setOKButtonText("Close");
        init();
        load();
    }

    /**
     * Returns the instance the window belongs to.
     */
    @NotNull
    public String instanceId() {
        return this.instanceId;
    }

    /**
     * The sandbox has exited: changes now apply only on a new start, so the window says how.
     */
    public void sandboxExited() {
        this.exitedHint.setVisible(afterChange(this.changed, SessionState.EXITED) == AfterChange.EXITED_HINT);
    }

    /**
     * What a change made in this window leads to.
     */
    enum AfterChange {
        /** Nothing to apply, or the sandbox is starting or stopping. */
        NONE,
        /** The usual Restart Sandbox confirmation when the user closes the window. */
        OFFER_RESTART,
        /** No question; the window says how to start the exited sandbox again. */
        EXITED_HINT
    }

    /**
     * Returns what follows a change, by the sandbox's state.
     *
     * @param changed a library was added, changed or removed in this window
     */
    @NotNull
    static AfterChange afterChange(boolean changed, @NotNull SessionState state) {
        if (!changed) {
            return AfterChange.NONE;
        }
        return switch (state) {
            case RUNNING -> AfterChange.OFFER_RESTART;
            case EXITED -> AfterChange.EXITED_HINT;
            default -> AfterChange.NONE;
        };
    }

    @Override
    protected void doOKAction() {
        final boolean restart = this.changed;
        super.doOKAction();
        offerRestart(restart);
    }

    @Override
    public void doCancelAction() {
        final boolean restart = this.changed;
        super.doCancelAction();
        offerRestart(restart);
    }

    /**
     * After the user closed the window (Close, Esc, the title bar), not when the sandbox tab closes it: the changes
     * apply on a restart, offered once for all of them with the usual confirmation. An exited sandbox gets no question;
     * the window already said how to start it again.
     */
    private void offerRestart(boolean restart) {
        if (isDisposed() && afterChange(restart, this.state.get()) == AfterChange.OFFER_RESTART) {
            this.onRestart.run();
        }
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        this.table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.table.getSelectionModel().addListSelectionListener(event -> updateActions());
        this.table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && LibrariesDialog.this.edit.isEnabled()) {
                    LibrariesDialog.this.edit.doClick();
                }
            }
        });
        this.add.addActionListener(event -> openAdd());
        this.edit.addActionListener(event -> selected().ifPresent(this::openEdit));
        this.remove.addActionListener(event -> selected().ifPresent(this::removeLibrary));
        this.refresh.addActionListener(event -> load());
        this.banner.setForeground(JBColor.namedColor("Label.errorForeground", new JBColor(0xD1323F, 0xF27C7C)));
        this.banner.setAllowAutoWrapping(true);
        this.banner.setVisible(false);
        this.hint.setForeground(UIUtil.getContextHelpForeground());
        this.exitedHint.setVisible(false);
        final JBLabel note = new JBLabel(NOT_LISTED);
        note.setForeground(UIUtil.getContextHelpForeground());
        final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        buttons.add(this.add);
        buttons.add(this.edit);
        buttons.add(this.remove);
        buttons.add(this.refresh);
        final JPanel south = new JPanel();
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(this.hint);
        south.add(this.exitedHint);
        south.add(note);
        south.add(buttons);
        for (JComponent component : List.of(this.hint, this.exitedHint, note, buttons)) {
            component.setAlignmentX(0);
        }
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.add(this.banner, BorderLayout.NORTH);
        panel.add(ScrollPaneFactory.createScrollPane(this.table), BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(JBUI.scale(720), JBUI.scale(360)));
        return panel;
    }

    /**
     * Runs a blocking call off the EDT and hands its result to {@code then} on the EDT, unless the window is gone.
     */
    private <T> void call(@NotNull Supplier<T> call, @NotNull Consumer<T> then) {
        this.running++;
        updateActions();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            T result = null;
            RuntimeException failure = null;
            try {
                result = call.get();
            } catch (RuntimeException e) {
                failure = e;
            }
            final T value = result;
            final RuntimeException error = failure;
            ApplicationManager.getApplication().invokeLater(() -> {
                this.running--;
                if (isDisposed()) {
                    return;
                }
                if (error != null) {
                    showBanner(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                } else {
                    then.accept(value);
                }
                updateActions();
            }, ModalityState.any());
        });
    }

    private void load() {
        this.table.getEmptyText().setText("Loading…");
        call(this.files::load, loaded -> {
            if (loaded.problems().isEmpty()) {
                // a file read as empty would make every library of the repaired file look new
                this.snapshot.compareAndSet(null, loaded.libraries());
            }
            this.model.set(LibraryTableModel.rows(loaded.libraries(), this.snapshot.get(), this.thisStart, this.files.resolver()));
            this.table.getEmptyText().setText("No libraries");
            if (loaded.problems().isEmpty()) {
                this.banner.setVisible(false);
            } else {
                showBanner(String.join(" ", loaded.problems()));
            }
        });
    }

    private void showBanner(@NotNull String text) {
        this.banner.setText(text);
        this.banner.setVisible(true);
    }

    /**
     * A file changed: closing offers the restart; an exited sandbox gets the hint at once.
     */
    private void changedFiles() {
        this.changed = true;
        load();
        if (afterChange(true, this.state.get()) == AfterChange.EXITED_HINT) {
            this.exitedHint.setVisible(true);
        }
    }

    private void openAdd() {
        LibraryInputDialog.add(getContentPanel(), this.files::add, this::changedFiles).show();
    }

    private void openEdit(@NotNull LibraryTableModel.Row row) {
        final HoleSettingsClient.Scope scope = row.source().scope();
        if (!row.editable() || scope == null) {
            return;
        }
        LibraryInputDialog.edit(getContentPanel(), row, scope,
            (target, hostPath, mountPath, readWrite, modality) -> this.files.change(target, hostPath, mountPath, readWrite, modality),
            this::changedFiles).show();
    }

    /**
     * Returns the confirmation of a Remove: the file it leaves, and what that means.
     */
    @NotNull
    static String removeQuestion(@NotNull LibraryTableModel.Row row, @NotNull HoleSettingsClient.Scope scope) {
        final StringBuilder text = new StringBuilder("Remove ").append(row.hostPath()).append(" from the ")
            .append(scope.label()).append(" settings file?");
        if (scope == HoleSettingsClient.Scope.GLOBAL) {
            text.append("\n\nThis affects every project.");
        } else {
            if (row.source() == LibraryTableModel.Source.PROJECT_OVERRIDES_GLOBAL) {
                text.append("\n\nThe global entry for it applies again.");
            }
            text.append("\n\n").append(LibraryInputDialog.PROJECT_TRUST_HINT).append('.');
        }
        text.append("\nThe library stays mounted until the sandbox restarts.");
        return text.toString();
    }

    private void removeLibrary(@NotNull LibraryTableModel.Row row) {
        final HoleSettingsClient.Scope scope = row.source().scope();
        if (!row.editable() || scope == null) {
            return;
        }
        if (!MessageDialogBuilder.yesNo("Remove Library", removeQuestion(row, scope))
            .yesText("Remove")
            .noText("Cancel")
            .ask(getContentPanel())) {
            return;
        }
        final ModalityState modality = ModalityState.stateForComponent(getContentPanel());
        call(() -> this.files.remove(scope, row.hostPath(), modality), failure -> {
            if (failure.isPresent()) {
                showBanner("Not removed: " + failure.get());
                load();
            } else {
                this.banner.setVisible(false);
                changedFiles();
            }
        });
    }

    @NotNull
    private Optional<LibraryTableModel.Row> selected() {
        final int row = this.table.getSelectedRow();
        return row < 0 ? Optional.empty() : Optional.of(this.model.row(this.table.convertRowIndexToModel(row)));
    }

    /**
     * Returns the hint under the table for the selected row.
     */
    @NotNull
    static String hint(@Nullable LibraryTableModel.Row row) {
        if (row == null) {
            return " ";
        }
        if (row.source() == LibraryTableModel.Source.THIS_START) {
            return THIS_START_HINT;
        }
        if (row.change() == LibraryTableModel.Change.REMOVED) {
            return REMOVED_HINT;
        }
        return row.warning().isEmpty() ? " " : row.warning();
    }

    private void updateActions() {
        final Optional<LibraryTableModel.Row> row = selected();
        final boolean idle = this.running == 0;
        final boolean editable = row.map(LibraryTableModel.Row::editable).orElse(false);
        this.add.setEnabled(idle);
        this.edit.setEnabled(idle && editable);
        this.remove.setEnabled(idle && editable);
        this.refresh.setEnabled(idle);
        this.hint.setText(hint(row.orElse(null)));
    }
}
