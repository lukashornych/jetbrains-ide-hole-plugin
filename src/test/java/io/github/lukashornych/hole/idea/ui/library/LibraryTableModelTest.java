package io.github.lukashornych.hole.idea.ui.library;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries.Origin;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Change;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Row;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Source;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rows of the Libraries window: sources, the marks against the snapshot, `--library` rows and duplicate warnings.
 */
class LibraryTableModelTest {

    private static final SettingsLibraries.HostResolver RESOLVER =
        new SettingsLibraries.HostResolver(Path.of("/home/user"), Path.of("/home/user/project"));

    private static SettingsLibraries.Library library(String host, String mount, boolean readWrite, Origin origin) {
        return new SettingsLibraries.Library(host, mount, readWrite, true, origin);
    }

    @Test
    void withoutSnapshotNothingIsMarked() {
        final List<Row> rows = LibraryTableModel.rows(List.of(
            library("~/a", "/libs/a", false, Origin.GLOBAL),
            library("~/b", "/libs/b", true, Origin.PROJECT_OVERRIDES_GLOBAL)
        ), null, List.of(), RESOLVER);
        assertEquals(List.of(
            new Row("~/a", "/libs/a", false, true, Source.GLOBAL, Change.NONE, ""),
            new Row("~/b", "/libs/b", true, true, Source.PROJECT_OVERRIDES_GLOBAL, Change.NONE, "")
        ), rows);
        assertEquals("project (overrides global)", rows.get(1).sourceText());
        assertEquals(HoleSettingsClient.Scope.PROJECT, rows.get(1).source().scope());
    }

    @Test
    void changesSinceTheSnapshotAreMarkedAndRemovedLibrariesStayListed() {
        final List<SettingsLibraries.Library> snapshot = List.of(
            library("~/same", "/libs/same", false, Origin.GLOBAL),
            library("~/mount", "/libs/old", false, Origin.GLOBAL),
            library("~/access", "/libs/access", false, Origin.PROJECT),
            library("~/gone", "/libs/gone", true, Origin.PROJECT)
        );
        final List<Row> rows = LibraryTableModel.rows(List.of(
            library("~/same", "/libs/same", false, Origin.GLOBAL),
            library("~/mount", "/libs/new", false, Origin.GLOBAL),
            library("~/access", "/libs/access", true, Origin.PROJECT),
            library("~/added", "/libs/added", false, Origin.GLOBAL)
        ), snapshot, List.of(), RESOLVER);
        assertEquals(List.of(Change.NONE, Change.CHANGED, Change.CHANGED, Change.CHANGED, Change.REMOVED),
            rows.stream().map(Row::change).toList());
        assertEquals("global · restart to apply", rows.get(1).sourceText());
        final Row gone = rows.get(4);
        assertEquals("~/gone", gone.hostPath());
        assertEquals("project · removed — restart to apply", gone.sourceText());
        assertFalse(gone.editable());
        assertTrue(rows.get(0).editable());
    }

    @Test
    void librariesOfThisStartAreReadOnlyAndReadLikeHole() {
        final List<Row> rows = LibraryTableModel.rows(List.of(), null, List.of(
            new Library("~/plain/", false),
            new Library("~/rw", true),
            new Library("~/mounted:/libs/m", true),
            new Library(" ", false)
        ), RESOLVER);
        assertEquals(List.of(
            new Row("~/plain/", "~/plain", false, true, Source.THIS_START, Change.NONE, ""),
            new Row("~/rw", "~/rw", true, true, Source.THIS_START, Change.NONE, ""),
            new Row("~/mounted", "/libs/m", true, true, Source.THIS_START, Change.NONE, "")
        ), rows);
        assertFalse(rows.get(0).editable());
        assertEquals(Optional.empty(), LibraryTableModel.parseArgument("a:b:c:d"));
    }

    @Test
    void sameDirectoryUnderAnotherKeyIsWarnedAbout() {
        final List<Row> rows = LibraryTableModel.rows(List.of(
            library("~/lib", "/libs/lib", false, Origin.GLOBAL),
            library("/home/user/lib/", "/libs/lib2", false, Origin.PROJECT),
            library("~/other", "/libs/other", false, Origin.GLOBAL)
        ), null, List.of(new Library("/home/user/other", false)), RESOLVER);
        assertEquals("Same directory as /home/user/lib/ (project) — Hole mounts only one of them", rows.get(0).warning());
        assertEquals("Same directory as ~/lib (global) — Hole mounts only one of them", rows.get(1).warning());
        assertEquals("Given with --library for this start, which wins over the settings", rows.get(2).warning());
        assertEquals("", rows.get(3).warning());
        assertEquals("~/lib ⚠", tableModel(rows).getValueAt(0, 0));
    }

    @Test
    void columns() {
        final LibraryTableModel model = tableModel(List.of(
            new Row("~/a", "/libs/a", true, true, Source.GLOBAL, Change.NONE, ""),
            new Row("~/b", "", false, false, Source.PROJECT, Change.NONE, "")
        ));
        assertEquals(List.of("Host path", "Mount path", "Access", "Source"),
            List.of(model.getColumnName(0), model.getColumnName(1), model.getColumnName(2), model.getColumnName(3)));
        assertEquals("read-write", model.getValueAt(0, 2));
        assertEquals("read-only", model.getValueAt(1, 2));
        assertEquals("(not a valid library value)", model.getValueAt(1, 1));
    }

    private static LibraryTableModel tableModel(List<Row> rows) {
        final LibraryTableModel model = new LibraryTableModel();
        model.set(rows);
        return model;
    }
}
