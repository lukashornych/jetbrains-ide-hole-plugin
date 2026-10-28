package io.github.lukashornych.hole.idea.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * File names, permissions and the size limit of stored pasted images.
 */
class PasteStoreTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:15:03Z"), ZoneOffset.UTC);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    @TempDir
    Path directory;

    private final PasteStore store = new PasteStore(CLOCK);

    @Test
    void storesTheImageAndReturnsItsContainerPath() throws IOException {
        final String path = this.store.store(this.directory, PNG);

        assertEquals("/tmp/hole-idea-paste/20261021-101503-1.png", path);
        final Path file = this.directory.resolve("20261021-101503-1.png");
        assertArrayEquals(PNG, Files.readAllBytes(file));
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(List.of("20261021-101503-1.png"), files());
    }

    @Test
    void namesStayUniqueWithinOneSecond() throws IOException {
        assertEquals("/tmp/hole-idea-paste/20261021-101503-1.png", this.store.store(this.directory, PNG));
        assertEquals("/tmp/hole-idea-paste/20261021-101503-2.png", new PasteStore(CLOCK).store(this.directory, PNG));
        assertEquals("/tmp/hole-idea-paste/20261021-101503-3.png", this.store.store(this.directory, PNG));
    }

    @Test
    void refusesImagesOverTheLimitWithoutLeavingAFile() throws IOException {
        this.store.store(this.directory, new byte[PasteStore.MAX_BYTES]);

        final IOException e = assertThrows(IOException.class, () -> this.store.store(this.directory, new byte[PasteStore.MAX_BYTES + 1]));

        assertTrue(e.getMessage().contains("at most 10.0 MB"), e.getMessage());
        assertEquals(List.of("20261021-101503-1.png"), files());
    }

    @Test
    void failedWriteLeavesNoFile() {
        // the sandbox ended and its directory is gone
        final Path gone = this.directory.resolve("aaaaaa");

        assertThrows(IOException.class, () -> this.store.store(gone, PNG));

        assertEquals(List.of(), files());
    }

    @Test
    void containerPathIsInsideTheMount() {
        assertEquals("/tmp/hole-idea-paste/x.png", PasteStore.containerPath("x.png"));
    }

    private List<String> files() {
        try (Stream<Path> list = Files.list(this.directory)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
