package io.github.lukashornych.hole.idea.hole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoleSettingsLocatorTest {

    @TempDir
    Path temp;

    private final Map<String, String> env = new HashMap<>();
    private HoleSettingsLocator locator;
    private Path home;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        this.home = Files.createDirectories(this.temp.resolve("home"));
        this.project = Files.createDirectories(this.temp.resolve("project"));
        this.env.put("HOME", this.home.toString());
        this.locator = new HoleSettingsLocator(() -> this.env);
    }

    @Test
    void resolvesProjectPathAgainstIdeRoot() {
        assertEquals(Optional.of(this.project), this.locator.resolveProjectDirectory(this.project, "."));
        assertEquals(Optional.of(this.project), this.locator.resolveProjectDirectory(this.project, " "));
        assertEquals(Optional.of(this.temp.resolve("other")), this.locator.resolveProjectDirectory(this.project, "../other"));
        assertEquals(Optional.of(Path.of("/abs/path")), this.locator.resolveProjectDirectory(this.project, "/abs/path"));
        assertEquals(Optional.of(this.home.resolve("src")), this.locator.resolveProjectDirectory(this.project, "~/src"));
        assertEquals(Optional.of(this.home), this.locator.resolveProjectDirectory(this.project, "~"));
    }

    @Test
    void profileAndAgentSuggestionsFromBothFiles() throws IOException {
        final Path global = write(this.home.resolve(".hole/settings.json"), """
            {"profiles": {"research": {}, "docker": {"extends": "research"}},
             "agents": {"claude": {"args": ["--x"]}, "my-agent": {}}}
            """);
        final Path project = write(this.project.resolve(".hole/settings.json"), """
            {"profiles": {"review": {}, "research": {}, "Bad Name": {}}, "agents": {"aider": {}}}
            """);
        assertEquals(List.of("docker", "research", "review"), this.locator.profileNames(List.of(global, project)));
        assertEquals(List.of("claude", "gemini", "codex", "aider", "my-agent"), this.locator.agentNames(List.of(global, project)));
    }

    @Test
    void missingOrMalformedFilesGiveNoSuggestions() throws IOException {
        final Path global = this.home.resolve(".hole/settings.json");
        final Path project = this.project.resolve(".hole/settings.json");
        assertTrue(this.locator.profileNames(List.of(global, project)).isEmpty());
        assertEquals(HoleSettingsLocator.BUILTIN_AGENTS, this.locator.agentNames(List.of(global, project)));
        assertEquals(HoleSettingsLocator.BUILTIN_AGENTS, this.locator.agentNames(List.of()));

        write(global, "{ not json");
        write(project, "{\"profiles\": [\"research\"]}");
        assertTrue(this.locator.profileNames(List.of(global, project)).isEmpty());
        assertEquals(HoleSettingsLocator.BUILTIN_AGENTS, this.locator.agentNames(List.of(global, project)));
    }

    @Test
    void fileAtAnyReportedPathIsRead() throws IOException {
        // e.g. HOLE_PROJECT_SETTINGS_FILE: Hole reports the path, the locator only reads it
        final Path override = write(this.temp.resolve("elsewhere/project.json"), "{\"profiles\": {\"ci\": {}}}");
        assertEquals(List.of("ci"), this.locator.profileNames(List.of(override)));
    }

    private static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
