package io.github.lukashornych.hole.idea.model;

import lombok.With;
import org.jetbrains.annotations.NotNull;

/**
 * A named, stored sandbox configuration that can be started from the favourites dropdown.
 *
 * @param name   name shown in the dropdown
 * @param scope  where the favourite is stored
 * @param config the sandbox configuration to start
 */
@With
public record Favourite(@NotNull String name, @NotNull FavouriteScope scope, @NotNull SandboxConfig config) {
}
