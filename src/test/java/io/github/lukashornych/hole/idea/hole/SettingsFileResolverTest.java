package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The get → ask → create → get flow against the fake `hole` script, with `HOLE_SETTINGS_FILE` in a temp directory.
 */
class SettingsFileResolverTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private SettingsFileResolver resolver;
    private Path global;
    private Path project;
    private final List<Path> asked = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.global = this.temp.resolve("config/hole/settings.json");
        this.project = Files.createDirectories(this.temp.resolve("project")).toRealPath();
        this.hole.environment().put("HOLE_SETTINGS_FILE", this.global.toString());
        this.resolver = new SettingsFileResolver(
            new HoleSettingsClient(this.hole.executable().toString(), new CliRunner(this.hole::environment))
        );
    }

    @Test
    void existingFileIsOpenedWithoutAsking() throws IOException {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{}");
        final SettingsFileResolver.Result result = this.resolver.resolve(HoleSettingsClient.Scope.GLOBAL, null, ask(true));
        assertEquals(SettingsFileResolver.Outcome.EXISTS, result.outcome());
        assertEquals(this.global, result.path());
        assertTrue(result.openable());
        assertEquals(List.of(), this.asked);
    }

    @Test
    void missingFileIsCreatedAfterConfirmation() {
        final SettingsFileResolver.Result result = this.resolver.resolve(HoleSettingsClient.Scope.PROJECT, this.project, ask(true));
        final Path file = this.project.resolve(".hole/settings.json");
        assertEquals(List.of(file), this.asked, "asked with the path Hole reported");
        assertEquals(SettingsFileResolver.Outcome.CREATED, result.outcome());
        assertEquals(file, result.path());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void missingFileIsNotCreatedWhenCancelled() {
        final SettingsFileResolver.Result result = this.resolver.resolve(HoleSettingsClient.Scope.GLOBAL, null, ask(false));
        assertEquals(SettingsFileResolver.Outcome.CANCELLED, result.outcome());
        assertFalse(result.openable());
        assertFalse(Files.exists(this.global));
        assertTrue(this.hole.calls().stream().noneMatch(call -> call.startsWith("settings create")), this.hole.calls().toString());
    }

    @Test
    void fileCreatedMeanwhileIsOpenedAsIs() {
        // the user (or another tool) creates the file while the question is open
        final SettingsFileResolver.Result result = this.resolver.resolve(HoleSettingsClient.Scope.GLOBAL, null, path -> {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "{\"mine\": true}");
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            return true;
        });
        assertEquals(SettingsFileResolver.Outcome.CREATED, result.outcome());
        assertEquals(this.global, result.path());
    }

    @Test
    void otherFailuresCarryHolesMessage() {
        this.hole.environment().put("HOLE_SETTINGS_FILE", "relative.json");
        final SettingsFileResolver.Result result = this.resolver.resolve(HoleSettingsClient.Scope.GLOBAL, null, ask(true));
        assertEquals(SettingsFileResolver.Outcome.FAILED, result.outcome());
        assertEquals("settings file path 'relative.json' must be an absolute path", result.message());
        assertEquals(List.of(), this.asked);
    }

    private java.util.function.Predicate<Path> ask(boolean answer) {
        return path -> {
            this.asked.add(path);
            return answer;
        };
    }
}
