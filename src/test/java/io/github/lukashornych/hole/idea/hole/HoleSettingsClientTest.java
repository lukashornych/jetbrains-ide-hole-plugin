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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `hole settings get` through the fake `hole` script, which resolves the paths like Hole 2.6.0 does.
 */
class HoleSettingsClientTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private HoleSettingsClient client;
    private Path home;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.home = Files.createDirectories(this.temp.resolve("home")).toRealPath();
        this.project = Files.createDirectories(this.temp.resolve("project")).toRealPath();
        this.hole.environment().put("HOME", this.home.toString());
        this.client = new HoleSettingsClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
    }

    @Test
    void existingGlobalFileIsReported() throws IOException {
        final Path file = write(this.home.resolve(".hole/settings.json"));
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(file, true)), this.client.global());
        assertEquals(List.of("settings get global"), this.hole.calls());
    }

    @Test
    void missingGlobalFileIsReportedWithItsPathFromHolesError() {
        // nothing is created: the dialog only shows where the file would be
        assertEquals(
            Optional.of(new HoleSettingsClient.SettingsFile(this.home.resolve(".hole/settings.json"), false)),
            this.client.global()
        );
        assertEquals(false, Files.exists(this.home.resolve(".hole")));
    }

    @Test
    void globalOverrideFromTheEnvironmentIsResolvedByHole() throws IOException {
        final Path override = write(this.temp.resolve("elsewhere/global.json"));
        this.hole.environment().put("HOLE_SETTINGS_FILE", override.toString());
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(override, true)), this.client.global());

        this.hole.environment().put("HOLE_SETTINGS_FILE", "relative.json");
        assertEquals(Optional.empty(), this.client.global(), "Hole rejects the override: no link");
    }

    @Test
    void projectFileExistingOrMissingWithItsPath() throws IOException {
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(this.project.resolve(".hole/settings.json"), false)),
            this.client.project(this.project), "missing: path from Hole's error");
        final Path file = write(this.project.resolve(".hole/settings.json"));
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(file, true)), this.client.project(this.project));
        assertEquals(Optional.empty(), this.client.project(this.temp.resolve("missing")), "Hole cannot tell");
        assertEquals("settings get project " + this.project, this.hole.calls().get(0));
    }

    @Test
    void projectOverrideFromTheEnvironmentIsResolvedByHole() throws IOException {
        final Path override = write(this.temp.resolve("elsewhere/project.json"));
        this.hole.environment().put("HOLE_PROJECT_SETTINGS_FILE", override.toString());
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(override, true)), this.client.project(this.project));
    }

    @Test
    void createWritesTheTemplateForBothScopesAndNeverOverwrites() throws IOException {
        assertEquals(HoleSettingsClient.Created.CREATED, this.client.create(HoleSettingsClient.Scope.GLOBAL, null).outcome());
        final Path global = this.home.resolve(".hole/settings.json");
        assertTrue(Files.readString(global).contains("$schema"), "Hole's template");
        assertEquals(HoleSettingsClient.Created.CREATED, this.client.create(HoleSettingsClient.Scope.PROJECT, this.project).outcome());
        assertTrue(Files.isRegularFile(this.project.resolve(".hole/settings.json")));
        assertTrue(this.hole.calls().contains("settings create project " + this.project), this.hole.calls().toString());

        Files.writeString(global, "{\"profiles\": {}}");
        final HoleSettingsClient.Creation again = this.client.create(HoleSettingsClient.Scope.GLOBAL, null);
        assertEquals(HoleSettingsClient.Created.ALREADY_EXISTS, again.outcome());
        assertTrue(again.message().startsWith("settings file already exists"), again.message());
        assertEquals("{\"profiles\": {}}", Files.readString(global), "left alone");
    }

    @Test
    void createHonoursTheOverridesAndReportsHolesError() {
        final Path override = this.temp.resolve("elsewhere/nested/global.json");
        this.hole.environment().put("HOLE_SETTINGS_FILE", override.toString());
        assertEquals(HoleSettingsClient.Created.CREATED, this.client.create(HoleSettingsClient.Scope.GLOBAL, null).outcome());
        assertTrue(Files.isRegularFile(override), "parent directories are created");
        final Path projectOverride = this.temp.resolve("elsewhere/project.json");
        this.hole.environment().put("HOLE_PROJECT_SETTINGS_FILE", projectOverride.toString());
        assertEquals(HoleSettingsClient.Created.CREATED, this.client.create(HoleSettingsClient.Scope.PROJECT, this.project).outcome());
        assertTrue(Files.isRegularFile(projectOverride));

        this.hole.environment().put("HOLE_SETTINGS_FILE", "relative.json");
        final HoleSettingsClient.Creation failed = this.client.create(HoleSettingsClient.Scope.GLOBAL, null);
        assertEquals(HoleSettingsClient.Created.FAILED, failed.outcome());
        assertEquals("settings file path 'relative.json' must be an absolute path", failed.message());
    }

    @Test
    void holeThatCannotRunGivesNothing() {
        final HoleSettingsClient missing = new HoleSettingsClient(this.temp.resolve("no/hole").toString(), new CliRunner(this.hole::environment));
        assertEquals(Optional.empty(), missing.global());
        assertEquals(Optional.empty(), missing.project(this.project));
    }

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "{}");
    }
}
