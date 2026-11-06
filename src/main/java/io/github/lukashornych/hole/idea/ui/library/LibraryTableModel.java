package io.github.lukashornych.hole.idea.ui.library;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.model.Library;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Rows of the Libraries window: <i>Host path</i> | <i>Mount path</i> | <i>Access</i> | <i>Source</i>. The rows are the
 * libraries of the two settings files as Hole merges them, the libraries the snapshot had and the files no longer
 * have (still mounted until a restart), and the `--library` values of this start.
 */
final class LibraryTableModel extends AbstractTableModel {

    /** Marks a file library that differs from the snapshot taken for this sandbox. */
    static final String RESTART_TO_APPLY = "restart to apply";
    /** Marks a library the snapshot had and the files no longer have. */
    static final String REMOVED = "removed — restart to apply";

    private List<Row> rows = List.of();

    /**
     * Where a row comes from.
     */
    enum Source {
        GLOBAL("global"),
        PROJECT("project"),
        PROJECT_OVERRIDES_GLOBAL("project (overrides global)"),
        THIS_START("this start");

        private final String text;

        Source(@NotNull String text) {
            this.text = text;
        }

        @NotNull
        String text() {
            return this.text;
        }

        @NotNull
        static Source of(@NotNull SettingsLibraries.Origin origin) {
            return switch (origin) {
                case GLOBAL -> GLOBAL;
                case PROJECT -> PROJECT;
                case PROJECT_OVERRIDES_GLOBAL -> PROJECT_OVERRIDES_GLOBAL;
            };
        }

        /**
         * Returns the settings file a row of this source is edited in, or {@code null} for `this start`.
         */
        @Nullable
        HoleSettingsClient.Scope scope() {
            return switch (this) {
                case GLOBAL -> HoleSettingsClient.Scope.GLOBAL;
                case PROJECT, PROJECT_OVERRIDES_GLOBAL -> HoleSettingsClient.Scope.PROJECT;
                case THIS_START -> null;
            };
        }
    }

    /**
     * How a file library relates to the snapshot of what the sandbox mounted.
     */
    enum Change {
        /** As at the snapshot. */
        NONE,
        /** Added or changed since the snapshot. */
        CHANGED,
        /** In the snapshot, no longer in the files. */
        REMOVED
    }

    /**
     * One row.
     *
     * @param hostPath  as written in the file or given with `--library`
     * @param mountPath container path; empty when the file value has none
     * @param readWrite mounted read-write
     * @param valid     the file value is a library Hole reads
     * @param source    where it comes from
     * @param change    compared with the snapshot
     * @param warning   a likely problem (the same directory under another key), empty when none
     */
    record Row(
        @NotNull String hostPath, @NotNull String mountPath, boolean readWrite, boolean valid, @NotNull Source source,
        @NotNull Change change, @NotNull String warning
    ) {

        /**
         * Returns {@code true} when Remove and Edit can change it: a library of a settings file that is still there.
         */
        boolean editable() {
            return this.source != Source.THIS_START && this.change != Change.REMOVED;
        }

        @NotNull
        String sourceText() {
            return switch (this.change) {
                case NONE -> this.source.text();
                case CHANGED -> this.source.text() + " · " + RESTART_TO_APPLY;
                case REMOVED -> this.source.text() + " · " + REMOVED;
            };
        }

        @NotNull
        String mountText() {
            return this.valid ? this.mountPath : "(not a valid library value)";
        }
    }

    /**
     * Builds the rows.
     *
     * @param files     the libraries of both files, merged as by Hole
     * @param snapshot  the file libraries when the window was first opened for this sandbox, {@code null} when none
     *                  was taken; nothing is marked then
     * @param thisStart the `--library` rows of the sandbox's start
     * @param resolver  resolves host paths for the duplicate warning
     */
    @NotNull
    static List<Row> rows(
        @NotNull List<SettingsLibraries.Library> files,
        @Nullable List<SettingsLibraries.Library> snapshot,
        @NotNull List<Library> thisStart,
        @NotNull SettingsLibraries.HostResolver resolver
    ) {
        final List<Row> rows = new ArrayList<>();
        final Map<String, SettingsLibraries.Library> before = new LinkedHashMap<>();
        if (snapshot != null) {
            snapshot.forEach(library -> before.put(library.hostPath(), library));
        }
        for (SettingsLibraries.Library library : files) {
            final SettingsLibraries.Library old = before.remove(library.hostPath());
            final boolean changed = snapshot != null && (old == null || !Objects.equals(old.mountPath(), library.mountPath())
                || old.readWrite() != library.readWrite());
            rows.add(new Row(library.hostPath(), library.mountPath(), library.readWrite(), library.valid(),
                Source.of(library.origin()), changed ? Change.CHANGED : Change.NONE, ""));
        }
        for (SettingsLibraries.Library removed : before.values()) {
            rows.add(new Row(removed.hostPath(), removed.mountPath(), removed.readWrite(), removed.valid(),
                Source.of(removed.origin()), Change.REMOVED, ""));
        }
        for (Library library : thisStart) {
            if (!library.isBlank()) {
                parseArgument(library.toArgument()).ifPresent(rows::add);
            }
        }
        return withWarnings(rows, resolver);
    }

    /**
     * Reads a `--library PATH[:MOUNT][:rw]` value as Hole's `ParseLibraryFlag` does; without a mount the directory is
     * mounted at its own host path.
     */
    @NotNull
    static Optional<Row> parseArgument(@NotNull String argument) {
        final String[] parts = argument.trim().split(":", -1);
        final String host = parts[0];
        String mount = "";
        boolean readWrite = false;
        switch (parts.length) {
            case 1 -> {
            }
            case 2 -> {
                if (parts[1].equals("rw")) {
                    readWrite = true;
                } else {
                    mount = parts[1];
                }
            }
            case 3 -> {
                mount = parts[1];
                readWrite = parts[2].equals("rw");
            }
            default -> {
                return Optional.empty();
            }
        }
        if (host.isEmpty()) {
            return Optional.empty();
        }
        if (mount.isEmpty()) {
            mount = host.length() > 1 ? host.replaceAll("/+$", "") : host;
        }
        return Optional.of(new Row(host, mount, readWrite, true, Source.THIS_START, Change.NONE, ""));
    }

    /**
     * Warns about rows that name the same directory: Hole resolves the keys only when it mounts and keeps one of them
     * (a `--library` value wins over the files; between two file keys it is not even predictable).
     */
    @NotNull
    private static List<Row> withWarnings(@NotNull List<Row> rows, @NotNull SettingsLibraries.HostResolver resolver) {
        final List<Row> warned = new ArrayList<>(rows.size());
        for (Row row : rows) {
            if (row.change() == Change.REMOVED) {
                warned.add(row);
                continue;
            }
            final String directory = resolver.resolve(row.hostPath());
            final List<Row> same = rows.stream()
                .filter(other -> other != row && other.change() != Change.REMOVED && resolver.resolve(other.hostPath()).equals(directory))
                .toList();
            String warning = "";
            if (row.source() != Source.THIS_START && same.stream().anyMatch(other -> other.source() == Source.THIS_START)) {
                warning = "Given with --library for this start, which wins over the settings";
            } else if (row.source() != Source.THIS_START && !same.isEmpty()) {
                warning = "Same directory as " + same.stream().filter(other -> other.source() != Source.THIS_START)
                    .map(other -> other.hostPath() + " (" + other.source().text() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse("") + " — Hole mounts only one of them";
            }
            warned.add(new Row(row.hostPath(), row.mountPath(), row.readWrite(), row.valid(), row.source(), row.change(), warning));
        }
        return warned;
    }

    void set(@NotNull List<Row> rows) {
        this.rows = List.copyOf(rows);
        fireTableDataChanged();
    }

    @NotNull
    Row row(int index) {
        return this.rows.get(index);
    }

    @NotNull
    List<Row> rows() {
        return this.rows;
    }

    @Override
    public int getRowCount() {
        return this.rows.size();
    }

    @Override
    public int getColumnCount() {
        return 4;
    }

    @Override
    public String getColumnName(int column) {
        return switch (column) {
            case 0 -> "Host path";
            case 1 -> "Mount path";
            case 2 -> "Access";
            default -> "Source";
        };
    }

    @Override
    public Object getValueAt(int row, int column) {
        final Row library = this.rows.get(row);
        return switch (column) {
            case 0 -> library.warning().isEmpty() ? library.hostPath() : library.hostPath() + " ⚠";
            case 1 -> library.mountText();
            case 2 -> library.readWrite() ? "read-write" : "read-only";
            default -> library.sourceText();
        };
    }
}
