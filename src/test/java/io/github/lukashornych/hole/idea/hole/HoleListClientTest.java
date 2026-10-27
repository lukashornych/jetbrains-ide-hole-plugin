package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.InstanceStatus;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real client against the fake `hole` script.
 */
class HoleListClientTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private HoleListClient client;

    @BeforeEach
    void setUp() {
        this.hole = FakeHole.install(this.temp);
        this.client = new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment));
    }

    @Test
    void noSandboxesGiveEmptyList() throws CliException {
        assertTrue(this.client.listAll().isEmpty());
        assertEquals(List.of("list --json --no-gc"), this.hole.calls());
    }

    @Test
    void listsAllAndFiltersById() throws CliException {
        this.hole.putInstance("aaaaaa", "starting");
        this.hole.putInstance("bbbbbb", "running");

        final List<InstanceInfo> all = this.client.listAll();
        assertEquals(List.of("aaaaaa", "bbbbbb"), all.stream().map(InstanceInfo::instanceId).sorted().toList());

        final List<InstanceInfo> one = this.client.list("bbbbbb");
        assertEquals(1, one.size());
        assertEquals(InstanceStatus.RUNNING, one.get(0).status());

        assertTrue(this.client.list("zzzzzz").isEmpty(), "unknown ID is not an error");
        assertEquals(List.of("list --json --no-gc", "list --json --no-gc bbbbbb", "list --json --no-gc zzzzzz"), this.hole.calls());
    }

    @Test
    void holeErrorBecomesExceptionWithCommandAndExitCode() {
        this.hole.mode("fail");
        final CliException e = assertThrows(CliException.class, () -> this.client.listAll());
        assertEquals(1, e.getExitCode());
        assertEquals(List.of(this.hole.executable().toString(), "list", "--json", "--no-gc"), e.getCommand());
        assertTrue(e.getMessage().contains("neither docker nor podman"), e.getMessage());
        assertTrue(e.getMessage().contains("exit code: 1"), e.getMessage());
    }

    @Test
    void olderHoleIsRejected() {
        this.hole.mode("old");
        final CliException e = assertThrows(CliException.class, () -> this.client.listAll());
        assertEquals(1, e.getExitCode());
    }

    @Test
    void environmentReachesProcess() {
        // without the state directory variable the fake refuses to run: proves the injected environment is used
        this.hole.environment().remove("FAKE_HOLE_STATE_DIR");
        assertThrows(CliException.class, () -> this.client.listAll());
    }
}
