package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.git.Worktree;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a favourite can start: the IDE project root first, then every other non-bare git worktree of the project.
 * A worktree starts at its top level, also when the IDE project is a sub-directory of its repository.
 */
final class StartLocations {

    private StartLocations() {
    }

    /**
     * Returns the locations; a single one means there is nothing to choose.
     *
     * @param projectRoot IDE project root
     * @param worktrees   `git worktree list` of the project root (the main worktree included), empty when unknown
     */
    @NotNull
    static List<Location> of(@NotNull Path projectRoot, @NotNull List<Worktree> worktrees) {
        final List<Location> locations = new ArrayList<>();
        locations.add(new Location("Project root", projectRoot.toString(), SandboxConfig.DEFAULT_PROJECT_PATH));
        final Path root = real(projectRoot);
        Worktree own = null;
        int ownLength = -1;
        for (Worktree worktree : worktrees) {
            final Path path = realOrNull(worktree.path());
            if (!worktree.bare() && path != null && root.startsWith(path) && path.getNameCount() > ownLength) {
                own = worktree;
                ownLength = path.getNameCount();
            }
        }
        for (Worktree worktree : worktrees) {
            if (worktree.bare() || worktree == own) {
                continue;
            }
            final String label;
            if (worktree.branch() != null) {
                label = worktree.branch();
            } else {
                final Path name = Path.of(worktree.path()).getFileName();
                label = name == null ? worktree.path() : name.toString();
            }
            locations.add(new Location(label, worktree.path(), worktree.path()));
        }
        return List.copyOf(locations);
    }

    /**
     * Git prints real paths, while the IDE project root may go through a symlink (e.g. `/var` → `/private/var` on
     * macOS).
     */
    @NotNull
    private static Path real(@NotNull Path path) {
        final Path normalized = path.toAbsolutePath().normalize();
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            return normalized;
        }
    }

    @Nullable
    private static Path realOrNull(@NotNull String path) {
        try {
            return real(Path.of(path));
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * One choice.
     *
     * @param label       <i>Project root</i>, the branch of a worktree, or its folder name when detached
     * @param secondary   absolute directory, shown in grey
     * @param projectPath the project path to start with: `.` for the project root, the worktree path otherwise
     */
    record Location(@NotNull String label, @NotNull String secondary, @NotNull String projectPath) {
    }
}
