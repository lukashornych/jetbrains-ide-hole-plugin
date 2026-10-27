package io.github.lukashornych.hole.idea.ui.network;

import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Table models of the Network window: entries (<i>Entry</i> | <i>Source</i>) and blocked names (<i>Name</i> |
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
     * Effective entries of a setting.
     */
    static final class Entries extends AbstractTableModel {
        private List<NetworkEntry> rows = List.of();

        void set(@NotNull List<NetworkEntry> entries) {
            this.rows = List.copyOf(entries);
            fireTableDataChanged();
        }

        @NotNull
        NetworkEntry row(int index) {
            return this.rows.get(index);
        }

        @Override
        public int getRowCount() {
            return this.rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "Entry" : "Source";
        }

        @Override
        public Object getValueAt(int row, int column) {
            final NetworkEntry entry = this.rows.get(row);
            return column == 0 ? entry.entry() : entry.source();
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
