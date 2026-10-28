package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmptyStateModelTest {

    private static final Favourite TESTS = new Favourite("acme tests", FavouriteScope.PROJECT,
        SandboxConfig.defaults().withProfile("research"));
    private static final Favourite CLAUDE = new Favourite("Claude here", FavouriteScope.GLOBAL, SandboxConfig.defaults());
    private static final Favourite GEMINI = new Favourite("Gemini", FavouriteScope.GLOBAL, SandboxConfig.defaults().withAgent("gemini"));

    @Test
    void withoutFavouritesOnlyNewSandboxAndEditFavourites() {
        assertEquals(List.of(
            "TITLE No sandboxes are running", "NEW_SANDBOX New Sandbox…", "EDIT_FAVOURITES Edit Favourites…"
        ), lines(EmptyStateModel.of(List.of(), null)));
    }

    @Test
    void favouritesAreGroupedProjectFirst() {
        // given in any order: project favourites come first
        assertEquals(List.of(
            "TITLE No sandboxes are running",
            "NEW_SANDBOX New Sandbox…",
            "GAP ",
            "HEADER This project",
            "FAVOURITE acme tests | claude:research",
            "HEADER All projects",
            "FAVOURITE Claude here | claude",
            "FAVOURITE Gemini | gemini",
            "EDIT_FAVOURITES Edit Favourites…"
        ), lines(EmptyStateModel.of(List.of(CLAUDE, TESTS, GEMINI), null)));
    }

    @Test
    void secondaryTextIsTheAgentOnly() {
        // the project path is chosen at start, so it is not shown
        final Favourite stored = CLAUDE.withConfig(CLAUDE.config().withProjectPath("/work/acme-cms-worktree"));
        assertEquals("FAVOURITE Claude here | claude", lines(EmptyStateModel.of(List.of(stored), null)).get(4));
    }

    @Test
    void atMostFiveFavouritesThenMore() {
        final List<Favourite> many = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            many.add(CLAUDE.withName("Global " + i));
        }
        many.add(TESTS);
        final List<String> lines = lines(EmptyStateModel.of(many, null));
        assertEquals(EmptyStateModel.LIMIT, lines.stream().filter(line -> line.startsWith("FAVOURITE ")).count());
        assertEquals("FAVOURITE acme tests | claude:research", lines.get(4), "the project favourite is kept");
        assertEquals("FAVOURITE Global 4 | claude", lines.get(lines.size() - 2));
        assertEquals("MORE_AND_EDIT Edit Favourites…", lines.get(lines.size() - 1));
    }

    @Test
    void exactlyFiveFavouritesNeedNoMore() {
        final List<Favourite> five = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            five.add(CLAUDE.withName("Global " + i));
        }
        final List<String> lines = lines(EmptyStateModel.of(five, null));
        assertEquals("EDIT_FAVOURITES Edit Favourites…", lines.get(lines.size() - 1));
    }

    @Test
    void favouritesAreDisabledWhenHoleCannotStart() {
        final HoleCapability missing = new HoleCapability(HoleCapability.Status.NOT_FOUND, null, "Hole was not found");
        final List<String> lines = lines(EmptyStateModel.of(List.of(TESTS), missing));
        assertEquals(List.of(
            "TITLE No sandboxes are running",
            "NEW_SANDBOX New Sandbox…",
            "GAP ",
            "HEADER This project",
            "DISABLED_FAVOURITE acme tests | claude:research",
            "CAPABILITY Hole was not found",
            "EDIT_FAVOURITES Edit Favourites…"
        ), lines);
    }

    private static List<String> lines(EmptyStateModel model) {
        return model.lines().stream()
            .map(line -> line.kind() + " " + line.text() + (line.secondary().isEmpty() ? "" : " | " + line.secondary()))
            .toList();
    }
}
