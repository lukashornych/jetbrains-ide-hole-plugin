package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.CollectionListModel;
import com.intellij.ui.ColoredListCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import io.github.lukashornych.hole.idea.storage.GlobalFavouritesService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * <i>Edit Favourites</i>: the favourites on the left, grouped <i>This project</i> / <i>All projects</i>, with add,
 * remove and copy; on the right the name, where it is stored and the same form as <i>New Sandbox</i>. OK stores
 * project favourites in `.idea/hole.xml` and the others in the IDE settings.
 */
public final class EditFavouritesDialog extends DialogWrapper {

    private final CollectionListModel<Entry> model = new CollectionListModel<>();
    private final JBList<Entry> list = new JBList<>(this.model);
    private final JBTextField name = new JBTextField();
    private final JBRadioButton projectScope = new JBRadioButton("This project (.idea/hole.xml)");
    private final JBRadioButton globalScope = new JBRadioButton("All projects (IDE settings)");
    private final SandboxForm form;
    private final Project project;
    private JPanel detail;
    private Entry current;
    private boolean loading;

    /**
     * @param project current project
     * @param toAdd   a new favourite to add and select (Save as Favourite…), {@code null} to just edit
     */
    public EditFavouritesDialog(@NotNull Project project, @Nullable Favourite toAdd) {
        super(project);
        this.project = project;
        setTitle("Edit Favourites");
        for (Favourite favourite : GlobalFavouritesService.all(project)) {
            this.model.add(new Entry(favourite));
        }
        this.form = new SandboxForm(project, SandboxSessionManager.getInstance(project).projectRoot(), false, getDisposable(), () -> { });
        final Entry added = toAdd == null ? null : new Entry(toAdd);
        if (added != null) {
            this.model.add(added);
        }
        sort();
        init();
        initValidation();
        if (added != null) {
            this.list.setSelectedValue(added, true);
        } else if (this.model.getSize() > 0) {
            this.list.setSelectedIndex(0);
        }
        updateDetail();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        this.list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.list.getEmptyText().setText("No favourites");
        this.list.setCellRenderer(new EntryRenderer());
        this.list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !this.loading) {
                selectEntry(this.list.getSelectedValue());
            }
        });
        final JPanel listPanel = ToolbarDecorator.createDecorator(this.list)
            .setAddAction(button -> {
                commit();
                addEntry(new Entry(new Favourite("New favourite", FavouriteScope.PROJECT, SandboxConfig.defaults())));
            })
            .setRemoveAction(button -> {
                final Entry selected = this.list.getSelectedValue();
                if (selected != null) {
                    final int index = this.model.getElementIndex(selected);
                    this.current = null;
                    this.model.remove(selected);
                    if (this.model.getSize() > 0) {
                        this.list.setSelectedIndex(Math.min(index, this.model.getSize() - 1));
                    }
                    selectEntry(this.list.getSelectedValue());
                }
            })
            .addExtraAction(new DumbAwareAction("Copy", "Copy the selected favourite", AllIcons.Actions.Copy) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    commit();
                    final Entry selected = EditFavouritesDialog.this.list.getSelectedValue();
                    if (selected != null) {
                        final Entry copy = new Entry(selected.toFavourite().withName(selected.name + " (copy)"));
                        addEntry(copy);
                    }
                }

                @Override
                public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(EditFavouritesDialog.this.list.getSelectedValue() != null);
                }

                @Override
                public @NotNull ActionUpdateThread getActionUpdateThread() {
                    return ActionUpdateThread.EDT;
                }
            })
            .disableUpDownActions()
            .createPanel();
        listPanel.setPreferredSize(JBUI.size(250, 420));

        final ButtonGroup scopes = new ButtonGroup();
        scopes.add(this.projectScope);
        scopes.add(this.globalScope);
        final JPanel scopePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(14), 0));
        scopePanel.setOpaque(false);
        scopePanel.add(this.projectScope);
        scopePanel.add(this.globalScope);
        this.projectScope.addItemListener(event -> scopeChanged());
        this.name.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (!EditFavouritesDialog.this.loading && EditFavouritesDialog.this.current != null) {
                    EditFavouritesDialog.this.current.name = EditFavouritesDialog.this.name.getText();
                    EditFavouritesDialog.this.list.repaint();
                }
            }
        });

        final JBLabel hint = new JBLabel("The project path is chosen at start: the project root, or a git worktree of it when there is one.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        this.detail = new JPanel(new BorderLayout());
        this.detail.add(FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", this.name)
            .addLabeledComponent("Stored in:", scopePanel)
            .addComponentToRightColumn(hint)
            .getPanel(), BorderLayout.NORTH);
        this.detail.add(this.form.component(), BorderLayout.CENTER);
        this.detail.setBorder(JBUI.Borders.emptyLeft(10));

        final JBSplitter splitter = new JBSplitter(false, "Hole.EditFavourites.splitter", 0.28f);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(this.detail);
        return splitter;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "Hole.EditFavouritesDialog";
    }

    /**
     * Validates the selected favourite with its form (including the background file checks). The others are checked
     * on OK, see {@link #doOKAction()}.
     */
    @Override
    protected @NotNull List<ValidationInfo> doValidateAll() {
        final List<ValidationInfo> problems = new ArrayList<>();
        if (this.current != null) {
            if (this.name.getText().isBlank()) {
                problems.add(new ValidationInfo("Enter a name.", this.name));
            }
            problems.addAll(this.form.validate());
        }
        return problems;
    }

    /**
     * Saves all favourites, unless one of them is invalid: then that one is selected and its problems are shown.
     */
    @Override
    protected void doOKAction() {
        commit();
        final List<Favourite> favourites = this.model.getItems().stream().map(Entry::toFavourite).toList();
        final int invalid = firstInvalid(favourites);
        if (invalid >= 0) {
            final Entry entry = this.model.getElementAt(invalid);
            this.list.setSelectedValue(entry, true);
            selectEntry(entry);
            final List<ValidationInfo> problems = doValidateAll();
            updateErrorInfo(problems);
            if (!problems.isEmpty() && problems.get(0).component != null) {
                problems.get(0).component.requestFocusInWindow();
            }
            return;
        }
        GlobalFavouritesService.saveAll(this.project, favourites);
        super.doOKAction();
    }

    /**
     * Returns the index of the first favourite with a problem that can be found without file access (blank name,
     * invalid agent or profile name), or -1 when all are valid. Library paths are checked only for the selected
     * favourite, by its form.
     */
    static int firstInvalid(@NotNull List<Favourite> favourites) {
        for (int i = 0; i < favourites.size(); i++) {
            final Favourite favourite = favourites.get(i);
            if (favourite.name().isBlank() || !SandboxFormValidator.validate(favourite.config(), null).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    private void addEntry(@NotNull Entry entry) {
        this.model.add(entry);
        sort();
        this.list.setSelectedValue(entry, true);
        selectEntry(entry);
    }

    private void selectEntry(@Nullable Entry entry) {
        if (entry == this.current) {
            return;
        }
        commit();
        this.current = entry;
        this.loading = true;
        try {
            if (entry != null) {
                this.name.setText(entry.name);
                (entry.scope == FavouriteScope.PROJECT ? this.projectScope : this.globalScope).setSelected(true);
                this.form.setConfig(entry.config);
            }
        } finally {
            this.loading = false;
        }
        updateDetail();
    }

    /**
     * Copies the form into the selected entry.
     */
    private void commit() {
        if (this.current != null) {
            this.current.name = this.name.getText().trim();
            this.current.config = this.form.getConfig();
        }
    }

    private void scopeChanged() {
        if (this.loading || this.current == null) {
            return;
        }
        this.current.scope = this.projectScope.isSelected() ? FavouriteScope.PROJECT : FavouriteScope.GLOBAL;
        final Entry selected = this.current;
        this.loading = true;
        try {
            sort();
            this.list.setSelectedValue(selected, true);
        } finally {
            this.loading = false;
        }
    }

    private void sort() {
        // stable: the user's order within each group stays
        this.model.sort(Comparator.comparing(entry -> entry.scope));
    }

    private void updateDetail() {
        if (this.detail != null) {
            UIUtil.setEnabled(this.detail, this.current != null, true);
        }
    }

    /**
     * Returns the group caption shown above row {@code index}: when the scope differs from the previous row.
     */
    @Nullable
    static String captionAbove(@NotNull List<FavouriteScope> scopes, int index) {
        final FavouriteScope scope = scopes.get(index);
        if (index > 0 && scopes.get(index - 1) == scope) {
            return null;
        }
        return scope == FavouriteScope.PROJECT ? "This project" : "All projects";
    }

    /**
     * A favourite's row, with the group caption above the first row of each scope.
     */
    private final class EntryRenderer implements ListCellRenderer<Entry> {
        private final JPanel panel = new JPanel(new BorderLayout());
        private final TitledSeparator caption = new TitledSeparator();
        private final ColoredListCellRenderer<Entry> row = new ColoredListCellRenderer<>() {
            @Override
            protected void customizeCellRenderer(
                @NotNull JList<? extends Entry> list, Entry value, int index, boolean selected, boolean hasFocus
            ) {
                append(value.name.isBlank() ? "(unnamed)" : value.name);
            }
        };

        private EntryRenderer() {
            this.panel.setOpaque(false);
            this.panel.add(this.caption, BorderLayout.NORTH);
        }

        @Override
        public Component getListCellRendererComponent(
            JList<? extends Entry> list, Entry value, int index, boolean selected, boolean hasFocus
        ) {
            final List<FavouriteScope> scopes = EditFavouritesDialog.this.model.getItems().stream().map(e -> e.scope).toList();
            final String text = index >= 0 && index < scopes.size() ? captionAbove(scopes, index) : null;
            this.caption.setText(text == null ? "" : text);
            this.caption.setVisible(text != null);
            this.panel.add(this.row.getListCellRendererComponent(list, value, index, selected, hasFocus), BorderLayout.CENTER);
            this.panel.setToolTipText(value.config.agentSpec());
            return this.panel;
        }
    }

    /**
     * Working copy of one favourite.
     */
    private static final class Entry {
        private String name;
        private FavouriteScope scope;
        private SandboxConfig config;

        private Entry(@NotNull Favourite favourite) {
            this.name = favourite.name();
            this.scope = favourite.scope();
            this.config = favourite.config();
        }

        @NotNull
        private Favourite toFavourite() {
            return new Favourite(this.name, this.scope, this.config);
        }
    }
}
