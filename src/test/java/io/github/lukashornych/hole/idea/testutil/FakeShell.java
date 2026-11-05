package io.github.lukashornych.hole.idea.testutil;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The fake login shell from `src/test/resources/fake-shell/shell`, installed into a temporary directory with its rc
 * file and invocation log. Tests point `ShellEnvironment` at {@link #executable()} and put {@link #environment()}
 * into its base environment; switches are changed through that map, exports through {@link #export(String, String)}.
 */
public final class FakeShell {

    private final Path executable;
    private final Path rc;
    private final Path log;
    private final Map<String, String> environment = new ConcurrentHashMap<>();

    private FakeShell(@NotNull Path root) throws IOException {
        Files.createDirectories(root);
        this.executable = root.resolve("shell");
        this.rc = root.resolve("rc");
        this.log = root.resolve("shell.log");
        try (InputStream script = FakeShell.class.getResourceAsStream("/fake-shell/shell")) {
            if (script == null) {
                throw new IllegalStateException("fake-shell/shell is not on the test classpath");
            }
            Files.copy(script, this.executable, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setPosixFilePermissions(this.executable, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(this.rc, "");
        this.environment.put("FAKE_SHELL_RC", this.rc.toString());
        this.environment.put("FAKE_SHELL_LOG", this.log.toString());
    }

    /**
     * Installs the fake shell under {@code root}.
     */
    @NotNull
    public static FakeShell install(@NotNull Path root) {
        try {
            return new FakeShell(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @NotNull
    public Path executable() {
        return this.executable;
    }

    /**
     * Returns the (mutable) switches the fake shell reads from its environment.
     */
    @NotNull
    public Map<String, String> environment() {
        return this.environment;
    }

    /**
     * Appends `export NAME='value'` to the rc file; a later export of the same name wins, like in a real rc file.
     */
    public void export(@NotNull String name, @NotNull String value) {
        try {
            Files.writeString(this.rc, "export " + name + "='" + value.replace("'", "'\\''") + "'\n",
                StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns one line per invocation: `<pid> reader=<marker> <arguments>`.
     */
    @NotNull
    public List<String> calls() {
        try {
            return Files.exists(this.log) ? Files.readAllLines(this.log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
