package io.github.lukashornych.hole.idea.git;

import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorktreeListerTest {

    @TempDir
    Path temp;

    @Test
    void parsesPorcelainOutput() {
        final String porcelain = """
            worktree /work/evitaDB
            HEAD 1234567890abcdef1234567890abcdef12345678
            branch refs/heads/main

            worktree /work/evitaDB-worktrees/fix entity index
            HEAD abcdefabcdefabcdefabcdefabcdefabcdefabcd
            branch refs/heads/fix/entity-index
            locked reason why

            worktree /work/evitaDB-worktrees/detached
            HEAD 0000000000000000000000000000000000000000
            detached

            worktree /work/gone
            HEAD 1111111111111111111111111111111111111111
            branch refs/heads/gone
            prunable gitdir file points to non-existent location
            future-attribute value
            """;
        final List<Worktree> worktrees = WorktreeLister.parse(porcelain);
        assertEquals(4, worktrees.size());
        assertEquals(new Worktree("/work/evitaDB", "1234567890abcdef1234567890abcdef12345678", "main", false, false, false, false), worktrees.get(0));
        assertEquals("/work/evitaDB-worktrees/fix entity index", worktrees.get(1).path());
        assertEquals("fix/entity-index", worktrees.get(1).branch());
        assertTrue(worktrees.get(1).locked());
        assertTrue(worktrees.get(2).detached());
        assertNull(worktrees.get(2).branch());
        assertTrue(worktrees.get(3).prunable());
    }

    @Test
    void parsesBareRepositoryAndMissingTrailingBlankLine() {
        final List<Worktree> worktrees = WorktreeLister.parse("worktree /repo.git\nbare\n\nworktree /wt\nHEAD abc\nbranch refs/heads/x");
        assertEquals(2, worktrees.size());
        assertTrue(worktrees.get(0).bare());
        assertNull(worktrees.get(0).head());
        assertEquals("x", worktrees.get(1).branch());
        assertTrue(WorktreeLister.parse("").isEmpty());
    }

    @Test
    void listsRealWorktrees() throws Exception {
        final Path repo = Files.createDirectories(this.temp.resolve("repo with space"));
        git(repo, "init", "-q", "-b", "main");
        Files.writeString(repo.resolve("README"), "x");
        git(repo, "add", "README");
        git(repo, "-c", "user.name=Test", "-c", "user.email=test@example.com", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "init");
        final Path worktree = this.temp.resolve("wt-feature");
        git(repo, "worktree", "add", "-q", "-b", "feature", worktree.toString());

        final List<Worktree> worktrees = new WorktreeLister(new CliRunner(WorktreeListerTest::environment)).list(repo);
        assertEquals(2, worktrees.size());
        assertEquals(repo.toRealPath().toString(), worktrees.get(0).path());
        assertEquals("main", worktrees.get(0).branch());
        assertEquals(worktree.toRealPath().toString(), worktrees.get(1).path());
        assertEquals("feature", worktrees.get(1).branch());
        assertNotNull(worktrees.get(1).head());
        final WorktreeLister lister = new WorktreeLister(new CliRunner(WorktreeListerTest::environment));
        assertFalse(lister.isLinked(repo));
        assertTrue(lister.isLinked(worktree));

        // a removed worktree directory makes the entry prunable; it is not suggested
        deleteRecursively(worktree);
        final List<Worktree> afterRemoval = new WorktreeLister(new CliRunner(WorktreeListerTest::environment)).list(repo);
        assertEquals(1, afterRemoval.size());
        assertFalse(afterRemoval.get(0).prunable());
    }

    @Test
    void linkedIsDecidedByTheInnermostContainingWorktree() {
        final List<Worktree> worktrees = WorktreeLister.parse("""
            worktree /work/app
            HEAD abc

            worktree /work/app-worktrees/fix
            HEAD def

            worktree /work/app/.worktrees/nested
            HEAD 123
            """);
        assertFalse(WorktreeLister.isLinked(worktrees, Path.of("/work/app")));
        assertFalse(WorktreeLister.isLinked(worktrees, Path.of("/work/app/module")), "a subdirectory of the main worktree");
        assertTrue(WorktreeLister.isLinked(worktrees, Path.of("/work/app-worktrees/fix")));
        assertTrue(WorktreeLister.isLinked(worktrees, Path.of("/work/app/.worktrees/nested/module")), "nested in the main checkout");
        assertFalse(WorktreeLister.isLinked(worktrees, Path.of("/work/app-worktrees")), "contained in none of them");
        assertFalse(WorktreeLister.isLinked(List.of(), Path.of("/work/app")));
    }

    @Test
    void notARepository() throws IOException {
        final Path plain = Files.createDirectories(this.temp.resolve("plain"));
        final CliException e = assertThrows(
            CliException.class,
            () -> new WorktreeLister(new CliRunner(WorktreeListerTest::environment)).list(plain)
        );
        assertNotNull(e.getExitCode());
    }

    private static Map<String, String> environment() {
        // no global git config of the developer machine influences the test
        return Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"), "GIT_CONFIG_NOSYSTEM", "1", "HOME", "/nonexistent");
    }

    private static void git(Path directory, String... args) throws Exception {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(args));
        final var result = new CliRunner(WorktreeListerTest::environment).run(command, null, Duration.ofSeconds(30));
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
