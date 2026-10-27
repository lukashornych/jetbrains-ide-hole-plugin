package io.github.lukashornych.hole.idea.testutil;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

/**
 * The fake `hole` script from `src/test/resources/fake-hole/hole`, installed into a temporary directory together with
 * its state directory. Tests point the plugin at {@link #executable()} and pass {@link #environment()} as the
 * process environment.
 */
public final class FakeHole {

    private final Path binDirectory;
    private final Path executable;
    private final Path stateDirectory;
    private final Map<String, String> environment = new ConcurrentHashMap<>();

    private FakeHole(@NotNull Path root) throws IOException {
        this.binDirectory = Files.createDirectories(root.resolve("bin"));
        this.stateDirectory = Files.createDirectories(root.resolve("state"));
        this.executable = this.binDirectory.resolve("hole");
        try (InputStream script = FakeHole.class.getResourceAsStream("/fake-hole/hole")) {
            if (script == null) {
                throw new IllegalStateException("fake-hole/hole is not on the test classpath");
            }
            Files.copy(script, this.executable, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setPosixFilePermissions(this.executable, PosixFilePermissions.fromString("rwxr-xr-x"));
        // a minimal environment: the system tools, the state directory, nothing from the IDE
        this.environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        this.environment.put("HOME", root.toString());
        this.environment.put("FAKE_HOLE_STATE_DIR", this.stateDirectory.toString());
    }

    /**
     * Installs the fake script under {@code root}.
     */
    @NotNull
    public static FakeHole install(@NotNull Path root) {
        try {
            return new FakeHole(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @NotNull
    public Path executable() {
        return this.executable;
    }

    @NotNull
    public Path binDirectory() {
        return this.binDirectory;
    }

    /**
     * Returns the (mutable) environment the fake expects.
     */
    @NotNull
    public Map<String, String> environment() {
        return this.environment;
    }

    /**
     * Sets {@code FAKE_HOLE_MODE} (`old` for a Hole before 2.6.0, `fail` for `list` without a container engine, or
     * {@code null} for normal behaviour).
     */
    @NotNull
    public FakeHole mode(String mode) {
        if (mode == null) {
            this.environment.remove("FAKE_HOLE_MODE");
        } else {
            this.environment.put("FAKE_HOLE_MODE", mode);
        }
        return this;
    }

    /**
     * Registers an instance as if a sandbox were running.
     */
    public void putInstance(@NotNull String id, @NotNull String status) {
        final String json = """
            {"instanceId": "%s", "instanceName": "demo-%s", "status": "%s", "agent": "claude", "profile": "",
             "projectPath": "/work/demo", "projectName": "demo", "startedAt": "2026-10-21T10:14:03+02:00",
             "uptimeSeconds": 5, "docker": false, "networks": [], "settings": ["global(~/.hole/settings.json)"],
             "settingsFiles": [], "flags": {"debug": false, "rebuild": false, "unrestricted": false,
             "dumpNetworkAccess": false, "withDocker": false}, "pid": 4242, "version": "2.6.0"}
            """.formatted(id, id, status);
        try {
            final Path instances = Files.createDirectories(this.stateDirectory.resolve("instances"));
            final Path tmp = instances.resolve(id + ".json.tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, instances.resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Leaves leftovers of an abandoned sandbox with this instance ID: `start --instance-id <id>` then fails like Hole.
     */
    public void putLeftovers(@NotNull String id) {
        try {
            Files.writeString(Files.createDirectories(this.stateDirectory.resolve("leftovers")).resolve(id), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the state directory (`FAKE_HOLE_STATE_DIR`).
     */
    @NotNull
    public Path stateDirectory() {
        return this.stateDirectory;
    }

    /**
     * Unregisters an instance.
     */
    public void removeInstance(@NotNull String id) {
        try {
            Files.deleteIfExists(this.stateDirectory.resolve("instances").resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the argument lines of all invocations so far.
     */
    @NotNull
    public List<String> calls() {
        final Path log = this.stateDirectory.resolve("calls.log");
        try {
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
