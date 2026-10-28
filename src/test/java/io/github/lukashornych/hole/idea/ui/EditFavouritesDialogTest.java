package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Swing-free parts of the Edit Favourites dialog: validation of all favourites on OK and the group captions.
 */
class EditFavouritesDialogTest {

    private static final Favourite VALID = new Favourite("Research", FavouriteScope.PROJECT, SandboxConfig.defaults());

    @Test
    void allValidFavouritesPass() {
        assertEquals(-1, EditFavouritesDialog.firstInvalid(List.of(VALID, VALID.withName("Other"))));
        assertEquals(-1, EditFavouritesDialog.firstInvalid(List.of()));
    }

    @Test
    void firstInvalidFavouriteIsFoundWhereverItIs() {
        // regression: only the selected favourite was validated on OK
        final Favourite badProfile = new Favourite("Bad profile", FavouriteScope.GLOBAL, SandboxConfig.defaults().withProfile("Not Valid"));
        final Favourite unnamed = VALID.withName(" ");
        assertEquals(2, EditFavouritesDialog.firstInvalid(List.of(VALID, VALID, badProfile, unnamed)));
        assertEquals(1, EditFavouritesDialog.firstInvalid(List.of(VALID, unnamed, badProfile)));
    }

    @Test
    void invalidAgentIsFound() {
        final Favourite badAgent = new Favourite("Agent", FavouriteScope.PROJECT, SandboxConfig.defaults().withAgent(""));
        assertEquals(0, EditFavouritesDialog.firstInvalid(List.of(badAgent, VALID)));
    }

    @Test
    void storedProjectPathCannotMakeAFavouriteInvalid() {
        // a favourite has no project path: a `~user` path that the validator rejects is dropped with it
        final SandboxConfig tilde = SandboxConfig.defaults().withProjectPath("~bob/work");
        assertEquals(-1, EditFavouritesDialog.firstInvalid(List.of(new Favourite("Tilde", FavouriteScope.PROJECT, tilde))));
    }

    @Test
    void captionAboveTheFirstRowOfEachScope() {
        final List<FavouriteScope> scopes = List.of(FavouriteScope.PROJECT, FavouriteScope.PROJECT, FavouriteScope.GLOBAL);
        assertEquals("This project", EditFavouritesDialog.captionAbove(scopes, 0));
        assertNull(EditFavouritesDialog.captionAbove(scopes, 1));
        assertEquals("All projects", EditFavouritesDialog.captionAbove(scopes, 2));
        assertEquals("All projects", EditFavouritesDialog.captionAbove(List.of(FavouriteScope.GLOBAL), 0));
    }
}
