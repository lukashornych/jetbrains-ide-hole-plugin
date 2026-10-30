package io.github.lukashornych.hole.idea.git;

import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Lists the git worktrees of a project through the `git` CLI (`git worktree list --porcelain`), so the plugin does not
 * depend on Git4Idea. Blocking; call off the EDT.
 */
public final class WorktreeLister {

    /** `git worktree list` is local and fast. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final String BRANCH_PREFIX = "refs/heads/";

    private final CliRunner runner;

    /**
     * @param runner runs `git` with the user's shell environment
     */
    public WorktreeLister(@NotNull CliRunner runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Returns the worktrees of the repository containing {@code directory}. Prunable worktrees are left out, because
     * their directory no longer exists.
     *
     * @throws CliException when git is not installed, the directory is not in a git repository, or git fails
     */
    @NotNull
    public List<Worktree> list(@NotNull Path directory) throws CliException {
        final List<String> command = List.of("git", "-C", directory.toString(), "worktree", "list", "--porcelain");
        final CliResult result = this.runner.run(command, null, TIMEOUT);
        if (!result.isSuccess()) {
            throw new CliException(result, "`git worktree list` failed");
        }
        return parse(result.stdout()).stream().filter(worktree -> !worktree.prunable()).toList();
    }

    /**
     * Returns whether {@code directory} lies in a linked worktree rather than in the main one.
     *
     * @throws CliException when git is not installed, the directory is not in a git repository, or git fails
     */
    public boolean isLinked(@NotNull Path directory) throws CliException {
        Path real;
        try {
            // git prints physical paths (e.g. /tmp is a symlink on macOS)
            real = directory.toRealPath();
        } catch (IOException e) {
            real = directory.toAbsolutePath().normalize();
        }
        return isLinked(list(directory), real);
    }

    /**
     * Returns whether {@code directory} lies in a linked worktree: the innermost worktree containing it is not the
     * first one, which git always lists as the main worktree. A directory in none of them counts as the main one.
     */
    public static boolean isLinked(@NotNull List<Worktree> worktrees, @NotNull Path directory) {
        int containing = -1;
        int depth = -1;
        for (int i = 0; i < worktrees.size(); i++) {
            final Path path = Path.of(worktrees.get(i).path());
            // innermost wins: a linked worktree may be nested inside the main checkout
            if (directory.startsWith(path) && path.getNameCount() > depth) {
                containing = i;
                depth = path.getNameCount();
            }
        }
        return containing > 0;
    }

    /**
     * Parses the porcelain format: records separated by blank lines, each starting with `worktree &lt;path&gt;`,
     * followed by `HEAD &lt;sha&gt;`, `branch &lt;ref&gt;`, `bare`, `detached`, `locked [reason]` and
     * `prunable [reason]` lines. Unknown lines are ignored.
     */
    @NotNull
    public static List<Worktree> parse(@NotNull String porcelain) {
        final List<Worktree> worktrees = new ArrayList<>();
        Builder current = null;
        for (String rawLine : porcelain.split("\\R", -1)) {
            final String line = rawLine.stripTrailing();
            if (line.isEmpty()) {
                if (current != null) {
                    worktrees.add(current.build());
                    current = null;
                }
                continue;
            }
            final int space = line.indexOf(' ');
            final String key = space < 0 ? line : line.substring(0, space);
            final String value = space < 0 ? "" : line.substring(space + 1);
            if (key.equals("worktree")) {
                if (current != null) {
                    worktrees.add(current.build());
                }
                current = new Builder(value);
                continue;
            }
            if (current == null) {
                continue;
            }
            switch (key) {
                case "HEAD" -> current.head = value;
                case "branch" -> current.branch = value.startsWith(BRANCH_PREFIX) ? value.substring(BRANCH_PREFIX.length()) : value;
                case "bare" -> current.bare = true;
                case "detached" -> current.detached = true;
                case "locked" -> current.locked = true;
                case "prunable" -> current.prunable = true;
                default -> {
                    // newer git versions may add attributes
                }
            }
        }
        if (current != null) {
            worktrees.add(current.build());
        }
        return List.copyOf(worktrees);
    }

    private static final class Builder {
        private final String path;
        private String head;
        private String branch;
        private boolean bare;
        private boolean detached;
        private boolean locked;
        private boolean prunable;

        private Builder(@NotNull String path) {
            this.path = path;
        }

        @NotNull
        private Worktree build() {
            return new Worktree(this.path, this.head, this.branch, this.bare, this.detached, this.locked, this.prunable);
        }
    }
}
