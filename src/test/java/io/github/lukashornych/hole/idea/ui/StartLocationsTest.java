package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.git.Worktree;
import io.github.lukashornych.hole.idea.git.WorktreeLister;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A favourite starts in the project root unless the project has linked git worktrees; then they are offered after
 * <i>Project root</i>, without the worktree the project root is in.
 */
class StartLocationsTest {

    private static final Path ROOT = Path.of("/work/acme-cms");
    private static final StartLocations.Location PROJECT_ROOT = new StartLocations.Location("Project root", ROOT.toString(), ".");

    @TempDir
    Path temp;

    @Test
    void withoutWorktreesOnlyTheProjectRoot() {
        assertEquals(List.of(PROJECT_ROOT), StartLocations.of(ROOT, List.of()));
        assertEquals(List.of(PROJECT_ROOT), StartLocations.of(ROOT, List.of(branch("/work/acme-cms", "main"))));
    }

    @Test
    void linkedWorktreesFollowTheProjectRootInGitOrder() {
        assertEquals(List.of(
            PROJECT_ROOT,
            new StartLocations.Location("feature/search", "/work/acme-cms-worktrees/search", "/work/acme-cms-worktrees/search"),
            new StartLocations.Location("fix", "/work/acme-cms-worktrees/fix", "/work/acme-cms-worktrees/fix")
        ), StartLocations.of(ROOT, List.of(
            branch("/work/acme-cms", "main"),
            branch("/work/acme-cms-worktrees/search", "feature/search"),
            branch("/work/acme-cms-worktrees/fix", "fix")
        )));
    }

    @Test
    void bareRepositoryIsSkipped() {
        final Path root = Path.of("/work/acme-cms.git-worktrees/main");
        assertEquals(
            List.of("Project root", "feature"),
            labels(StartLocations.of(root, List.of(
                new Worktree("/work/acme-cms.git", null, null, true, false, false, false),
                branch("/work/acme-cms.git-worktrees/main", "main"),
                branch("/work/acme-cms.git-worktrees/feature", "feature")
            )))
        );
    }

    @Test
    void projectRootInALinkedWorktreeOffersTheMainOne() {
        final Path root = Path.of("/work/acme-cms-worktrees/search");
        assertEquals(List.of(
            new StartLocations.Location("Project root", root.toString(), "."),
            new StartLocations.Location("main", "/work/acme-cms", "/work/acme-cms")
        ), StartLocations.of(root, List.of(
            branch("/work/acme-cms", "main"),
            branch("/work/acme-cms-worktrees/search", "feature/search")
        )));
    }

    @Test
    void projectRootInASubDirectoryOrNestedWorktree() {
        final List<Worktree> worktrees = List.of(branch("/work/acme-cms", "main"), branch("/work/acme-cms/.worktrees/search", "search"));
        assertEquals(List.of("Project root", "search"), labels(StartLocations.of(Path.of("/work/acme-cms/module"), worktrees)));
        assertEquals(List.of("Project root", "main"), labels(StartLocations.of(Path.of("/work/acme-cms/.worktrees/search/module"), worktrees)));
    }

    @Test
    void detachedWorktreeIsLabelledByItsFolder() {
        assertEquals(List.of("Project root", "review"), labels(StartLocations.of(ROOT, List.of(
            branch("/work/acme-cms", "main"),
            new Worktree("/work/acme-cms-worktrees/review", "abc", null, false, true, false, false)
        ))));
    }

    @Test
    void realRepositoryWithAWorktreeOpenedThroughASymlink() throws Exception {
        Assumptions.assumeTrue(gitAvailable(), "git is not installed");
        final Path repo = Files.createDirectories(this.temp.resolve("repo"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x");
        git(repo, "add", "README");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "init");
        final WorktreeLister lister = new WorktreeLister(new CliRunner(StartLocationsTest::environment));
        assertEquals(1, StartLocations.of(repo, lister.list(repo)).size(), "only the main worktree: nothing to choose");

        final Path worktree = this.temp.resolve("wt-feature");
        git(repo, "worktree", "add", "-q", "-b", "feature", worktree.toString());
        final Path link = Files.createSymbolicLink(this.temp.resolve("link"), repo);
        final List<StartLocations.Location> locations = StartLocations.of(link, lister.list(link));
        assertEquals(List.of("Project root", "feature"), labels(locations));
        assertEquals(worktree.toRealPath().toString(), locations.get(1).projectPath());
    }

    private static Worktree branch(String path, String branch) {
        return new Worktree(path, "abc", branch, false, false, false, false);
    }

    private static List<String> labels(List<StartLocations.Location> locations) {
        return locations.stream().map(StartLocations.Location::label).toList();
    }

    private static boolean gitAvailable() {
        try {
            return new CliRunner(StartLocationsTest::environment).run(List.of("git", "--version"), null, Duration.ofSeconds(10)).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static Map<String, String> environment() {
        // no global git config of the developer machine influences the test
        return Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), "GIT_CONFIG_NOSYSTEM", "1", "HOME", "/nonexistent");
    }

    private static void git(Path directory, String... args) throws Exception {
        final List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));
        final var result = new CliRunner(StartLocationsTest::environment).run(command, null, Duration.ofSeconds(30));
        assertEquals(0, result.exitCode(), result.stderr());
    }
}
