package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCommandBuilder;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Writes pasted images into a sandbox's paste directory (see {@link PasteDirectory}) and returns the path the sandbox
 * sees them under. The directory is read-only for the sandbox and the plugin is its only writer, so no symlink handling
 * is needed; a file appears atomically (temp file and move), so the agent never reads half an image.
 */
public final class PasteStore {

    /** Largest encoded image that can be pasted. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    /** Serializes the choice of a free file name across all stores. */
    private static final Object LOCK = new Object();

    private final Clock clock;

    /**
     * @param clock time source of the file names (its zone is used)
     */
    public PasteStore(@NotNull Clock clock) {
        this.clock = clock;
    }

    /**
     * Stores a PNG image as `<yyyyMMdd-HHmmss>-<n>.png` (0644).
     *
     * @param directory paste directory of the sandbox
     * @param png       encoded image
     * @return the container path of the file, e.g. `/tmp/hole-idea-paste/20261027-101503-1.png`
     * @throws IOException when the image is larger than {@link #MAX_BYTES} or cannot be written; no file is left then
     */
    @NotNull
    public String store(@NotNull Path directory, byte @NotNull [] png) throws IOException {
        if (png.length > MAX_BYTES) {
            throw new TooLargeException("The image is " + megabytes(png.length) + " MB as PNG; at most "
                + megabytes(MAX_BYTES) + " MB can be pasted.");
        }
        final Path temp = Files.createTempFile(directory, ".paste-", ".tmp");
        try {
            Files.write(temp, png);
            if (POSIX) {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-r--r--"));
            }
            final String prefix = LocalDateTime.now(this.clock).format(NAME) + "-";
            synchronized (LOCK) {
                Path target;
                int n = 1;
                do {
                    target = directory.resolve(prefix + n++ + ".png");
                } while (Files.exists(target));
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                return containerPath(target.getFileName().toString());
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Returns the path a file of the paste directory has inside the sandbox.
     */
    @NotNull
    public static String containerPath(@NotNull String fileName) {
        return HoleCommandBuilder.PASTE_MOUNT + "/" + fileName;
    }

    @NotNull
    private static String megabytes(int bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    /**
     * The image exceeds {@link #MAX_BYTES}; the message is meant for the user.
     */
    public static final class TooLargeException extends IOException {

        public TooLargeException(@NotNull String message) {
            super(message);
        }
    }
}
