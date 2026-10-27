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
    void projectFileOnlyWhenItExists() throws IOException {
        assertEquals(Optional.empty(), this.client.existingProjectFile(this.project));
        final Path file = write(this.project.resolve(".hole/settings.json"));
        assertEquals(Optional.of(file), this.client.existingProjectFile(this.project));
        assertEquals(Optional.empty(), this.client.existingProjectFile(this.temp.resolve("missing")));
        assertEquals("settings get project " + this.project, this.hole.calls().get(0));
    }

    @Test
    void projectOverrideFromTheEnvironmentIsResolvedByHole() throws IOException {
        final Path override = write(this.temp.resolve("elsewhere/project.json"));
        this.hole.environment().put("HOLE_PROJECT_SETTINGS_FILE", override.toString());
        assertEquals(Optional.of(override), this.client.existingProjectFile(this.project));
    }

    @Test
    void holeThatCannotRunGivesNothing() {
        final HoleSettingsClient missing = new HoleSettingsClient(this.temp.resolve("no/hole").toString(), new CliRunner(this.hole::environment));
        assertEquals(Optional.empty(), missing.global());
        assertEquals(Optional.empty(), missing.existingProjectFile(this.project));
    }

    private static Path write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "{}");
    }
}
