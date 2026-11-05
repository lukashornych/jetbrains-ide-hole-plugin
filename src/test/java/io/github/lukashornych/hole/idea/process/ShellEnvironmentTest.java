package io.github.lukashornych.hole.idea.process;

import io.github.lukashornych.hole.idea.testutil.FakeShell;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the environment through the fake login shell.
 */
class ShellEnvironmentTest {

    private static final Map<String, String> FALLBACK = Map.of("FROM", "fallback");

    @TempDir
    Path temp;

    private FakeShell shell;

    @BeforeEach
    void setUp() {
        this.shell = FakeShell.install(this.temp.resolve("shell"));
    }

    @Test
    void readsExportsOfTheRcFileAndDropsTheReadingShellsOwnVariables() throws Exception {
        this.shell.export("PROJECT_PATH", "/home/user/project");
        this.shell.environment().put("SHLVL", "2");
        this.shell.environment().put("PWD", "/somewhere");

        final Map<String, String> environment = reader(Duration.ofSeconds(10)).read();

        assertEquals("/home/user/project", environment.get("PROJECT_PATH"));
        assertEquals(this.shell.executable().toString(), environment.get("SHELL"), "the base environment is inherited");
        Stream.of("SHLVL", "PWD", "_", "OLDPWD", ShellEnvironment.READER_MARKER)
            .forEach(name -> assertFalse(environment.containsKey(name), name));
    }

    @Test
    void passesLoginInteractiveFlagsAndTheReaderMarker() throws Exception {
        reader(Duration.ofSeconds(10)).read();
        final List<String> calls = this.shell.calls();
        assertEquals(1, calls.size(), calls.toString());
        assertTrue(calls.get(0).matches("\\d+ reader=true -l -i -c /usr/bin/env -0 > '.+'"), calls.get(0));
    }

    @Test
    void rcOutputOnStdoutAndStderrDoesNotBreakParsing() throws Exception {
        this.shell.environment().put("FAKE_SHELL_NOISE", "1");
        this.shell.export("PROJECT_PATH", "/home/user/project");
        final Map<String, String> environment = reader(Duration.ofSeconds(10)).read();
        assertEquals("/home/user/project", environment.get("PROJECT_PATH"));
        assertFalse(environment.containsKey("NOT"), "stdout output is not parsed");
        assertFalse(environment.containsKey("FOO"));
    }

    @Test
    void noclobberInTheRcFileDoesNotBreakTheRead() throws Exception {
        // regression: `>` onto a pre-created temporary file fails with noclobber, which blocked every start
        this.shell.environment().put("FAKE_SHELL_NOCLOBBER", "1");
        this.shell.export("PROJECT_PATH", "/home/user/project");
        assertEquals("/home/user/project", reader(Duration.ofSeconds(10)).read().get("PROJECT_PATH"));
        assertNoTemporaryFiles();
    }

    @Test
    void latestJoinsAStartsReadAfterAnEarlierFailure() throws Exception {
        this.shell.environment().put("FAKE_SHELL_EXIT", "1");
        final ShellEnvironment reader = reader(Duration.ofSeconds(10));
        assertEquals(FALLBACK, reader.latest());

        this.shell.environment().remove("FAKE_SHELL_EXIT");
        this.shell.environment().put("FAKE_SHELL_DELAY", "0.5");
        this.shell.export("PROJECT_PATH", "/home/user/project");
        final CompletableFuture<Map<String, String>> start = CompletableFuture.supplyAsync(() -> readUnchecked(reader));
        waitUntil(() -> this.shell.calls().size() == 2, "the start's read runs");
        assertEquals("/home/user/project", reader.latest().get("PROJECT_PATH"), "latest() waits for the running read");
        assertSame(start.get(10, TimeUnit.SECONDS), reader.latest());
        assertEquals(2, this.shell.calls().size());
    }

    @Test
    void valuesWithEqualsSignsAndNewlinesSurvive() throws Exception {
        this.shell.export("MULTI", "a=b\nc=d");
        assertEquals("a=b\nc=d", reader(Duration.ofSeconds(10)).read().get("MULTI"));
    }

    @Test
    void nonZeroExitFailsWithTheExitCodeButWithoutRcOutput() throws Exception {
        this.shell.environment().put("FAKE_SHELL_EXIT", "3");
        this.shell.environment().put("FAKE_SHELL_NOISE", "1");
        this.shell.export("SECRET", "s3cr3t");
        final ShellEnvironmentException e = assertThrows(ShellEnvironmentException.class, () -> reader(Duration.ofSeconds(10)).read());
        assertEquals("exited with code 3", e.getReason());
        assertEquals(this.shell.executable().toString(), e.getShell());
        assertFalse(e.isCancelled());
        assertFalse(e.getMessage().contains("rc warning"));
        assertFalse(e.getMessage().contains("s3cr3t"));
        assertNoTemporaryFiles();
    }

    @Test
    void missingShellFails() {
        this.shell.environment().put("SHELL", this.temp.resolve("no-such-shell").toString());
        final ShellEnvironmentException e = assertThrows(ShellEnvironmentException.class, () -> reader(Duration.ofSeconds(10)).read());
        assertEquals("the shell was not found", e.getReason());
    }

    @Test
    void hangingShellTimesOutAndIsTerminated() throws Exception {
        this.shell.environment().put("FAKE_SHELL_HANG", "1");
        final ShellEnvironmentException e = assertThrows(ShellEnvironmentException.class, () -> reader(Duration.ofMillis(500)).read());
        assertTrue(e.getReason().startsWith("timed out"), e.getReason());
        final long pid = Long.parseLong(this.shell.calls().get(0).split(" ")[0]);
        waitUntil(() -> ProcessHandle.of(pid).map(handle -> !handle.isAlive()).orElse(true), "the shell was terminated");
        assertNoTemporaryFiles();
    }

    @Test
    void concurrentReadsShareOneShellRunAndSequentialReadsRunItAgain() throws Exception {
        this.shell.environment().put("FAKE_SHELL_DELAY", "0.5");
        final ShellEnvironment reader = reader(Duration.ofSeconds(10));
        final CompletableFuture<Map<String, String>> first = CompletableFuture.supplyAsync(() -> readUnchecked(reader));
        final CompletableFuture<Map<String, String>> second = CompletableFuture.supplyAsync(() -> readUnchecked(reader));
        assertSame(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        assertEquals(1, this.shell.calls().size(), this.shell.calls().toString());

        this.shell.environment().remove("FAKE_SHELL_DELAY");
        this.shell.export("PROJECT_PATH", "/home/user/other");
        assertEquals("/home/user/other", reader.read().get("PROJECT_PATH"), "a new read sees the changed rc file");
        assertEquals(2, this.shell.calls().size());
    }

    @Test
    void latestKeepsTheLastSuccessAfterALaterFailure() throws Exception {
        this.shell.export("PROJECT_PATH", "/home/user/project");
        final ShellEnvironment reader = reader(Duration.ofSeconds(10));
        reader.read();
        this.shell.environment().put("FAKE_SHELL_EXIT", "1");
        assertThrows(ShellEnvironmentException.class, reader::read);
        assertEquals("/home/user/project", reader.latest().get("PROJECT_PATH"));
        assertEquals(2, this.shell.calls().size(), "latest() does not run the shell after a success");
    }

    @Test
    void latestReadsOnceAndFallsBackWithoutAnySuccess() {
        this.shell.environment().put("FAKE_SHELL_EXIT", "1");
        final ShellEnvironment reader = reader(Duration.ofSeconds(10));
        assertEquals(FALLBACK, reader.latest());
        assertEquals(FALLBACK, reader.latest());
        assertEquals(1, this.shell.calls().size(), "a failed read is not repeated by every CLI call");
    }

    @Test
    void latestReadsWhenNothingWasReadYet() {
        this.shell.export("PROJECT_PATH", "/home/user/project");
        assertEquals("/home/user/project", reader(Duration.ofSeconds(10)).latest().get("PROJECT_PATH"));
    }

    @Test
    void unsupportedPlatformReturnsTheFallbackWithoutAShell() throws Exception {
        final ShellEnvironment reader = new ShellEnvironment(this::base, () -> FALLBACK, Duration.ofSeconds(10), false);
        assertEquals(FALLBACK, reader.read());
        assertEquals(FALLBACK, reader.latest());
        assertTrue(this.shell.calls().isEmpty());
    }

    @Test
    void parseSkipsMalformedEntries() {
        final Map<String, String> parsed = ShellEnvironment.parse("A=1\0=x\0junk\0B=\0_=/usr/bin/env\0".getBytes());
        assertEquals(Map.of("A", "1", "B", ""), parsed);
        assertNull(parsed.get("_"));
    }

    @NotNull
    private ShellEnvironment reader(@NotNull Duration timeout) {
        return new ShellEnvironment(this::base, () -> FALLBACK, timeout, true);
    }

    @NotNull
    private Map<String, String> base() {
        final Map<String, String> base = new HashMap<>();
        base.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        base.put("HOME", this.temp.toString());
        base.put("SHELL", this.shell.executable().toString());
        base.putAll(this.shell.environment());
        return base;
    }

    /**
     * The temporary directory of the file named in the logged command of every read is gone.
     */
    private void assertNoTemporaryFiles() {
        for (String call : this.shell.calls()) {
            final Path file = Path.of(call.substring(call.indexOf("> '") + 3, call.length() - 1));
            assertFalse(Files.exists(file.getParent()), file.getParent().toString());
        }
    }

    @NotNull
    private static Map<String, String> readUnchecked(@NotNull ShellEnvironment reader) {
        try {
            return reader.read();
        } catch (ShellEnvironmentException e) {
            throw new IllegalStateException(e);
        }
    }
}
