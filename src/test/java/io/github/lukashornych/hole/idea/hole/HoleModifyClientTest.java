package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `hole modify` through the fake `hole` script, which follows Hole 2.6.0's contract.
 */
class HoleModifyClientTest {

    @TempDir
    Path temp;

    private FakeHole hole;
    private HoleModifyClient client;
    private Path project;

    @BeforeEach
    void setUp() throws IOException {
        this.hole = FakeHole.install(this.temp.resolve("fake"));
        this.project = Files.createDirectories(this.temp.resolve("demo"));
        this.hole.putInstance("k3x9a2", "running");
        this.hole.environment().put("FAKE_HOLE_MODIFY_BASE",
            "network.allow=api.example.com:443@settings;network.allow=registry.example.org:443,80@agent:claude;"
                + "network.hostGatewayDomains=host.example.internal@settings");
        this.client = new HoleModifyClient(this.hole.executable().toString(), new CliRunner(this.hole::environment),
            this.project, "k3x9a2");
    }

    @Test
    void listReportsEntriesWithSourcesPerSetting() {
        final HoleModifyClient.Result<List<NetworkEntry>> allow = this.client.list(NetworkSetting.ALLOW);
        assertTrue(allow.isSuccess(), allow.message());
        assertEquals(List.of(
            new NetworkEntry("api.example.com:443", "settings"),
            new NetworkEntry("registry.example.org:443,80", "agent:claude")
        ), allow.value(), "the fake adds an unknown field, which is ignored");
        assertEquals(List.of(new NetworkEntry("host.example.internal", "settings")),
            this.client.list(NetworkSetting.HOST_GATEWAY_DOMAINS).value());
        assertEquals("modify " + this.project + " network.allow list --instance-id k3x9a2 --json", this.hole.calls().get(0));
    }

    @Test
    void severalValuesAreAddedInOneCallAndOnlyRuntimeEntriesRemoved() throws Exception {
        assertTrue(this.client.add(NetworkSetting.ALLOW, List.of("*.cdn.example.net:443", "db.example.com:5432")).isSuccess());
        assertEquals(1, this.hole.calls().stream().filter(call -> call.contains(" add ")).count(), "one gateway apply");
        assertEquals(List.of(
            new NetworkEntry("api.example.com:443", "settings"),
            new NetworkEntry("registry.example.org:443,80", "agent:claude"),
            new NetworkEntry("*.cdn.example.net:443", "runtime"),
            new NetworkEntry("db.example.com:5432", "runtime")
        ), this.client.list(NetworkSetting.ALLOW).value());
        assertEquals(2, new HoleListClient(this.hole.executable().toString(), new CliRunner(this.hole::environment))
            .list("k3x9a2").get(0).runtimeEntryCount(), "hole list reports the runtime entries");

        final HoleModifyClient.Result<Void> base = this.client.remove(NetworkSetting.ALLOW, List.of("api.example.com:443"));
        assertEquals(HoleModifyClient.ModifyFailure.INVALID, base.failure());
        assertTrue(base.message().contains("comes from the sandbox's settings"), base.message());

        assertTrue(this.client.remove(NetworkSetting.ALLOW, List.of("db.example.com:5432")).isSuccess());
        assertEquals(3, this.client.list(NetworkSetting.ALLOW).value().size());
    }

    @Test
    void blockedNamesDisappearOnceAllowed() {
        this.hole.environment().put("FAKE_HOLE_BLOCKED",
            "telemetry.example.com|4|2026-10-21T10:00:00Z|2026-10-21T10:05:00Z;cdn.a.b.example.org|1|2026-10-21T09:00:00Z|2026-10-21T09:00:00Z");
        final HoleModifyClient.Result<List<BlockedName>> before = this.client.listBlocked();
        assertEquals(List.of("telemetry.example.com", "cdn.a.b.example.org"), before.value().stream().map(BlockedName::name).toList());
        assertEquals(4, before.value().get(0).count());

        assertTrue(this.client.add(NetworkSetting.ALLOW, List.of("*.b.example.org:443,80")).isSuccess());
        assertEquals(List.of("telemetry.example.com"), this.client.listBlocked().value().stream().map(BlockedName::name).toList());
    }

    @Test
    void exitCodesAreMapped() {
        this.hole.environment().put("FAKE_HOLE_MODIFY_EXIT", "4");
        this.hole.environment().put("FAKE_HOLE_MODIFY_MESSAGE", "the gateway did not apply the change (exit 1); the previous policy is still in force");
        final HoleModifyClient.Result<Void> apply = this.client.add(NetworkSetting.ALLOW, List.of("a.example.com"));
        assertEquals(HoleModifyClient.ModifyFailure.APPLY_FAILED, apply.failure());
        assertTrue(apply.message().startsWith("the gateway did not apply"), apply.message());

        this.hole.environment().put("FAKE_HOLE_MODIFY_EXIT", "2");
        this.hole.environment().put("FAKE_HOLE_MODIFY_MESSAGE", "the change would make names resolve to the host");
        assertEquals(HoleModifyClient.ModifyFailure.INVALID, this.client.add(NetworkSetting.HOST_GATEWAY_DOMAINS, List.of("docker.io")).failure());

        this.hole.environment().put("FAKE_HOLE_MODIFY_EXIT", "3");
        this.hole.environment().put("FAKE_HOLE_MODIFY_MESSAGE", "sandbox k3x9a2 was started by an older Hole; restart it to use modify");
        assertEquals(HoleModifyClient.ModifyFailure.OLDER_HOLE, this.client.list(NetworkSetting.ALLOW).failure());

        this.hole.environment().put("FAKE_HOLE_MODIFY_EXIT", "1");
        assertEquals(HoleModifyClient.ModifyFailure.OTHER, this.client.listBlocked().failure());
    }

    @Test
    void notRunningOrStartingSandboxIsReported() {
        this.hole.putInstance("k3x9a2", "starting");
        final HoleModifyClient.Result<List<NetworkEntry>> starting = this.client.list(NetworkSetting.ALLOW);
        assertEquals(HoleModifyClient.ModifyFailure.NOT_RUNNING, starting.failure());
        assertTrue(starting.message().contains("still starting"), starting.message());
        this.hole.removeInstance("k3x9a2");
        assertEquals(HoleModifyClient.ModifyFailure.NOT_RUNNING, this.client.list(NetworkSetting.ALLOW).failure());
    }

    @Test
    void unexpectedExceptionBecomesAFailure() {
        // the Network window counts calls in flight; an exception must not leave its buttons disabled
        final HoleModifyClient.Result<Void> result = HoleModifyClient.Result.guarded(() -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(HoleModifyClient.ModifyFailure.OTHER, result.failure());
        assertEquals("boom", result.message());
        assertTrue(HoleModifyClient.Result.guarded(() -> this.client.list(NetworkSetting.ALLOW)).isSuccess());
    }

    @Test
    void holeBeforeModifyFailsAsOther() {
        this.hole.mode("old");
        assertEquals(HoleModifyClient.ModifyFailure.OTHER, this.client.list(NetworkSetting.ALLOW).failure());
    }
}
