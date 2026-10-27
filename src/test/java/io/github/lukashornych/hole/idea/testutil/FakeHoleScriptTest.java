package io.github.lukashornych.hole.idea.testutil;

import com.intellij.execution.process.UnixProcessManager;
import io.github.lukashornych.hole.idea.hole.HoleCommandBuilder;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the fake `hole start` lifecycle the later session tests rely on: status transitions, SIGINT teardown with
 * exit code 130, start failures, duplicate IDs and the hang mode. Runs without a pty (plain {@link ProcessBuilder});
 * the signal goes through {@link UnixProcessManager} exactly as the plugin will send it.
 */
class FakeHoleScriptTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private HoleListClient client;
    private final List<Process> processes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
        this.hole.environment().put("FAKE_HOLE_RUNNING_AFTER", "0.3");
    }

    @AfterEach
    void tearDown() {
        this.processes.forEach(Process::destroyForcibly);
    }

    @Test
    void startRunsThroughStatusesAndSigintTearsDown() throws Exception {
        final Process process = start("abc123");
        waitForStatus("abc123", InstanceStatus.RUNNING);

        final InstanceInfo info = this.client.list("abc123").get(0);
        assertEquals(process.pid(), info.pid(), "pid is the `hole start` process the plugin signals");
        assertEquals(this.temp.toRealPath().toString(), info.projectPath(), "`.` resolved against the working directory");

        assertEquals(0, UnixProcessManager.sendSignal((int) process.pid(), UnixProcessManager.SIGINT));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "teardown finishes");
        assertEquals(130, process.exitValue());
        assertTrue(this.client.listAll().isEmpty());
        assertTrue(output(process).contains("Instance ID: abc123"));
    }

    @Test
    void exitFromWithinEndsWithZero() throws Exception {
        this.hole.environment().put("FAKE_HOLE_EXIT_AFTER", "0.6");
        final Process process = start("abc123");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertTrue(this.client.listAll().isEmpty());
    }

    @Test
    void startFailureExitsWithConfiguredCode() throws Exception {
        this.hole.environment().put("FAKE_HOLE_START_EXIT", "1");
        final Process process = start("abc123");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertTrue(this.client.listAll().isEmpty());
    }

    @Test
    void duplicateInstanceIdIsRejected() throws Exception {
        this.hole.putInstance("abc123", "running");
        final Process process = start("abc123");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertEquals("[ERROR] instance ID 'abc123' is already in use by /work/demo\n",
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void leftoversOfAnAbandonedSandboxAreRejected() throws Exception {
        this.hole.putLeftovers("abc123");
        final Process process = start("abc123");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertEquals("[ERROR] instance ID 'abc123' has leftovers of an abandoned sandbox; run 'hole list' to clean them up\n",
            new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void versionIsConfigurable() throws Exception {
        final CliRunner runner = new CliRunner(this.hole::environment);
        final String executable = this.hole.executable().toString();
        assertEquals("hole 2.6.0\n", runner.run(List.of(executable, "version"), null, Duration.ofSeconds(10)).stdout());
        this.hole.mode("old");
        assertEquals("hole 2.5.0\n", runner.run(List.of(executable, "version"), null, Duration.ofSeconds(10)).stdout());
        this.hole.environment().put("FAKE_HOLE_VERSION", "hole development (abc1234)");
        assertEquals("hole development (abc1234)\n", runner.run(List.of(executable, "version"), null, Duration.ofSeconds(10)).stdout());
    }

    @Test
    void olderHoleRejectsTheNewOptions() throws Exception {
        this.hole.mode("old");
        final CliResult list = new CliRunner(this.hole::environment)
            .run(HoleCommandBuilder.listJson(this.hole.executable().toString(), null), null, Duration.ofSeconds(10));
        assertEquals(1, list.exitCode());
        assertEquals("[ERROR] unknown option '--json'; run 'hole help' for usage", list.firstStderrLine());

        final Process process = start("abc123");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(1, process.exitValue());
        assertTrue(new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).contains("unknown option '--instance-id'"));
    }

    @Test
    void hangModeIgnoresSigintAndNeedsSigkill() throws Exception {
        this.hole.environment().put("FAKE_HOLE_HANG", "1");
        final Process process = start("abc123");
        waitForStatus("abc123", InstanceStatus.RUNNING);

        UnixProcessManager.sendSignal((int) process.pid(), UnixProcessManager.SIGINT);
        assertTrue(!process.waitFor(800, TimeUnit.MILLISECONDS), "SIGINT is ignored");

        UnixProcessManager.sendSignal((int) process.pid(), UnixProcessManager.SIGKILL);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(137, process.exitValue());
    }

    private Process start(String id) throws IOException {
        final List<String> command = HoleCommandBuilder.start(this.hole.executable().toString(), SandboxConfig.defaults(), id);
        final ProcessBuilder builder = new ProcessBuilder(command).directory(this.temp.toFile());
        builder.environment().clear();
        builder.environment().putAll(this.hole.environment());
        builder.redirectOutput(Files.createTempFile(this.temp, "out", ".log").toFile());
        final Process process = builder.start();
        this.processes.add(process);
        return process;
    }

    private String output(Process process) throws IOException {
        // stdout is redirected to the newest out*.log file
        try (var files = Files.list(this.temp)) {
            final Path log = files.filter(p -> p.getFileName().toString().startsWith("out")).findFirst().orElseThrow();
            return Files.readString(log);
        }
    }

    private void waitForStatus(String id, InstanceStatus status) throws CliException, InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            final List<InstanceInfo> instances = this.client.list(id);
            if (!instances.isEmpty() && instances.get(0).status() == status) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Instance " + id + " did not reach " + status);
    }
}
