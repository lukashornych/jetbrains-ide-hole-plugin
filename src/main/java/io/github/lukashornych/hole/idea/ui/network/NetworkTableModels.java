package io.github.lukashornych.hole.idea.ui.network;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Table models of the Network window: entries (<i>Entry</i> | <i>Source</i> | <i>Saved in</i>) and blocked names (<i>Name</i> |
 * <i>Count</i> | <i>Last seen</i>, local time).
 */
final class NetworkTableModels {

    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NetworkTableModels() {
    }

    /**
     * Formats a time Hole reported in the local time zone, empty when unknown.
     */
    @NotNull
    static String localTime(@Nullable OffsetDateTime time, @NotNull ZoneId zone) {
        return time == null ? "" : time.atZoneSameInstant(zone).format(LOCAL_TIME);
    }

    /**
     * One effective entry with the settings files that hold it at the top level.
     *
     * @param entry   as Hole lists it
     * @param savedIn the settings files whose top-level setting contains it (canonically), in {@link HoleSettingsClient.Scope} order
     * @param removed in force from settings, but this window removed it from the files; the gateway keeps it until a restart
     */
    record EntryRow(@NotNull NetworkEntry entry, @NotNull Set<HoleSettingsClient.Scope> savedIn, boolean removed) {

        /** <i>Saved in</i> of an entry removed from the settings files that is in force until a restart. */
        static final String REMOVED = "removed — restart to apply";

        /**
         * Pairs Hole's entries with the canonical entries of each settings file.
         */
        @NotNull
        static List<EntryRow> of(@NotNull List<NetworkEntry> entries, @NotNull Map<HoleSettingsClient.Scope, Set<String>> saved) {
            return of(entries, saved, Set.of());
        }

        /**
         * Pairs Hole's entries with the canonical entries of each settings file and marks the ones removed from the
         * files since the sandbox started.
         *
         * @param removed canonical entries this window removed from the settings files of the running instance
         */
        @NotNull
        static List<EntryRow> of(
            @NotNull List<NetworkEntry> entries, @NotNull Map<HoleSettingsClient.Scope, Set<String>> saved, @NotNull Set<String> removed
        ) {
            return entries.stream().map(entry -> {
                final Set<HoleSettingsClient.Scope> scopes = EnumSet.noneOf(HoleSettingsClient.Scope.class);
                saved.forEach((scope, values) -> {
                    if (values.contains(entry.entry())) {
                        scopes.add(scope);
                    }
                });
                final boolean pending = !entry.isRuntime() && scopes.isEmpty() && removed.contains(entry.entry());
                return new EntryRow(entry, Collections.unmodifiableSet(scopes), pending);
            }).toList();
        }

        /**
         * Returns {@code true} when Remove can take it away: a runtime entry, or one in a settings file.
         */
        boolean removable() {
            return this.entry.isRuntime() || !this.savedIn.isEmpty();
        }

        /**
         * Returns {@code true} when the gateway keeps the entry until a restart after it left the settings files.
         */
        boolean needsRestart() {
            return !this.entry.isRuntime() && !this.savedIn.isEmpty();
        }

        /**
         * Returns the files for the <i>Saved in</i> column, e.g. {@code global, project}.
         */
        @NotNull
        String savedInText() {
            if (this.removed) {
                return REMOVED;
            }
            return Arrays.stream(HoleSettingsClient.Scope.values()).filter(this.savedIn::contains)
                .map(HoleSettingsClient.Scope::label).collect(Collectors.joining(", "));
        }
    }

    /**
     * Effective entries of a setting.
     */
    static final class Entries extends AbstractTableModel {
        private List<EntryRow> rows = List.of();

        void set(@NotNull List<EntryRow> entries) {
            this.rows = List.copyOf(entries);
            fireTableDataChanged();
        }

        @NotNull
        EntryRow row(int index) {
            return this.rows.get(index);
        }

        @Override
        public int getRowCount() {
            return this.rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Entry";
                case 1 -> "Source";
                default -> "Saved in";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            final EntryRow entry = this.rows.get(row);
            return switch (column) {
                case 0 -> entry.entry().entry();
                case 1 -> entry.entry().source();
                default -> entry.savedInText();
            };
        }
    }

    /**
     * Names the gateway refused, newest first.
     */
    static final class Blocked extends AbstractTableModel {
        private final ZoneId zone;
        private List<BlockedName> rows = List.of();

        Blocked(@NotNull ZoneId zone) {
            this.zone = zone;
        }

        void set(@NotNull List<BlockedName> names) {
            this.rows = List.copyOf(names);
            fireTableDataChanged();
        }

        @NotNull
        BlockedName row(int index) {
            return this.rows.get(index);
        }

        @Override
        public int getRowCount() {
            return this.rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Name";
                case 1 -> "Count";
                default -> "Last seen";
            };
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 1 ? Long.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            final BlockedName name = this.rows.get(row);
            return switch (column) {
                case 0 -> name.name();
                case 1 -> name.count();
                default -> localTime(name.lastSeen(), this.zone);
            };
        }
    }
}
