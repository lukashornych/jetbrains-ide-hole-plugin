package io.github.lukashornych.hole.idea.session;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TabHistoryTest {

    @Test
    void previousIsMostRecentlySelectedOtherTab() {
        final TabHistory<String> history = new TabHistory<>();
        history.selected("a");
        history.selected("b");
        history.selected("c");
        history.selected("a");
        assertEquals(List.of("b", "c", "a"), history.asList());
        assertEquals("c", history.previous("a"));
        assertEquals("a", history.previous("c"), "for a tab that is not selected, the selected one");

        history.remove("c");
        assertEquals("b", history.previous("a"));
        history.remove("b");
        assertNull(history.previous("a"));
    }
}
