package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.diagnostic.Logger;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.process.CliException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The per-sandbox directories of the image paste: `~/.hole/idea-paste/<instance ID>/`, mounted read-only into the
 * sandbox at {@link io.github.lukashornych.hole.idea.hole.HoleCommandBuilder#PASTE_MOUNT}. Under `$HOME`, because
 * Docker Desktop, Colima, Lima and Podman machines share it; Hole itself never touches it (its garbage collection
 * removes only its own run directories), so the plugin creates the directory before the spawn, deletes it when the
 * process has exited and sweeps the ones a closed project or a killed IDE left behind.
 *
 * <p>The root is created 0700 (screenshots are not for other local users); an instance directory is 0755 and its
 * files 0644, so a container user with another UID can read them.</p>
 */
public final class PasteDirectory {

    /** How old an unused directory must be before the sweep removes it; protects a start in progress. */
    static final Duration STALE_AFTER = Duration.ofMinutes(10);
    /** The root as shown in the command preview. */
    public static final String DISPLAY_ROOT = "~/.hole/idea-paste";

    private static final Logger LOG = Logger.getInstance(PasteDirectory.class);
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    /** {@code null} when the image paste is disabled (tests that do not need it). */
    @Nullable
    private final Path root;
    @NotNull
    private final Clock clock;

    /**
     * @param root  directory that holds the instance directories
     * @param clock time source for the sweep
     */
    public PasteDirectory(@NotNull Path root, @NotNull Clock clock) {
        this.root = root;
        this.clock = clock;
    }

    private PasteDirectory() {
        this.root = null;
        this.clock = Clock.systemUTC();
    }

    /**
     * Returns the directories used in the IDE: `~/.hole/idea-paste` of the JVM's home directory.
     */
    @NotNull
    public static PasteDirectory platform() {
        return new PasteDirectory(Path.of(System.getProperty("user.home"), ".hole", "idea-paste"), Clock.systemUTC());
    }

    /**
     * Returns an instance that never creates a directory, so every sandbox starts without the mount.
     */
    @NotNull
    public static PasteDirectory disabled() {
        return new PasteDirectory();
    }

    /**
     * Returns the directory of an instance, {@code null} when disabled.
     */
    @Nullable
    public Path pathFor(@NotNull String instanceId) {
        return this.root == null ? null : this.root.resolve(instanceId);
    }

    /**
     * Returns {@code true} when Hole can mount the path as a library: `:` would split the `--library` value, and Hole
     * expands environment variables (`$`) in it.
     */
    public static boolean isUsable(@NotNull Path directory) {
        final String text = directory.toString();
        return !text.contains(":") && !text.contains("$");
    }

    /**
     * Creates the (empty) directory of an instance. Never throws: a sandbox without the mount starts as before.
     *
     * @return the directory, or {@code null} when disabled, unusable or not creatable (logged)
     */
    @Nullable
    public Path create(@NotNull String instanceId) {
        final Path directory = pathFor(instanceId);
        if (directory == null) {
            return null;
        }
        if (!isUsable(directory)) {
            LOG.info("Image paste is off for sandbox " + instanceId + ": the paste directory path contains ':' or '$'");
            return null;
        }
        try {
            Files.createDirectories(this.root.getParent());
            if (POSIX) {
                Files.createDirectories(this.root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------"));
                Files.createDirectories(directory);
                // explicit, so the umask cannot hide the directory from a container user with another UID
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
            } else {
                Files.createDirectories(directory);
            }
            return directory;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Image paste is off for sandbox " + instanceId + ": could not create " + directory + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Deletes an instance directory with its images, best-effort (failures are logged; the sweep retries later).
     *
     * @param directory a directory returned by {@link #create(String)}, or {@code null} (nothing happens)
     */
    public void delete(@Nullable Path directory) {
        if (directory == null || this.root == null || !directory.startsWith(this.root) || directory.equals(this.root)) {
            return;
        }
        try {
            try {
                deleteTree(directory);
            } catch (DirectoryNotEmptyException e) {
                // an image stored while the tree was walked
                deleteTree(directory);
            }
        } catch (IOException | RuntimeException e) {
            LOG.info("Could not delete paste directory " + directory + ": " + e.getMessage());
        }
    }

    /**
     * Removes the directories that outlived their sandbox: those whose name is no running instance in `hole list` and
     * that are older than {@link #STALE_AFTER}. Nothing is removed (and Hole is not called) when there is no
     * directory, and nothing when `hole list` fails. Blocking.
     *
     * @param hole lists the running instances of all projects (other IDE windows included)
     * @return the number of directories removed
     */
    public int sweep(@NotNull HoleListClient hole) {
        if (candidates().isEmpty()) {
            return 0;
        }
        final List<InstanceInfo> live;
        try {
            live = hole.listAll();
        } catch (CliException e) {
            LOG.info("Paste directories not swept, 'hole list' failed: " + e.getMessage());
            return 0;
        }
        return sweep(live.stream().map(InstanceInfo::instanceId).toList());
    }

    /**
     * Removes the directories whose name is not in {@code liveInstanceIds} and that are older than
     * {@link #STALE_AFTER}.
     *
     * @return the number of directories removed
     */
    int sweep(@NotNull Collection<String> liveInstanceIds) {
        final Set<String> live = new HashSet<>(liveInstanceIds);
        final Instant limit = this.clock.instant().minus(STALE_AFTER);
        int removed = 0;
        for (Path directory : candidates()) {
            final String name = directory.getFileName().toString();
            try {
                if (live.contains(name) || Files.getLastModifiedTime(directory).toInstant().isAfter(limit)) {
                    continue;
                }
                deleteTree(directory);
                removed++;
            } catch (IOException | RuntimeException e) {
                LOG.info("Could not delete stale paste directory " + directory + ": " + e.getMessage());
            }
        }
        return removed;
    }

    /**
     * Returns the instance directories under the root; anything else there is left alone.
     */
    @NotNull
    private List<Path> candidates() {
        if (this.root == null || !Files.isDirectory(this.root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(this.root)) {
            return children
                .filter(child -> InstanceIdGenerator.isValid(child.getFileName().toString()))
                .filter(Files::isDirectory)
                .toList();
        } catch (IOException e) {
            LOG.info("Could not list paste directories in " + this.root + ": " + e.getMessage());
            return List.of();
        }
    }

    private static void deleteTree(@NotNull Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        final List<Path> paths;
        try (Stream<Path> walk = Files.walk(directory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
