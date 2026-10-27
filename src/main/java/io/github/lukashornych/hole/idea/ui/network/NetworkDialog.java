package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Action;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Non-modal window like Hole's `hole modify` TUI for one running sandbox: <i>Allowed</i> (`network.allow`),
 * <i>Host gateway</i> (`network.hostGatewayDomains`) and <i>Blocked</i> (`network.allow list-blocked`) with quick
 * allow prefills (see {@link BlockedSuggestions}).
 *
 * <p>Unlike the TUI, every confirmed add or remove is applied at once (one `hole modify … add|remove v1 v2…` call, one
 * gateway apply): the CLI cannot apply additions and removals together, so staging would not be atomic anyway. The
 * entry lists load on open and after each change; the blocked list on open, on Refresh and after each add (no timer:
 * `list-blocked` reads the gateway's whole log). Calls run off the EDT; actions are disabled while one runs. Only
 * runtime entries can be removed; entries from the settings or an agent need a settings change and a restart.</p>
 */
public final class NetworkDialog extends DialogWrapper {

    /** Hint for a selected entry that cannot be removed at runtime. */
    static final String NOT_REMOVABLE = "From settings — edit the settings file and restart";
    /** Shown instead of the allow list and the blocked names in a sandbox started with `-u`. */
    static final String UNRESTRICTED = "Unrestricted network: everything is already allowed";

    private final HoleModifyClient client;
    private final boolean unrestricted;
    private final Runnable onRestart;
    private final JBLabel banner = new JBLabel();
    private final JButton restart = new JButton("Restart Sandbox");
    private final EntriesTab allowed;
    private final EntriesTab hostGateway;
    private final BlockedTab blocked;
    /** Calls in flight. EDT only. */
    private int running;

    /**
     * @param project      the IDE project
     * @param client       `hole modify` for this sandbox
     * @param title        e.g. {@code Network · my-project · claude (k3x9a2)}
     * @param unrestricted the sandbox runs with `-u`: the allow list and the blocked names are read-only / empty
     * @param onRestart    restarts the sandbox (offered when it was started by an older Hole)
     */
    public NetworkDialog(
        @NotNull Project project,
        @NotNull HoleModifyClient client,
        @NotNull String title,
        boolean unrestricted,
        @NotNull Runnable onRestart
    ) {
        super(project, true);
        this.client = client;
        this.unrestricted = unrestricted;
        this.onRestart = onRestart;
        this.allowed = new EntriesTab(NetworkSetting.ALLOW);
        this.hostGateway = new EntriesTab(NetworkSetting.HOST_GATEWAY_DOMAINS);
        this.blocked = new BlockedTab();
        setModal(false);
        setTitle(title);
        setOKButtonText("Close");
        init();
        this.allowed.load();
        this.hostGateway.load();
        this.blocked.load();
    }

    /**
     * Returns the instance ID the window changes; it closes when the session gets another one.
     */
    @NotNull
    public String instanceId() {
        return this.client.instanceId();
    }

    /**
     * Returns the banner text for a failed call, by Hole's exit code.
     */
    @NotNull
    static String bannerText(@NotNull HoleModifyClient.ModifyFailure failure, @NotNull String message) {
        return switch (failure) {
            case NOT_RUNNING -> "The sandbox is not running or still starting: " + message;
            case OLDER_HOLE -> "This sandbox was started by an older Hole; restart it to change its network: " + message;
            case APPLY_FAILED -> "The gateway did not apply the change; the previous allow list is still in force. " + message;
            case INVALID, OTHER -> message;
        };
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getOKAction()};
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        final JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        this.banner.setForeground(JBColor.namedColor("Label.errorForeground", new JBColor(0xD1323F, 0xF27C7C)));
        this.banner.setAllowAutoWrapping(true);
        this.banner.setVisible(false);
        top.add(this.banner);
        this.restart.setVisible(false);
        this.restart.addActionListener(event -> {
            close(OK_EXIT_CODE);
            this.onRestart.run();
        });
        top.add(this.restart);
        panel.add(top, BorderLayout.NORTH);
        final JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab("Allowed", this.allowed.panel);
        tabs.addTab("Host gateway", this.hostGateway.panel);
        tabs.addTab("Blocked", this.blocked.panel);
        panel.add(tabs, BorderLayout.CENTER);
        panel.setPreferredSize(new Dimension(JBUI.scale(640), JBUI.scale(420)));
        return panel;
    }

    /**
     * Runs a call off the EDT and hands its result to {@code then} on the EDT, unless the window is gone.
     */
    private <T> void call(@NotNull Supplier<HoleModifyClient.Result<T>> call, @NotNull Consumer<HoleModifyClient.Result<T>> then) {
        call(call, then, () -> {
        });
    }

    /**
     * Like {@link #call(Supplier, Consumer)}; {@code always} runs on the EDT after success and failure alike (e.g. to
     * replace a "Loading…" empty text).
     */
    private <T> void call(
        @NotNull Supplier<HoleModifyClient.Result<T>> call,
        @NotNull Consumer<HoleModifyClient.Result<T>> then,
        @NotNull Runnable always
    ) {
        this.running++;
        updateActions();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleModifyClient.Result<T> result = HoleModifyClient.Result.guarded(call);
            ApplicationManager.getApplication().invokeLater(() -> {
                this.running--;
                if (isDisposed()) {
                    return;
                }
                if (result.isSuccess()) {
                    then.accept(result);
                } else {
                    showFailure(result);
                }
                always.run();
                updateActions();
            }, ModalityState.any());
        });
    }

    private void showFailure(@NotNull HoleModifyClient.Result<?> result) {
        final HoleModifyClient.ModifyFailure failure = result.failure();
        if (failure == null) {
            return;
        }
        this.banner.setText(bannerText(failure, result.message()));
        this.banner.setVisible(true);
        this.restart.setVisible(failure == HoleModifyClient.ModifyFailure.OLDER_HOLE);
    }

    private void clearFailure() {
        this.banner.setVisible(false);
        this.restart.setVisible(false);
    }

    private void updateActions() {
        this.allowed.updateActions();
        this.hostGateway.updateActions();
        this.blocked.updateActions();
    }

    private boolean idle() {
        return this.running == 0;
    }

    /**
     * Opens the Add input; after an add the entry lists and the blocked names are reloaded.
     */
    private void openAdd(@NotNull NetworkSetting setting, @NotNull String prefill) {
        new EntryInputDialog(getContentPanel(), setting, prefill, values -> this.client.add(setting, values), result -> {
            if (isDisposed()) {
                return;
            }
            if (result.isSuccess()) {
                clearFailure();
            } else {
                showFailure(result);
            }
            tab(setting).load();
            if (setting == NetworkSetting.ALLOW) {
                this.blocked.load();
            }
        }).show();
    }

    @NotNull
    private EntriesTab tab(@NotNull NetworkSetting setting) {
        return setting == NetworkSetting.ALLOW ? this.allowed : this.hostGateway;
    }

    /**
     * Allowed or Host gateway: entries with their source, Add…, Remove (runtime entries only), Refresh.
     */
    private final class EntriesTab {
        private final NetworkSetting setting;
        private final NetworkTableModels.Entries model = new NetworkTableModels.Entries();
        private final JBTable table = new JBTable(this.model);
        private final JButton add = new JButton("Add…");
        private final JButton remove = new JButton("Remove");
        private final JButton refresh = new JButton("Refresh");
        private final JBLabel hint = new JBLabel();
        private final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        private final boolean readOnly;

        private EntriesTab(@NotNull NetworkSetting setting) {
            this.setting = setting;
            this.readOnly = setting == NetworkSetting.ALLOW && NetworkDialog.this.unrestricted;
            this.table.getSelectionModel().setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            this.table.getSelectionModel().addListSelectionListener(event -> updateActions());
            this.hint.setForeground(UIUtil.getContextHelpForeground());
            this.add.addActionListener(event -> openAdd(this.setting, ""));
            this.remove.addActionListener(event -> removeSelected());
            this.refresh.addActionListener(event -> load());
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            buttons.add(this.add);
            buttons.add(this.remove);
            buttons.add(this.refresh);
            final JPanel south = new JPanel();
            south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
            south.add(this.hint);
            south.add(buttons);
            if (this.readOnly) {
                final JBLabel note = new JBLabel(UNRESTRICTED);
                this.panel.add(note, BorderLayout.NORTH);
            }
            this.panel.add(ScrollPaneFactory.createScrollPane(this.table), BorderLayout.CENTER);
            this.panel.add(south, BorderLayout.SOUTH);
            this.panel.setBorder(JBUI.Borders.empty(6));
        }

        private void load() {
            this.table.getEmptyText().setText("Loading…");
            call(() -> NetworkDialog.this.client.list(this.setting), result -> {
                this.model.set(result.value() == null ? List.of() : result.value());
            }, () -> this.table.getEmptyText().setText("No entries"));
        }

        @NotNull
        private List<NetworkEntry> selected() {
            final List<NetworkEntry> entries = new ArrayList<>();
            for (int row : this.table.getSelectedRows()) {
                entries.add(this.model.row(this.table.convertRowIndexToModel(row)));
            }
            return entries;
        }

        private void removeSelected() {
            final List<NetworkEntry> entries = selected();
            if (entries.isEmpty() || !entries.stream().allMatch(NetworkEntry::isRuntime)) {
                return;
            }
            // several rows: one call, one gateway apply
            call(() -> NetworkDialog.this.client.remove(this.setting, entries.stream().map(NetworkEntry::entry).toList()), result -> {
                clearFailure();
                load();
            });
        }

        private void updateActions() {
            final List<NetworkEntry> entries = selected();
            final boolean removable = !entries.isEmpty() && entries.stream().allMatch(NetworkEntry::isRuntime);
            this.add.setEnabled(idle() && !this.readOnly);
            this.remove.setEnabled(idle() && !this.readOnly && removable);
            this.refresh.setEnabled(idle());
            final boolean fromSettings = entries.stream().anyMatch(entry -> !entry.isRuntime());
            this.hint.setText(fromSettings ? NOT_REMOVABLE : " ");
        }
    }

    /**
     * Blocked: refused names, newest first, with Allow / Allow on ports… / Allow *.parent (buttons, context menu,
     * double-click = Allow), each opening the Add input prefilled.
     */
    private final class BlockedTab {
        private final NetworkTableModels.Blocked model = new NetworkTableModels.Blocked(ZoneId.systemDefault());
        private final JBTable table = new JBTable(this.model);
        private final JButton allow = new JButton("Allow");
        private final JButton allowOnPorts = new JButton("Allow on Ports…");
        private final JButton allowWildcard = new JButton("Allow *.parent");
        private final JButton refresh = new JButton("Refresh");
        private final JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));

        private BlockedTab() {
            this.table.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            this.table.getSelectionModel().addListSelectionListener(event -> updateActions());
            this.allow.addActionListener(event -> selected().ifPresent(name -> openAdd(NetworkSetting.ALLOW, BlockedSuggestions.allow(name))));
            this.allowOnPorts.addActionListener(event -> selected().ifPresent(name -> openAdd(NetworkSetting.ALLOW, BlockedSuggestions.allowOnPorts(name))));
            this.allowWildcard.addActionListener(event -> selected().flatMap(BlockedSuggestions::wildcard)
                .ifPresent(entry -> openAdd(NetworkSetting.ALLOW, entry)));
            this.refresh.addActionListener(event -> load());
            this.table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && BlockedTab.this.allow.isEnabled()) {
                        BlockedTab.this.allow.doClick();
                    }
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    popup(event);
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    popup(event);
                }
            });
            final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
            buttons.add(this.allow);
            buttons.add(this.allowOnPorts);
            buttons.add(this.allowWildcard);
            buttons.add(this.refresh);
            if (NetworkDialog.this.unrestricted) {
                this.panel.add(new JBLabel(UNRESTRICTED), BorderLayout.NORTH);
            }
            this.panel.add(ScrollPaneFactory.createScrollPane(this.table), BorderLayout.CENTER);
            this.panel.add(buttons, BorderLayout.SOUTH);
            this.panel.setBorder(JBUI.Borders.empty(6));
        }

        private void popup(@NotNull MouseEvent event) {
            if (!event.isPopupTrigger()) {
                return;
            }
            final int row = this.table.rowAtPoint(event.getPoint());
            if (row >= 0) {
                this.table.setRowSelectionInterval(row, row);
            }
            final JPopupMenu menu = new JPopupMenu();
            for (JButton button : List.of(this.allow, this.allowOnPorts, this.allowWildcard)) {
                final JMenuItem item = new JMenuItem(button.getText());
                item.setEnabled(button.isEnabled());
                item.addActionListener(action -> button.doClick());
                menu.add(item);
            }
            menu.show(this.table, event.getX(), event.getY());
        }

        private void load() {
            if (NetworkDialog.this.unrestricted) {
                this.table.getEmptyText().setText("Nothing is blocked with unrestricted network access");
                return;
            }
            this.table.getEmptyText().setText("Loading…");
            call(NetworkDialog.this.client::listBlocked, result -> {
                final List<BlockedName> names = result.value() == null ? List.of() : result.value();
                this.model.set(names);
            }, () -> this.table.getEmptyText().setText("No blocked names"));
        }

        @NotNull
        private Optional<String> selected() {
            final int row = this.table.getSelectedRow();
            return row < 0 ? Optional.empty() : Optional.of(this.model.row(this.table.convertRowIndexToModel(row)).name());
        }

        private void updateActions() {
            final Optional<String> name = selected();
            final boolean usable = idle() && name.isPresent() && !NetworkDialog.this.unrestricted;
            this.allow.setEnabled(usable);
            this.allowOnPorts.setEnabled(usable);
            this.allowWildcard.setEnabled(usable && name.flatMap(BlockedSuggestions::wildcard).isPresent());
            this.allowWildcard.setText(name.flatMap(BlockedSuggestions::wildcard)
                .map(entry -> "Allow " + entry.substring(0, entry.indexOf(':')))
                .orElse("Allow *.parent"));
            this.refresh.setEnabled(idle() && !NetworkDialog.this.unrestricted);
        }
    }
}
