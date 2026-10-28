package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import javax.swing.DefaultComboBoxModel;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combo suggestions are replaced only when they changed: the debounced checks run after every keystroke, and a
 * new model closes the dropdown and resets the caret. Without an editable project path (Edit Favourites) the
 * configuration has `.` and no project path problems.
 */
class SandboxFormTest {

    @Test
    void unchangedSuggestionsKeepTheModel() {
        // regression: the model was rebuilt after every keystroke
        final DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(new String[]{"", "research", "work"});
        assertFalse(SandboxForm.shouldReplaceItems(SandboxForm.items(model), List.of("", "research", "work")));
    }

    @Test
    void changedSuggestionsReplaceTheModel() {
        final List<String> current = List.of("claude", "codex");
        assertTrue(SandboxForm.shouldReplaceItems(current, List.of("claude", "codex", "my-agent")));
        assertTrue(SandboxForm.shouldReplaceItems(current, List.of("claude")));
        assertTrue(SandboxForm.shouldReplaceItems(current, List.of("codex", "claude")), "order is shown, so it counts");
    }

    @Test
    void itemsOfModel() {
        assertEquals(List.of("a", "b"), SandboxForm.items(new DefaultComboBoxModel<>(new String[]{"a", "b"})));
        assertEquals(List.of(), SandboxForm.items(new DefaultComboBoxModel<>()));
    }

    @Test
    void editableProjectPathIsNormalised() {
        assertEquals("/home/user/work", SandboxForm.projectPath(true, " ~/work ", "/home/user"));
        assertEquals("../other", SandboxForm.projectPath(true, "../other", "/home/user"));
    }

    @Test
    void notEditableProjectPathIsTheProjectRoot() {
        assertEquals(".", SandboxForm.projectPath(false, "../other", "/home/user"));
        assertEquals(".", SandboxForm.projectPath(false, "", "/home/user"));
    }

    @Test
    void projectPathProblemsOnlyWhenEditable() {
        final SandboxConfig config = SandboxConfig.defaults().withProjectPath("~bob/work").withAgent("");
        assertEquals(
            List.of(SandboxFormValidator.Field.AGENT, SandboxFormValidator.Field.PROJECT_PATH),
            SandboxForm.problems(true, config, null).stream().map(SandboxFormValidator.Problem::field).toList()
        );
        assertEquals(
            List.of(SandboxFormValidator.Field.AGENT),
            SandboxForm.problems(false, config, null).stream().map(SandboxFormValidator.Problem::field).toList()
        );
    }
}
