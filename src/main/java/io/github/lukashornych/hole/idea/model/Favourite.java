package io.github.lukashornych.hole.idea.model;

import lombok.With;
import org.jetbrains.annotations.NotNull;

/**
 * A named, stored sandbox configuration that can be started from the favourites dropdown. The project path is not
 * part of a favourite: its config always holds `.`, and the start chooses the project root or a git worktree of it.
 *
 * @param name   name shown in the dropdown
 * @param scope  where the favourite is stored
 * @param config the sandbox configuration to start
 */
@With
public record Favourite(@NotNull String name, @NotNull FavouriteScope scope, @NotNull SandboxConfig config) {

    public Favourite {
        config = config.withProjectPath(SandboxConfig.DEFAULT_PROJECT_PATH);
    }
}
