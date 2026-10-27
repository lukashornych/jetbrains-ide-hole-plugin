package io.github.lukashornych.hole.idea.git;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One entry of `git worktree list --porcelain`.
 *
 * @param path     absolute path of the worktree
 * @param head     commit the worktree is at, {@code null} for a bare repository
 * @param branch   short branch name (without `refs/heads/`), {@code null} when detached or bare
 * @param bare     the main repository is bare
 * @param detached HEAD is detached
 * @param locked   the worktree is locked
 * @param prunable git considers the worktree prunable (its directory is gone)
 */
public record Worktree(
    @NotNull String path,
    @Nullable String head,
    @Nullable String branch,
    boolean bare,
    boolean detached,
    boolean locked,
    boolean prunable
) {
}
