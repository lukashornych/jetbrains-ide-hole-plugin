package io.github.lukashornych.hole.idea.ui.library;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.session.SessionState;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Change;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Row;
import io.github.lukashornych.hole.idea.ui.library.LibraryTableModel.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Swing-free texts of the Libraries window: the Remove confirmation and the hint for the selected row.
 */
class LibrariesDialogTextsTest {

    @Test
    void globalRemovalWarnsAboutEveryProject() {
        final String question = LibrariesDialog.removeQuestion(
            new Row("~/a", "/libs/a", false, true, Source.GLOBAL, Change.NONE, ""), HoleSettingsClient.Scope.GLOBAL);
        assertTrue(question.startsWith("Remove ~/a from the global settings file?"), question);
        assertTrue(question.contains("This affects every project."), question);
        assertFalse(question.contains("trust"), question);
    }

    @Test
    void projectRemovalAsksForTrustAgainAndBringsBackTheGlobalEntry() {
        final String question = LibrariesDialog.removeQuestion(
            new Row("~/a", "/libs/a", false, true, Source.PROJECT_OVERRIDES_GLOBAL, Change.NONE, ""), HoleSettingsClient.Scope.PROJECT);
        assertTrue(question.startsWith("Remove ~/a from the project settings file?"), question);
        assertTrue(question.contains("The global entry for it applies again."), question);
        assertTrue(question.contains(LibraryInputDialog.PROJECT_TRUST_HINT), question);
    }

    @Test
    void hintFollowsTheSelectedRow() {
        assertEquals(" ", LibrariesDialog.hint(null));
        assertEquals(LibrariesDialog.THIS_START_HINT,
            LibrariesDialog.hint(new Row("~/a", "~/a", false, true, Source.THIS_START, Change.NONE, "")));
        assertEquals(LibrariesDialog.REMOVED_HINT,
            LibrariesDialog.hint(new Row("~/a", "/a", false, true, Source.GLOBAL, Change.REMOVED, "")));
        assertEquals("dup", LibrariesDialog.hint(new Row("~/a", "/a", false, true, Source.GLOBAL, Change.NONE, "dup")));
    }

    @Test
    void closingAfterAChangeOffersTheRestartOnlyWhileRunning() {
        assertEquals(LibrariesDialog.AfterChange.OFFER_RESTART, LibrariesDialog.afterChange(true, SessionState.RUNNING));
        assertEquals(LibrariesDialog.AfterChange.EXITED_HINT, LibrariesDialog.afterChange(true, SessionState.EXITED));
        for (SessionState state : SessionState.values()) {
            assertEquals(LibrariesDialog.AfterChange.NONE, LibrariesDialog.afterChange(false, state), state.name());
        }
        assertEquals(LibrariesDialog.AfterChange.NONE, LibrariesDialog.afterChange(true, SessionState.STOPPING));
        assertEquals(LibrariesDialog.AfterChange.NONE, LibrariesDialog.afterChange(true, SessionState.STARTING));
    }

    /**
     * Regression: saving an Edit of a `project (overrides global)` row without a change still rewrote the file and
     * offered a restart.
     */
    @Test
    void editWithoutAVisibleChangeWritesNothing() {
        final Row row = new Row("~/a", "/libs/a", true, true, Source.PROJECT_OVERRIDES_GLOBAL, Change.NONE, "");
        assertTrue(LibraryInputDialog.unchanged(row, "/libs/a", true));
        assertFalse(LibraryInputDialog.unchanged(row, "/libs/a", false));
        assertFalse(LibraryInputDialog.unchanged(row, "/libs/b", true));
        assertFalse(LibraryInputDialog.unchanged(new Row("~/a", "", false, false, Source.PROJECT, Change.NONE, ""), "", false));
    }
}
