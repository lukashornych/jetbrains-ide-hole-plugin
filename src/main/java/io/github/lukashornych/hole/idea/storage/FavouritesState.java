package io.github.lukashornych.hole.idea.storage;

import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Persisted list of favourites of one scope, in the user's order.
 */
@Data
@NoArgsConstructor
public class FavouritesState {

    private List<FavouriteBean> favourites = new ArrayList<>();

    /**
     * Returns the favourites as model values.
     */
    @NotNull
    public List<Favourite> toFavourites(@NotNull FavouriteScope scope) {
        if (this.favourites == null) {
            return List.of();
        }
        return this.favourites.stream().filter(Objects::nonNull).map(bean -> bean.toFavourite(scope)).toList();
    }

    /**
     * Returns a state holding the given favourites.
     */
    @NotNull
    public static FavouritesState of(@NotNull List<Favourite> favourites) {
        final FavouritesState state = new FavouritesState();
        state.favourites = new ArrayList<>(favourites.stream().map(FavouriteBean::of).toList());
        return state;
    }
}
