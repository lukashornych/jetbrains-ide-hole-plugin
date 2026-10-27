package io.github.lukashornych.hole.idea.storage;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Favourites of <i>This project</i>, stored in `.idea/hole.xml` (shareable through version control).
 */
@Service(Service.Level.PROJECT)
@State(name = "HoleProjectFavourites", storages = @Storage("hole.xml"))
public final class ProjectFavouritesService implements PersistentStateComponent<FavouritesState> {

    private volatile FavouritesState state = new FavouritesState();

    /**
     * Returns the project's instance.
     */
    @NotNull
    public static ProjectFavouritesService getInstance(@NotNull Project project) {
        return project.getService(ProjectFavouritesService.class);
    }

    /**
     * Returns the favourites in the stored order.
     */
    @NotNull
    public List<Favourite> getFavourites() {
        return this.state.toFavourites(FavouriteScope.PROJECT);
    }

    /**
     * Replaces all favourites of this project.
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
}
