package io.github.lukashornych.hole.idea.session;

import com.intellij.execution.process.UnixProcessManager;
import com.pty4j.PtyProcess;
import io.github.lukashornych.hole.idea.hole.HoleCommandBuilder;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.github.lukashornych.hole.idea.testutil.Waiting.sleep;
import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hole's stop contract against the fake `hole` script in a real pty: `hole start` leads its own session and process
 * group, and {@link ProcessSignaller#PROCESS_GROUP} reaches both Hole and the child it waits for (like an image
 * build), while a signal to the `hole` process alone does not reach the child, so Hole would wait for it.
 */
class ProcessGroupSignalTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private Path project;
    private final List<PtyProcess> processes = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.project = Files.createDirectories(this.temp.resolve("demo"));
        this.hole.environment().put("FAKE_HOLE_CHILD", "1");
    }

    @AfterEach
    void tearDown() {
        for (PtyProcess process : this.processes) {
            if (process.isAlive()) {
                ProcessSignaller.PROCESS_GROUP.send(process.pid(), ProcessSignaller.SIGKILL);
            }
        }
    }

    @Test
    void ptyProcessLeadsItsOwnSessionAndProcessGroup() throws Exception {
        final PtyProcess process = spawn("aaaaaa");
        final long pid = process.pid();
        assertEquals(pid, processGroup(pid), "pgid == pid");
        final Path stat = Path.of("/proc", Long.toString(pid), "stat");
        if (Files.exists(stat)) {
            // Linux: fields after the command are state, ppid, pgrp, session
            final String[] fields = Files.readString(stat).substring(Files.readString(stat).lastIndexOf(')') + 2).split(" ");
            assertEquals(Long.toString(pid), fields[3], "sid == pid: a session leader");
        }

        final long child = childPid();
        assertEquals(pid, processGroup(child), "the child stays in Hole's process group");
    }

    @Test
    void groupSigintReachesHoleAndItsChild() throws Exception {
        final PtyProcess process = spawn("aaaaaa");
        childPid();

        assertTrue(ProcessSignaller.PROCESS_GROUP.send(process.pid(), ProcessSignaller.SIGINT));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Hole tears down at once");
        assertEquals(130, process.exitValue());
        assertTrue(Files.exists(childInterrupted()), "the child got SIGINT");
    }

    @Test
    void processOnlySigintDoesNotReachTheChildAndHoleWaitsForIt() throws Exception {
        final PtyProcess process = spawn("aaaaaa");
        childPid();

        assertEquals(0, UnixProcessManager.sendSignal((int) process.pid(), UnixProcessManager.SIGINT));
        sleep(1000);
        assertTrue(process.isAlive(), "Hole waits for the running child before it handles the signal");
        assertFalse(Files.exists(childInterrupted()), "the child did not get the signal");

        assertTrue(ProcessSignaller.PROCESS_GROUP.send(process.pid(), ProcessSignaller.SIGINT));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(130, process.exitValue());
        assertTrue(Files.exists(childInterrupted()));
    }

    @Test
    void groupSigkillEndsHoleAndItsChild() throws Exception {
        final PtyProcess process = spawn("aaaaaa");
        final long child = childPid();

        assertTrue(ProcessSignaller.PROCESS_GROUP.send(process.pid(), ProcessSignaller.SIGKILL));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(137, process.exitValue());
        waitUntil(() -> isGone(child), "child killed with the group");
        assertFalse(Files.exists(childInterrupted()), "SIGKILL, not SIGINT");
    }

    @Test
    void platformSignalRejectsNegativePids() {
        // the reason PROCESS_GROUP uses pty4j's killpg: UnixProcessManager never passes a negative PID to kill(2)
        assertThrows(IllegalArgumentException.class, () -> UnixProcessManager.sendSignal(-12345, UnixProcessManager.SIGINT));
        assertFalse(ProcessSignaller.PROCESS_GROUP.send(Integer.MAX_VALUE - 1, ProcessSignaller.SIGINT), "no such group");
        assertFalse(ProcessSignaller.PROCESS_GROUP.send(0, ProcessSignaller.SIGINT), "never kill(0) or kill(-1)");
        assertFalse(ProcessSignaller.PROCESS_GROUP.send(-1, ProcessSignaller.SIGINT));
    }

    @NotNull
    private PtyProcess spawn(@NotNull String id) throws IOException {
        final PtyProcess process = new HoleTerminalRunner().spawn(
            HoleCommandBuilder.start(this.hole.executable().toString(), SandboxConfig.defaults(), id),
            this.project,
            this.hole.environment()
        );
        this.processes.add(process);
        TerminalDrain.start(new HoleTtyConnector(process, new HoleTtyConnector.OutputTap() {
            @Override
            public void output(char[] buffer, int offset, int length) {
            }

            @Override
            public void closed() {
            }
        }));
        return process;
    }

    private long childPid() throws IOException {
        final Path file = this.hole.stateDirectory().resolve("child.pid");
        waitUntil(() -> {
            try {
                return Files.exists(file) && !Files.readString(file).isBlank();
            } catch (IOException e) {
                return false;
            }
        }, "child started");
        return Long.parseLong(Files.readString(file).trim());
    }

    @NotNull
    private Path childInterrupted() {
        return this.hole.stateDirectory().resolve("child.int");
    }

    /**
     * Returns the process group ID of a process, read with `ps` (works on Linux and macOS).
     */
    private static long processGroup(long pid) throws IOException, InterruptedException {
        return Long.parseLong(ps(pid, "pgid=").trim());
    }

    /**
     * A process is gone when `ps` no longer lists it, or lists it as a zombie nobody reaped yet.
     */
    private static boolean isGone(long pid) {
        try {
            final String state = ps(pid, "stat=").trim();
            return state.isEmpty() || state.startsWith("Z");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @NotNull
    private static String ps(long pid, @NotNull String format) throws IOException, InterruptedException {
        final Process ps = new ProcessBuilder("ps", "-o", format, "-p", Long.toString(pid)).redirectErrorStream(true).start();
        final String output = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        ps.waitFor(10, TimeUnit.SECONDS);
        return output;
    }
}
