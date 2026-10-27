package io.github.lukashornych.hole.idea.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Favourites of <i>All projects</i>, stored in the IDE application settings (`options/holeFavourites.xml`). They
 * typically use `.` as project path, so they work in any project.
 */
@Service(Service.Level.APP)
@State(name = "HoleGlobalFavourites", storages = @Storage("holeFavourites.xml"))
public final class GlobalFavouritesService implements PersistentStateComponent<FavouritesState> {

    private volatile FavouritesState state = new FavouritesState();

    /**
     * Returns the application-wide instance.
     */
    @NotNull
    public static GlobalFavouritesService getInstance() {
        return ApplicationManager.getApplication().getService(GlobalFavouritesService.class);
    }

    /**
     * Returns the favourites in the stored order.
     */
    @NotNull
    public List<Favourite> getFavourites() {
        return this.state.toFavourites(FavouriteScope.GLOBAL);
    }

    /**
     * Replaces all global favourites.
     */
    public void setFavourites(@NotNull List<Favourite> favourites) {
        this.state = FavouritesState.of(favourites);
    }

    @NotNull
    @Override
    public FavouritesState getState() {
        return this.state;
    }

    @Override
    public void loadState(@NotNull FavouritesState state) {
        this.state = state;
    }

    /**
     * Returns the favourites of {@code project} followed by the global ones, as shown in the dropdown.
     */
    @NotNull
    public static List<Favourite> all(@NotNull com.intellij.openapi.project.Project project) {
        final List<Favourite> all = new ArrayList<>(ProjectFavouritesService.getInstance(project).getFavourites());
        all.addAll(getInstance().getFavourites());
        return List.copyOf(all);
    }

    /**
     * Stores favourites edited in one list: each goes to the service of its scope, in the given order.
     */
    public static void saveAll(@NotNull com.intellij.openapi.project.Project project, @NotNull List<Favourite> favourites) {
        ProjectFavouritesService.getInstance(project).setFavourites(
            favourites.stream().filter(favourite -> favourite.scope() == FavouriteScope.PROJECT).toList()
        );
        getInstance().setFavourites(
            favourites.stream().filter(favourite -> favourite.scope() == FavouriteScope.GLOBAL).toList()
        );
    }
}
