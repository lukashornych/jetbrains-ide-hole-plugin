package io.github.lukashornych.hole.idea.session;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import io.github.lukashornych.hole.idea.process.ExecutableLocator;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spawns `hole start` with a real pseudo terminal through pty4j. Hole checks that stdin is a terminal: without one it
 * skips its prompts and a debug shell exits at once, so `hole start` is never run through a plain pipe.
 *
 * <p>The process runs directly, without an intermediate shell, so its exit is the signal that the sandbox is gone.
 * It gets exactly the given environment (the user's shell environment) plus {@code TERM=xterm-256color}, which is what
 * the terminal widget emulates. Neither the command line nor the environment is logged here.</p>
 */
public final class HoleTerminalRunner {

    /** Size of the pty before the terminal widget reports its real size. */
    public static final int INITIAL_COLUMNS = 120;
    /** Size of the pty before the terminal widget reports its real size. */
    public static final int INITIAL_ROWS = 32;
    /** How long {@link #spawn} waits for the new process to start running the executable. */
    static final Duration EXEC_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Starts the process and returns once it runs the executable, so a signal sent right away reaches it.
     *
     * @param command          executable (absolute) followed by its arguments
     * @param workingDirectory the IDE project root, against which Hole resolves a relative project path
     * @param environment      the environment of the user's shell
     * @throws IOException when the executable is not an executable file or the process cannot be started. pty4j
     *                     reports a failed exec only as an exit code, so the executable is checked first.
     */
    @NotNull
    public PtyProcess spawn(
        @NotNull List<String> command,
        @NotNull Path workingDirectory,
        @NotNull Map<String, String> environment
    ) throws IOException {
        if (command.isEmpty() || !ExecutableLocator.isExecutableFile(Path.of(command.get(0)))) {
            throw new IOException("'" + (command.isEmpty() ? "" : command.get(0)) + "' is not an executable file");
        }
        final PtyProcess process = new PtyProcessBuilder(command.toArray(String[]::new))
            .setEnvironment(terminalEnvironment(environment))
            .setDirectory(workingDirectory.toString())
            .setInitialColumns(INITIAL_COLUMNS)
            .setInitialRows(INITIAL_ROWS)
            .setConsole(false)
            .setRedirectErrorStream(true)
            .start();
        awaitExec(process);
        return process;
    }

    /**
     * Waits until the process no longer runs the JVM it was forked from. pty4j returns right after the fork, and a
     * signal that arrives before the exec is taken by the forked JVM and lost, so a tab closed right after its start
     * would leave Hole running. Gives up after {@link #EXEC_TIMEOUT}, when the process has ended, or when the
     * platform does not report process commands.
     */
    private static void awaitExec(@NotNull PtyProcess process) {
        final Optional<String> jvm = ProcessHandle.current().info().command();
        if (jvm.isEmpty()) {
            return;
        }
        final long deadline = System.nanoTime() + EXEC_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            final Optional<String> command = ProcessHandle.of(process.pid()).flatMap(handle -> handle.info().command());
            if (command.isEmpty() || !command.equals(jvm)) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Returns the environment for the pty process: {@code base} with {@code TERM} set to {@code xterm-256color}.
     */
    @NotNull
    static Map<String, String> terminalEnvironment(@NotNull Map<String, String> base) {
        final Map<String, String> environment = new HashMap<>(base);
        environment.put("TERM", "xterm-256color");
        return environment;
    }
}
