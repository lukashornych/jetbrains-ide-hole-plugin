package io.github.lukashornych.hole.idea.process;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Finds executables on the `PATH` of a given environment.
 *
 * <p>The plugin resolves executables itself instead of leaving it to {@link ProcessBuilder}, because the JVM looks
 * them up on the IDE's own `PATH`, which differs from the user's shell `PATH` when the IDE is started from the
 * macOS Dock.</p>
 */
public final class ExecutableLocator {

    private ExecutableLocator() {
    }

    /**
     * Returns the first executable file named {@code name} in the directories of the `PATH` variable of
     * {@code environment}.
     */
    @NotNull
    public static Optional<Path> findOnPath(@NotNull String name, @NotNull Map<String, String> environment) {
        final String path = environment.get("PATH");
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            try {
                final Path candidate = Path.of(directory).resolve(name);
                if (isExecutableFile(candidate)) {
                    return Optional.of(candidate.toAbsolutePath());
                }
            } catch (InvalidPathException ignored) {
                // a malformed PATH entry is skipped like a shell would
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves a command's executable: a value containing a path separator is used as it is, a bare name is looked up
     * on the `PATH` of {@code environment}.
     */
    @NotNull
    public static Optional<Path> resolve(@NotNull String executable, @NotNull Map<String, String> environment) {
        if (executable.contains(File.separator)) {
            final Path path = Path.of(executable);
            return isExecutableFile(path) ? Optional.of(path) : Optional.empty();
        }
        return findOnPath(executable, environment);
    }

    /**
     * Returns {@code true} for a regular file the current user may execute.
     */
    public static boolean isExecutableFile(@NotNull Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }
}
