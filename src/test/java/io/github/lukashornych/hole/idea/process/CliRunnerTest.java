package io.github.lukashornych.hole.idea.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliRunnerTest {

    private static final Map<String, String> ENV = Map.of("PATH", "/usr/bin:/bin", "HOLE_TEST_VALUE", "from shell env");

    @TempDir
    Path temp;

    @Test
    void processGetsExactlyTheInjectedEnvironment() throws CliException {
        final CliRunner runner = new CliRunner(() -> ENV);
        final CliResult result = runner.run(List.of("sh", "-c", "printf '%s|%s' \"$HOLE_TEST_VALUE\" \"${JAVA_HOME:-none}\""), null, Duration.ofSeconds(10));
        assertEquals(0, result.exitCode());
        assertEquals("from shell env|none", result.stdout(), "the IDE's own environment does not leak in");
    }

    @Test
    void executableIsResolvedOnInjectedPath() throws Exception {
        final Path bin = Files.createDirectories(this.temp.resolve("bin"));
        final Path tool = bin.resolve("my-tool");
        Files.writeString(tool, "#!/bin/sh\necho tool-ran \"$@\"\n");
        assertTrue(tool.toFile().setExecutable(true));

        final CliRunner runner = new CliRunner(() -> Map.of("PATH", bin + ":/usr/bin:/bin"));
        final CliResult result = runner.run(List.of("my-tool", "a b"), null, Duration.ofSeconds(10));
        assertEquals("tool-ran a b\n", result.stdout());
        assertEquals(tool.toAbsolutePath().toString(), result.command().get(0));
    }

    @Test
    void argumentsAreNotInterpretedByShell() throws CliException {
        final CliRunner runner = new CliRunner(() -> ENV);
        final CliResult result = runner.run(List.of("printf", "%s\\n", "$HOME", "a;b", "`id`"), null, Duration.ofSeconds(10));
        assertEquals("$HOME\na;b\n`id`\n", result.stdout());
    }

    @Test
    void capturesStderrExitCodeAndWorkingDirectory() throws Exception {
        final CliRunner runner = new CliRunner(() -> ENV);
        final CliResult result = runner.run(List.of("sh", "-c", "pwd; echo '  ' >&2; echo oops >&2; exit 3"), this.temp, Duration.ofSeconds(10));
        assertEquals(3, result.exitCode());
        assertEquals(this.temp.toRealPath() + "\n", result.stdout());
        assertEquals("oops", result.firstStderrLine());
    }

    @Test
    void missingExecutable() {
        final CliRunner runner = new CliRunner(() -> ENV);
        final CliException e = assertThrows(CliException.class, () -> runner.run(List.of("no-such-tool-xyz"), null, Duration.ofSeconds(10)));
        assertNull(e.getExitCode());
        assertTrue(e.getMessage().contains("no-such-tool-xyz"), e.getMessage());
    }

    @Test
    void timeoutKillsProcess() {
        final CliRunner runner = new CliRunner(() -> ENV);
        final long start = System.nanoTime();
        final CliException e = assertThrows(CliException.class, () -> runner.run(List.of("sleep", "30"), null, Duration.ofMillis(300)));
        assertTrue(e.getMessage().contains("did not finish"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    }

    @Test
    void backgroundChildHoldingOutputOpenDoesNotBlockForever() {
        // Two outcomes are correct. Usually the reader is blocked on the pipe the background child keeps open, and the
        // run fails with "not closed". But when the JDK's process reaper sees the exit before the reader thread starts,
        // it drains the pipe into a buffer and closes it, so the reader gets EOF and the run succeeds. What matters is
        // that the call never waits for the child (3 s).
        final CliRunner runner = new CliRunner(() -> ENV);
        final long start = System.nanoTime();
        try {
            final CliResult result = runner.run(List.of("sh", "-c", "sleep 3 & echo started"), null, Duration.ofMillis(700));
            assertEquals(0, result.exitCode());
            assertTrue(result.stdout().contains("started"), result.stdout());
        } catch (CliException e) {
            assertTrue(e.getMessage().contains("not closed"), e.getMessage());
            assertEquals(0, e.getExitCode());
        }
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500);
    }

    @Test
    void interruptTerminatesWithSigtermFirst() throws Exception {
        // regression: an interrupted run() must give the process SIGTERM (Hole finishes a GC teardown), not SIGKILL
        final Path ready = this.temp.resolve("ready");
        final Path signal = this.temp.resolve("signal");
        final String script = "trap 'echo TERM > \"" + signal + "\"; exit 143' TERM; touch \"" + ready + "\"; "
            + "while :; do sleep 0.05; done";
        final CliRunner runner = new CliRunner(() -> ENV);
        final java.util.concurrent.atomic.AtomicReference<Throwable> thrown = new java.util.concurrent.atomic.AtomicReference<>();
        final Thread caller = Thread.ofPlatform().start(() -> {
            try {
                runner.run(List.of("sh", "-c", script), null, Duration.ofSeconds(30));
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(ready) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(Files.exists(ready), "script started");

        caller.interrupt();
        caller.join(10_000);
        assertTrue(thrown.get() instanceof CliException, "interrupt is reported: " + thrown.get());
        assertTrue(thrown.get().getMessage().contains("Interrupted"), thrown.get().getMessage());
        assertTrue(Files.exists(signal), "the process received SIGTERM and ran its trap");
        assertEquals("TERM", Files.readString(signal).trim());
    }

    @Test
    void locatorSkipsDirectoriesAndNonExecutables() throws Exception {
        final Path a = Files.createDirectories(this.temp.resolve("a"));
        final Path b = Files.createDirectories(this.temp.resolve("b"));
        Files.createDirectories(a.resolve("tool"));
        final Path plain = Files.writeString(b.resolve("tool"), "x");
        final Path c = Files.createDirectories(this.temp.resolve("c"));
        final Path real = Files.writeString(c.resolve("tool"), "#!/bin/sh\n");
        assertTrue(real.toFile().setExecutable(true));

        final Map<String, String> env = Map.of("PATH", a + "::" + b + ":" + c);
        assertEquals(real.toAbsolutePath(), ExecutableLocator.findOnPath("tool", env).orElseThrow());
        assertTrue(ExecutableLocator.findOnPath("tool", Map.of()).isEmpty());
        assertTrue(ExecutableLocator.resolve(plain.toString(), env).isEmpty());
    }
}
