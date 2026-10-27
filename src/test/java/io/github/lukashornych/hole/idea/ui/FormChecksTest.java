package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsLocator;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The background checks of the sandbox form, with the settings files located by the fake `hole settings get`.
 */
class FormChecksTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private HoleSettingsClient settings;
    private HoleSettingsLocator locator;
    private Path home;

    @BeforeEach
    void setUp() throws Exception {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.home = Files.createDirectories(this.temp.resolve("home")).toRealPath();
        this.hole.environment().put("HOME", this.home.toString());
        this.settings = new HoleSettingsClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.locator = new HoleSettingsLocator(this.hole::environment);
    }

    @Test
    void projectLinkAndSuggestionsFollowTheSelectedPath() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root")).toRealPath();
        final Path worktree = Files.createDirectories(this.temp.resolve("worktree/.hole")).toRealPath();
        Files.writeString(Files.createDirectories(this.home.resolve(".hole")).resolve("settings.json"),
            "{\"profiles\": {\"research\": {}}, \"agents\": {\"aider\": {}}}");
        Files.writeString(worktree.resolve("settings.json"), "{\"profiles\": {\"review\": {}}}");

        final FormChecks atRoot = FormChecks.run(this.locator, this.settings, root, SandboxConfig.defaults());
        assertEquals(root, atRoot.projectDirectory());
        assertTrue(atRoot.projectDirectoryExists());
        assertEquals(this.home.resolve(".hole/settings.json"), atRoot.globalSettings());
        assertTrue(atRoot.globalSettingsExists());
        assertNull(atRoot.projectSettings(), "no project settings at the root: link hidden");
        assertEquals(List.of("research"), atRoot.profiles());
        assertEquals(List.of("claude", "gemini", "codex", "aider"), atRoot.agents());

        final FormChecks atWorktree = FormChecks.run(this.locator, this.settings, root, SandboxConfig.defaults().withProjectPath("../worktree"));
        assertEquals(worktree.resolve("settings.json"), atWorktree.projectSettings());
        assertEquals(List.of("research", "review"), atWorktree.profiles());
        assertTrue(this.hole.calls().contains("settings get project " + this.temp.resolve("worktree").toRealPath()),
            "Hole is asked for the resolved path: " + this.hole.calls());
    }

    @Test
    void missingGlobalFileKeepsItsPathForTheNotCreatedState() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        final FormChecks checks = FormChecks.run(this.locator, this.settings, root, SandboxConfig.defaults());
        assertEquals(this.home.resolve(".hole/settings.json"), checks.globalSettings());
        assertFalse(checks.globalSettingsExists());
        assertFalse(Files.exists(this.home.resolve(".hole")), "nothing is created");
    }

    @Test
    void withoutHoleThereAreNoLinks() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        Files.writeString(Files.createDirectories(root.resolve(".hole")).resolve("settings.json"), "{\"profiles\": {\"ci\": {}}}");
        final FormChecks checks = FormChecks.run(this.locator, null, root, SandboxConfig.defaults());
        assertNull(checks.globalSettings());
        assertFalse(checks.globalSettingsExists());
        assertNull(checks.projectSettings());
        assertEquals(HoleSettingsLocator.BUILTIN_AGENTS, checks.agents());
        assertEquals(List.of(), checks.profiles());
    }

    @Test
    void missingDirectoryAndLibraries() throws Exception {
        final Path root = Files.createDirectories(this.temp.resolve("root"));
        Files.createDirectories(root.resolve("lib"));
        final SandboxConfig config = SandboxConfig.defaults().withProjectPath("nope")
            .withLibraries(List.of(new Library("lib", false), new Library("/does/not/exist", true), new Library(" ", false)));

        final FormChecks checks = FormChecks.run(this.locator, this.settings, root, config);
        assertFalse(checks.projectDirectoryExists());
        assertFalse(checks.globalSettingsExists());
        assertNull(checks.projectSettings());
        assertEquals(Set.of("/does/not/exist"), checks.missingLibraries());
    }
}
