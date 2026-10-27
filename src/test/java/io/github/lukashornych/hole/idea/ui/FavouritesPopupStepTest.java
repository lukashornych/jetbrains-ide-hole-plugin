package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FavouritesPopupStepTest {

    @Test
    void projectFavouritesFirstThenGlobalThenCommands() {
        final Favourite global = new Favourite("Codex anywhere", FavouriteScope.GLOBAL, SandboxConfig.defaults().withAgent("codex"));
        final Favourite local = new Favourite("Research", FavouriteScope.PROJECT, SandboxConfig.defaults());
        final List<FavouritesPopupStep.Item> items = FavouritesPopupStep.items(List.of(global, local));
        assertEquals(List.of(
            new FavouritesPopupStep.FavouriteItem(local),
            new FavouritesPopupStep.FavouriteItem(global),
            new FavouritesPopupStep.CommandItem(FavouritesPopupStep.Command.NEW_SANDBOX),
            new FavouritesPopupStep.CommandItem(FavouritesPopupStep.Command.EDIT_FAVOURITES)
        ), items);
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

    private static MouseEvent mouse(int modifiers) {
        return new MouseEvent(new JPanel(), MouseEvent.MOUSE_RELEASED, 0L, modifiers, 1, 1, 1, false, MouseEvent.BUTTON1);
    }

    private static KeyEvent key(int modifiers) {
        return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, KeyEvent.VK_ENTER, '\n');
    }
}
