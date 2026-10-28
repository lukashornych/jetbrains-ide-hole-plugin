package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Creation, deletion and the stale sweep of the image paste directories.
 */
class PasteDirectoryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");

    @TempDir
    Path temp;

    private Path root;
    private PasteDirectory pastes;

    @BeforeEach
    void setUp() {
        this.root = this.temp.resolve("home/.hole/idea-paste");
        this.pastes = new PasteDirectory(this.root, new MutableClock(NOW));
    }

    @Test
    void createsTheInstanceDirectoryInsideAPrivateRoot() throws IOException {
        final Path directory = this.pastes.create("aaaaaa");

        assertEquals(this.root.resolve("aaaaaa"), directory);
        assertTrue(Files.isDirectory(directory));
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(this.root)));
        assertEquals("rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)));
    }

    @Test
    void anExistingRootIsMadePrivate() throws IOException {
        Files.createDirectories(this.root);
        Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwxr-xr-x"));

        this.pastes.create("aaaaaa");

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(this.root)));
    }

    @Test
    void pathsHoleCannotMountAreNotUsed() {
        assertFalse(PasteDirectory.isUsable(Path.of("/home/a:b/.hole/idea-paste/aaaaaa")));
        assertFalse(PasteDirectory.isUsable(Path.of("/home/$USER/.hole/idea-paste/aaaaaa")));
        assertTrue(PasteDirectory.isUsable(Path.of("/home/dev/.hole/idea-paste/aaaaaa")));

        final PasteDirectory colon = new PasteDirectory(this.temp.resolve("a:b"), new MutableClock(NOW));
        assertNull(colon.create("aaaaaa"));
        assertFalse(Files.exists(this.temp.resolve("a:b")));
    }

    @Test
    void anUncreatableDirectoryMeansNoMount() throws IOException {
        // a file where the root should be
        Files.createDirectories(this.root.getParent());
        Files.writeString(this.root, "");

        assertNull(this.pastes.create("aaaaaa"));
    }

    @Test
    void disabledCreatesNothing() {
        assertNull(PasteDirectory.disabled().create("aaaaaa"));
        assertNull(PasteDirectory.disabled().pathFor("aaaaaa"));
    }

    @Test
    void deleteRemovesTheImagesOnlyInsideTheRoot() throws IOException {
        final Path directory = this.pastes.create("aaaaaa");
        Files.writeString(directory.resolve("20261021-100000-1.png"), "png");
        final Path outside = Files.createDirectories(this.temp.resolve("other"));

        this.pastes.delete(directory);
        this.pastes.delete(outside);
        this.pastes.delete(this.root);
        this.pastes.delete(null);

        assertFalse(Files.exists(directory));
        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(this.root));
    }

    @Test
    void sweepRemovesOnlyOldDirectoriesOfEndedSandboxes() throws IOException {
        final Path ended = oldDirectory("aaaaaa");
        final Path live = oldDirectory("bbbbbb");
        final Path young = this.pastes.create("cccccc");
        Files.setLastModifiedTime(young, FileTime.from(NOW.minus(Duration.ofMinutes(9))));
        final Path foreign = Files.createDirectories(this.root.resolve("notes"));
        Files.setLastModifiedTime(foreign, FileTime.from(NOW.minus(Duration.ofDays(1))));

        assertEquals(1, this.pastes.sweep(List.of("bbbbbb")));

        assertFalse(Files.exists(ended));
        assertTrue(Files.exists(live), "a running sandbox, maybe of another IDE window");
        assertTrue(Files.exists(young), "a start in progress");
        assertTrue(Files.exists(foreign), "not an instance directory");
    }

    @Test
    void sweepAsksHoleForTheRunningInstances() throws IOException {
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake"));
        hole.putInstance("bbbbbb", "running");
        final Path ended = oldDirectory("aaaaaa");
        final Path live = oldDirectory("bbbbbb");

        assertEquals(1, this.pastes.sweep(client(hole)));

        assertFalse(Files.exists(ended));
        assertTrue(Files.exists(live));
    }

    @Test
    void sweepKeepsEverythingWhenHoleListFails() throws IOException {
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake")).mode("fail");
        final Path ended = oldDirectory("aaaaaa");

        assertEquals(0, this.pastes.sweep(client(hole)));

        assertTrue(Files.exists(ended));
    }

    @Test
    void sweepWithoutDirectoriesDoesNotCallHole() {
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake"));

        assertEquals(0, this.pastes.sweep(client(hole)));

        assertEquals(List.of(), hole.calls());
    }

    private Path oldDirectory(String instanceId) throws IOException {
        final Path directory = this.pastes.create(instanceId);
        Files.writeString(directory.resolve("20261021-090000-1.png"), "png");
        Files.setLastModifiedTime(directory, FileTime.from(NOW.minus(Duration.ofMinutes(11))));
        return directory;
    }

    private static HoleListClient client(FakeHole hole) {
        return new HoleListClient(hole.executable().toString(), new CliRunner(hole::environment));
    }
}
