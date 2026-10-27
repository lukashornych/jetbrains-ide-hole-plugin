package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleListClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.process.ExecutableLocator;
import io.github.lukashornych.hole.idea.testutil.MutableClock;
import io.github.lukashornych.hole.idea.testutil.TerminalDrain;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.lukashornych.hole.idea.testutil.Waiting.waitUntil;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in checks against a real Hole binary (2.6.0 or newer): run with the environment variable
 * {@code HOLE_REAL_BINARY} pointing to the executable, e.g.
 * {@code HOLE_REAL_BINARY=$(command -v hole) ./gradlew test --tests '*RealHoleBinaryTest'}. Skipped otherwise.
 *
 * <p>The tests need no running container engine, but `hole list` and `hole start` detect it, so the `docker` (or
 * `podman`) CLI with its compose plugin must be on {@code PATH}. Hole runs with a temporary {@code HOME}, so the
 * user's settings and instances are not touched, and the started sandbox fails before it needs the daemon (unknown
 * agent). {@code DOCKER_CONFIG} keeps pointing to the user's Docker configuration, so the compose plugin is found.</p>
 */
@EnabledIfEnvironmentVariable(named = RealHoleBinaryTest.VARIABLE, matches = ".+")
class RealHoleBinaryTest {

    static final String VARIABLE = "HOLE_REAL_BINARY";

    @TempDir
    Path temp;

    private Path binary;
    private Path home;
    private Path project;
    private Map<String, String> environment;
    private CliRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        this.binary = Path.of(System.getenv(VARIABLE)).toAbsolutePath();
        assumeTrue(ExecutableLocator.isExecutableFile(this.binary), VARIABLE + " is not an executable file: " + this.binary);
        this.home = Files.createDirectories(this.temp.resolve("home")).toRealPath();
        this.project = Files.createDirectories(this.temp.resolve("project")).toRealPath();
        this.environment = new HashMap<>();
        this.environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
        this.environment.put("HOME", this.home.toString());
        // the temporary HOME hides ~/.docker, where Docker Desktop registers the compose plugin
        this.environment.put(
            "DOCKER_CONFIG",
            System.getenv().getOrDefault("DOCKER_CONFIG", Path.of(System.getProperty("user.home"), ".docker").toString())
        );
        this.runner = new CliRunner(() -> this.environment);
    }

    @Test
    void versionIsSupported() {
        final HoleCapability capability = new HoleExecutable(this.binary::toString, this.runner).probe();
        assertEquals(HoleCapability.Status.SUPPORTED, capability.status(), capability.message());
    }

    @Test
    void listJsonWithoutGcParses() throws Exception {
        final List<InstanceInfo> instances = new HoleListClient(this.binary.toString(), this.runner).listAll();
        assertTrue(instances.isEmpty(), "no sandboxes with a fresh HOME: " + instances);
    }

    @Test
    void failingStartEndsExitedWithNonZeroCode() throws Exception {
        final MetadataPoller poller = new MetadataPoller(
            new HoleListClient(this.binary.toString(), this.runner)::listAll,
            Executors.newSingleThreadScheduledExecutor(), true, Duration.ofMillis(500), Duration.ofSeconds(2)
        );
        final ExecutorService background = Executors.newCachedThreadPool();
        try {
            final AtomicInteger restarts = new AtomicInteger();
            final SandboxSession session = new SandboxSession(
                new SandboxSession.Launch(
                    SandboxConfig.defaults().withAgent("no-such-agent"), false, this.binary, this.project, this.project,
                    this.environment
                ),
                "rlt3st",
                new SessionServices(
                    new HoleTerminalRunner(), ProcessSignaller.PROCESS_GROUP, MetadataSource.of(poller),
                    () -> "rlt3su", new MutableClock(Instant.now()), background
                ),
                new SandboxSession.Listener() {
                    @Override
                    public void sessionChanged(@NotNull SandboxSession changed) {
                    }

                    @Override
                    public void processRestarted(@NotNull SandboxSession changed) {
                        restarts.incrementAndGet();
                    }
                }
            );
            session.start();
            final TerminalDrain drain = TerminalDrain.start(session.createConnector());

            waitUntil(() -> session.snapshot().state() == SessionState.EXITED, () -> "exited: " + drain.output());
            assertNotNull(session.snapshot().exitCode());
            assertNotEquals(0, session.snapshot().exitCode());
            assertEquals(0, restarts.get(), "not an instance ID conflict");
            waitUntil(() -> drain.output().contains("no-such-agent"), () -> "Hole's error is in the terminal: " + drain.output());
        } finally {
            poller.dispose();
            background.shutdownNow();
        }
    }

    @Test
    void settingsGetForExistingAndMissingFiles() throws IOException {
        final HoleSettingsClient client = new HoleSettingsClient(this.binary.toString(), this.runner);
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(this.project.resolve(".hole/settings.json"), false)),
            client.project(this.project), "missing project file, path taken from Hole's error");
        assertEquals(
            Optional.of(new HoleSettingsClient.SettingsFile(this.home.resolve(".hole/settings.json"), false)),
            client.global(),
            "missing global file, path taken from Hole's error"
        );

        final Path projectFile = Files.writeString(Files.createDirectories(this.project.resolve(".hole")).resolve("settings.json"), "{}");
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(projectFile, true)), client.project(this.project));
        final Path globalFile = Files.writeString(Files.createDirectories(this.home.resolve(".hole")).resolve("settings.json"), "{}");
        assertEquals(Optional.of(new HoleSettingsClient.SettingsFile(globalFile, true)), client.global());
    }

    @Test
    void modifyOfASandboxThatIsNotRunningIsReportedAsNotRunning() {
        // starting a real sandbox needs images and an agent; the JSON contract is covered by HoleModifyParserTest
        final io.github.lukashornych.hole.idea.hole.HoleModifyClient client = new io.github.lukashornych.hole.idea.hole.HoleModifyClient(
            this.binary.toString(), this.runner, this.project, "zz9zz9");
        assertEquals(io.github.lukashornych.hole.idea.hole.HoleModifyClient.ModifyFailure.NOT_RUNNING,
            client.list(io.github.lukashornych.hole.idea.model.NetworkSetting.ALLOW).failure());
        assertEquals(io.github.lukashornych.hole.idea.hole.HoleModifyClient.ModifyFailure.NOT_RUNNING,
            client.listBlocked().failure());
    }
}
