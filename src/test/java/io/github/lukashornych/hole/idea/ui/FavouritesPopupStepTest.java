package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import com.intellij.openapi.ui.popup.ListSeparator;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavouritesPopupStepTest {

    private static final Favourite GLOBAL = new Favourite("Codex anywhere", FavouriteScope.GLOBAL, SandboxConfig.defaults().withAgent("codex"));
    private static final Favourite LOCAL = new Favourite("Research", FavouriteScope.PROJECT, SandboxConfig.defaults());
    private static final FavouritesPopupStep.Item NEW_SANDBOX = new FavouritesPopupStep.CommandItem(FavouritesPopupStep.Command.NEW_SANDBOX);
    private static final FavouritesPopupStep.Item EDIT_FAVOURITES = new FavouritesPopupStep.CommandItem(FavouritesPopupStep.Command.EDIT_FAVOURITES);

    @Test
    void newSandboxFirstThenProjectAndGlobalFavouritesThenEditFavourites() {
        final List<FavouritesPopupStep.Item> items = FavouritesPopupStep.items(List.of(GLOBAL, LOCAL));
        assertEquals(List.of(
            NEW_SANDBOX,
            new FavouritesPopupStep.FavouriteItem(LOCAL),
            new FavouritesPopupStep.FavouriteItem(GLOBAL),
            EDIT_FAVOURITES
        ), items);
    }

    @Test
    void separatorsBelowNewSandboxBetweenGroupsAndAboveEditFavourites() {
        final List<FavouritesPopupStep.Item> items = FavouritesPopupStep.items(List.of(GLOBAL, LOCAL));
        assertEquals(Arrays.asList(null, "This project", "All projects", ""), separators(items));
    }

    @Test
    void withoutFavouritesTheCommandsHaveNoSeparator() {
        final List<FavouritesPopupStep.Item> items = FavouritesPopupStep.items(List.of());
        assertEquals(List.of(NEW_SANDBOX, EDIT_FAVOURITES), items);
        assertEquals(Arrays.asList(null, null), separators(items));
    }

    @Test
    void shiftClickRequestsDebugShell() {
        assertTrue(FavouritesPopupStep.debugRequested(mouse(InputEvent.SHIFT_DOWN_MASK)));
        assertFalse(FavouritesPopupStep.debugRequested(mouse(0)));
    }

    @Test
    void shiftEnterRequestsDebugShell() {
        // the event FavouritesPopupAction passes to ListPopup.handleSelect for Shift+Enter
        assertTrue(FavouritesPopupStep.debugRequested(key(InputEvent.SHIFT_DOWN_MASK)));
        assertFalse(FavouritesPopupStep.debugRequested(key(0)));
    }

    @Test
    void unknownEventStartsNormally() {
        assertFalse(FavouritesPopupStep.debugRequested(null));
        assertFalse(FavouritesPopupStep.debugRequested(key(InputEvent.CTRL_DOWN_MASK)));
    }

    /** Text of the separator above each item: {@code null} for none, {@code ""} for a plain line. */
    private static List<String> separators(List<FavouritesPopupStep.Item> items) {
        return items.stream()
            .map(item -> {
                final ListSeparator separator = FavouritesPopupStep.separatorAbove(items, item);
                return separator == null ? null : Objects.requireNonNullElse(separator.getText(), "");
            })
            .toList();
    }

    private static MouseEvent mouse(int modifiers) {
        return new MouseEvent(new JPanel(), MouseEvent.MOUSE_RELEASED, 0L, modifiers, 1, 1, 1, false, MouseEvent.BUTTON1);
    }

    private static KeyEvent key(int modifiers) {
        return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, KeyEvent.VK_ENTER, '\n');
    }
}
