package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Location and capability probe against the fake `hole` script.
 */
class HoleExecutableTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private final AtomicReference<String> configured = new AtomicReference<>("");

    @BeforeEach
    void setUp() {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
    }

    private HoleExecutable executable() {
        return new HoleExecutable(this.configured::get, new CliRunner(this.hole::environment));
    }

    @Test
    void configuredPathIsUsed() {
        this.configured.set(this.hole.executable().toString());
        assertEquals(Optional.of(this.hole.executable()), executable().locate());
    }

    @Test
    void configuredPathWithTildeExpandsAgainstHome() throws IOException {
        final Path home = Files.createDirectories(this.temp.resolve("home"));
        final Path inHome = Files.copy(this.hole.executable(), Files.createDirectories(home.resolve("tools")).resolve("hole"));
        this.hole.environment().put("HOME", home.toString());
        this.configured.set("~/tools/hole");
        assertEquals(Optional.of(inHome), executable().locate());
    }

    @Test
    void invalidConfiguredPathIsNotFoundWithoutFallback() {
        this.hole.environment().put("PATH", this.hole.binDirectory() + ":/usr/bin:/bin");
        this.configured.set(this.temp.resolve("missing/hole").toString());

        final HoleCapability capability = executable().probe();
        assertEquals(HoleCapability.Status.NOT_FOUND, capability.status());
        assertNull(capability.executable());
        assertTrue(capability.message().contains("missing/hole"), capability.message());
    }

    @Test
    void foundOnShellPath() {
        this.hole.environment().put("PATH", "/nonexistent:" + this.hole.binDirectory() + ":/usr/bin:/bin");
        assertEquals(Optional.of(this.hole.executable().toAbsolutePath()), executable().locate());
    }

    @Test
    void fallsBackToLocalBin() throws IOException {
        final Path home = Files.createDirectories(this.temp.resolve("home"));
        final Path localBin = Files.createDirectories(home.resolve(".local/bin"));
        final Path installed = Files.copy(this.hole.executable(), localBin.resolve("hole"));
        this.hole.environment().put("HOME", home.toString());
        this.hole.environment().put("PATH", "/usr/bin:/bin");
        assertEquals(Optional.of(installed), executable().locate());
    }

    @Test
    void nonExecutableFileIsIgnored() throws IOException {
        Files.setPosixFilePermissions(this.hole.executable(), PosixFilePermissions.fromString("rw-r--r--"));
        this.hole.environment().put("PATH", this.hole.binDirectory().toString());
        assertEquals(HoleCapability.Status.NOT_FOUND, executable().probe().status());
    }

    @Test
    void newHoleIsSupportedAndCached() {
        this.configured.set(this.hole.executable().toString());
        final HoleExecutable executable = executable();

        final HoleCapability first = executable.probe();
        assertEquals(HoleCapability.Status.SUPPORTED, first.status());
        assertTrue(first.isSupported());
        assertEquals(this.hole.executable(), first.executable());
        assertEquals(1, this.hole.calls().size());

        assertEquals(HoleCapability.Status.SUPPORTED, executable.probe().status());
        assertEquals(1, this.hole.calls().size(), "positive result is cached");
    }

    @Test
    void changedExecutableIsProbedAgain() throws IOException {
        this.configured.set(this.hole.executable().toString());
        final HoleExecutable executable = executable();
        assertTrue(executable.probe().isSupported());

        // `hole update` replaces the binary: new modification time
        Files.setLastModifiedTime(this.hole.executable(), FileTime.from(Instant.now().plusSeconds(60)));
        this.hole.mode("old");
        assertEquals(HoleCapability.Status.UNSUPPORTED, executable.probe().status());
        assertEquals(2, this.hole.calls().size());
    }

    @Test
    void probeRunsVersionAndNeedsNoContainerEngine() {
        this.configured.set(this.hole.executable().toString());
        this.hole.mode("fail");

        assertEquals(HoleCapability.Status.SUPPORTED, executable().probe().status());
        assertEquals(List.of("version"), this.hole.calls());
    }

    @Test
    void failingVersionCommandIsUnavailable() {
        this.configured.set(this.hole.executable().toString());
        this.hole.environment().put("FAKE_HOLE_VERSION_EXIT", "2");

        final HoleCapability capability = executable().probe();
        assertEquals(HoleCapability.Status.UNAVAILABLE, capability.status());
        assertTrue(capability.canStart(), "Hole shows its own error in the tab");
        assertTrue(capability.message().contains("exit code 2"), capability.message());
    }

    @Test
    void unrecognizedVersionIsUnavailable() {
        this.configured.set(this.hole.executable().toString());
        this.hole.environment().put("FAKE_HOLE_VERSION", "Usage: hole {command}");

        final HoleCapability capability = executable().probe();
        assertEquals(HoleCapability.Status.UNAVAILABLE, capability.status());
        assertTrue(capability.canStart());
    }

    @Test
    void goInstallAndDevelopmentBuildsAreSupported() {
        this.configured.set(this.hole.executable().toString());
        this.hole.environment().put("FAKE_HOLE_VERSION", "hole 2.6.0 (go install)");
        assertEquals(HoleCapability.Status.SUPPORTED, executable().probe().status());

        this.hole.environment().put("FAKE_HOLE_VERSION", "hole development (abc1234, dirty)");
        assertEquals(HoleCapability.Status.SUPPORTED, executable().probe().status());
    }

    @Test
    void olderGoInstallBuildIsUnsupported() {
        this.configured.set(this.hole.executable().toString());
        this.hole.environment().put("FAKE_HOLE_VERSION", "hole 2.5.9 (go install)");

        final HoleCapability capability = executable().probe();
        assertEquals(HoleCapability.Status.UNSUPPORTED, capability.status());
        assertEquals("This plugin needs Hole 2.6.0 or newer. Run `hole update`.", capability.message());
    }

    @Test
    void concurrentProbesShareOneRun() throws Exception {
        // regression: callers probing at the same time must not each spawn `hole version`; negative results are shared too
        this.configured.set(this.hole.executable().toString());
        this.hole.mode("old");
        this.hole.environment().put("FAKE_HOLE_VERSION_DELAY", "0.5");
        final HoleExecutable executable = executable();

        final int callers = 4;
        final java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(callers);
        final java.util.List<java.util.concurrent.Future<HoleCapability>> results = new java.util.ArrayList<>();
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        try {
            for (int i = 0; i < callers; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return executable.probe();
                }));
            }
            for (java.util.concurrent.Future<HoleCapability> result : results) {
                assertEquals(HoleCapability.Status.UNSUPPORTED, result.get(20, java.util.concurrent.TimeUnit.SECONDS).status());
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, this.hole.calls().size(), "one process for all concurrent callers: " + this.hole.calls());
        assertEquals(HoleCapability.Status.UNSUPPORTED, executable.lastCapability().status());

        // a later probe runs again, because negative results are not cached
        executable.probe();
        assertEquals(2, this.hole.calls().size());
    }

    @Test
    void failingWrapperIsUnavailableNotUnsupported() {
        // only a recognized version below 2.6.0 means "too old"; anything else is unknown and does not block starts
        final java.nio.file.Path wrapper = this.temp.resolve("wrapper-hole");
        try {
            Files.writeString(wrapper, "#!/bin/sh\necho \"[ERROR] invalid command 'version'\" >&2\nexit 1\n");
            Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        this.configured.set(wrapper.toString());
        assertEquals(HoleCapability.Status.UNAVAILABLE, executable().probe().status());
    }

    @Test
    void canStartAllowsSupportedAndUnavailableOnly() {
        assertTrue(new HoleCapability(HoleCapability.Status.SUPPORTED, null, "").canStart());
        assertTrue(new HoleCapability(HoleCapability.Status.UNAVAILABLE, null, "").canStart());
        assertTrue(!new HoleCapability(HoleCapability.Status.UNSUPPORTED, null, "").canStart());
        assertTrue(!new HoleCapability(HoleCapability.Status.NOT_FOUND, null, "").canStart());
        assertTrue(!new HoleCapability(HoleCapability.Status.UNAVAILABLE, null, "").isSupported());
    }

    @Test
    void olderHoleIsUnsupportedAndNotCached() {
        this.configured.set(this.hole.executable().toString());
        this.hole.mode("old");
        final HoleExecutable executable = executable();

        final HoleCapability capability = executable.probe();
        assertEquals(HoleCapability.Status.UNSUPPORTED, capability.status());
        assertEquals(this.hole.executable(), capability.executable());
        assertEquals(HoleExecutable.UPDATE_NOTICE, capability.message());
        assertEquals("This plugin needs Hole 2.6.0 or newer. Run `hole update`.", capability.message());

        this.hole.mode(null);
        assertEquals(HoleCapability.Status.SUPPORTED, executable.probe().status(), "negative result is re-probed");
        assertEquals(2, this.hole.calls().size());
    }
}
