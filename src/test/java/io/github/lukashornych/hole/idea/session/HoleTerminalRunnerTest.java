package io.github.lukashornych.hole.idea.session;

import com.jediterm.core.util.TermSize;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HoleTerminalRunnerTest {

    @TempDir
    Path temp;

    @Test
    void processRunsInRealTerminalWithTermAndOnlyGivenEnvironment() throws Exception {
        final Map<String, String> env = Map.of("PATH", "/usr/bin:/bin", "TERM", "dumb", "HOLE_TEST", "from shell");
        final var process = new HoleTerminalRunner().spawn(
            List.of("/bin/sh", "-c", "[ -t 0 ] && echo \"tty:$TERM:$HOLE_TEST:${JAVA_HOME:-none}:$(pwd)\"; echo err >&2"),
            this.temp, env
        );
        final TerminalDrain drain = TerminalDrain.start(new HoleTtyConnector(process, new HoleTtyConnector.OutputTap() {
            @Override
            public void output(char[] buffer, int offset, int length) {
            }

            @Override
            public void closed() {
            }
        }));
        assertEquals(0, process.waitFor());
        waitUntil(() -> drain.output().contains("err"), () -> "stderr reaches the terminal: " + drain.output());
        final String expected = "tty:xterm-256color:from shell:none:" + this.temp.toRealPath();
        waitUntil(() -> drain.output().contains(expected), () -> drain.output());
    }

    @Test
    void signalRightAfterSpawnReachesTheProcess() throws Exception {
        // regression: pty4j returns right after the fork, and a SIGINT that arrived before the exec hit the forked JVM
        // and was lost, so a tab closed (or a project closed) right after a start left Hole running
        final Path script = this.temp.resolve("trap-int");
        Files.writeString(script, "#!/bin/sh\ntrap 'exit 130' INT\nwhile :; do sleep 0.05; done\n");
        assertTrue(script.toFile().setExecutable(true));
        for (int i = 0; i < 20; i++) {
            final var process = new HoleTerminalRunner().spawn(List.of(script.toString()), this.temp, Map.of("PATH", "/usr/bin:/bin"));
            try {
                assertTrue(ProcessSignaller.PROCESS_GROUP.send(process.pid(), ProcessSignaller.SIGINT), "attempt " + i);
                assertTrue(process.waitFor(5, TimeUnit.SECONDS), "SIGINT was lost in attempt " + i);
                assertEquals(130, process.exitValue());
            } finally {
                process.destroyForcibly();
            }
        }
    }

    @Test
    void connectorForwardsResizeToThePty() throws Exception {
        // regression: the connector no longer extends PtyProcessTtyConnector (its constructor changed in 2026.3), so
        // it forwards the terminal size to the pty itself
        final var process = new HoleTerminalRunner().spawn(
            List.of("/bin/sh", "-c", "sleep 1; stty size"), this.temp, Map.of("PATH", "/usr/bin:/bin")
        );
        final HoleTtyConnector connector = new HoleTtyConnector(process, new HoleTtyConnector.OutputTap() {
            @Override
            public void output(char[] buffer, int offset, int length) {
            }

            @Override
            public void closed() {
            }
        });
        connector.resize(new TermSize(101, 33));
        final TerminalDrain drain = TerminalDrain.start(connector);
        assertEquals(0, process.waitFor());
        waitUntil(() -> drain.output().contains("33 101"), () -> "stty sees the new size: " + drain.output());
    }

    @Test
    void terminalEnvironmentOverridesTerm() {
        assertEquals(
            Map.of("PATH", "/bin", "TERM", "xterm-256color"),
            HoleTerminalRunner.terminalEnvironment(Map.of("PATH", "/bin", "TERM", "dumb"))
        );
    }
}
