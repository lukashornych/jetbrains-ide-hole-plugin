package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;

/**
 * One extra host directory mounted into the sandbox (`--library PATH[:rw]`).
 *
 * @param path      host path, passed to Hole verbatim (Hole expands `~` and resolves it)
 * @param readWrite {@code true} appends `:rw`, otherwise Hole mounts the directory read-only
 */
public record Library(@NotNull String path, boolean readWrite) {

    /**
     * Returns the value of the `--library` argument, i.e. the path with the `:rw` suffix when
     * the library is writable.
     */
    @NotNull
    public String toArgument() {
        return this.readWrite ? this.path.trim() + ":rw" : this.path.trim();
    }

    /**
     * Returns {@code true} when the row has no path. Such rows come from empty form rows and are
     * left out of the command.
     */
    public boolean isBlank() {
        return this.path.isBlank();
    }
}
