package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Lines of the tool window's empty state (no sandbox tabs), without Swing so they can be tested:
 *
 * <pre>
 * No sandboxes are running
 * New Sandbox…
 *
 * This project
 *   evita tests   claude ./worktrees/feature
 * All projects
 *   Claude here   claude .
 * More…   Edit Favourites…
 * </pre>
 *
 * <p>At most {@link #LIMIT} favourites are listed, project ones first, because a bottom tool window is short and the
 * status text does not scroll; <i>More…</i> opens the favourites dropdown with all of them. When Hole cannot start
 * (missing or too old), the favourites are plain text and the capability message follows them.</p>
 *
 * @param lines the lines from top to bottom
 */
public record EmptyStateModel(@NotNull List<Line> lines) {

    /** Most favourites shown on the start page. */
    static final int LIMIT = 5;

    public EmptyStateModel {
        lines = List.copyOf(lines);
    }

    /**
     * Builds the lines.
     *
     * @param favourites project favourites followed by global ones ({@code GlobalFavouritesService.all})
     * @param capability the last Hole probe, {@code null} when not probed yet (favourites are clickable then; the start
     *                   probes again)
     */
    @NotNull
    public static EmptyStateModel of(@NotNull List<Favourite> favourites, @Nullable HoleCapability capability) {
        final List<Line> lines = new ArrayList<>();
        lines.add(new Line(Kind.TITLE, "No sandboxes are running", "", null));
        lines.add(new Line(Kind.NEW_SANDBOX, "New Sandbox…", "", null));
        final List<Favourite> ordered = new ArrayList<>();
        favourites.stream().filter(f -> f.scope() == FavouriteScope.PROJECT).forEach(ordered::add);
        favourites.stream().filter(f -> f.scope() == FavouriteScope.GLOBAL).forEach(ordered::add);
        if (ordered.isEmpty()) {
            lines.add(new Line(Kind.EDIT_FAVOURITES, "Edit Favourites…", "", null));
            return new EmptyStateModel(lines);
        }
        final boolean startable = capability == null || capability.canStart();
        lines.add(new Line(Kind.GAP, "", "", null));
        FavouriteScope group = null;
        for (Favourite favourite : ordered.subList(0, Math.min(LIMIT, ordered.size()))) {
            if (favourite.scope() != group) {
                group = favourite.scope();
                lines.add(new Line(Kind.HEADER, group == FavouriteScope.PROJECT ? "This project" : "All projects", "", null));
            }
            lines.add(new Line(
                startable ? Kind.FAVOURITE : Kind.DISABLED_FAVOURITE,
                favourite.name(),
                favourite.config().agentSpec() + " " + favourite.config().projectPath(),
                favourite
            ));
        }
        if (!startable) {
            lines.add(new Line(Kind.CAPABILITY, capability.message(), "", null));
        }
        lines.add(new Line(ordered.size() > LIMIT ? Kind.MORE_AND_EDIT : Kind.EDIT_FAVOURITES, "Edit Favourites…", "", null));
        return new EmptyStateModel(lines);
    }

    /**
     * What a line is.
     */
    public enum Kind {
        /** "No sandboxes are running". */
        TITLE,
        /** The New Sandbox… link. */
        NEW_SANDBOX,
        /** Empty line before the favourites. */
        GAP,
        /** Grey group header: "This project" / "All projects". */
        HEADER,
        /** A favourite link (starts it in agent mode) with its command in grey. */
        FAVOURITE,
        /** A favourite as plain grey text: Hole cannot start. */
        DISABLED_FAVOURITE,
        /** Why Hole cannot start. */
        CAPABILITY,
        /** The Edit Favourites… link. */
        EDIT_FAVOURITES,
        /** More… (opens the dropdown) followed by Edit Favourites…. */
        MORE_AND_EDIT
    }

    /**
     * One line.
     *
     * @param kind      what it is
     * @param text      main text
     * @param secondary grey text after it (the command of a favourite), empty when none
     * @param favourite the favourite of a {@link Kind#FAVOURITE} / {@link Kind#DISABLED_FAVOURITE} line
     */
    public record Line(@NotNull Kind kind, @NotNull String text, @NotNull String secondary, @Nullable Favourite favourite) {
    }
}
