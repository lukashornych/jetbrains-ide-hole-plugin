package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HoleCommandBuilderTest {

    private static final String HOLE = "/usr/local/bin/hole";
    private static final String ID = "r8tq2c";

    @Test
    void defaultsStartAgentInCurrentDirectoryWithInstanceId() {
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "--instance-id", ID),
            HoleCommandBuilder.start(HOLE, SandboxConfig.defaults(), ID)
        );
    }

    @Test
    void debugAddsDebugFlagAfterProjectPath() {
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "-d", "--instance-id", ID),
            HoleCommandBuilder.debug(HOLE, SandboxConfig.defaults(), ID)
        );
    }

    @Test
    void profileIsJoinedToAgentWithColon() {
        final SandboxConfig config = SandboxConfig.defaults().withProfile("research");
        assertEquals(
            List.of(HOLE, "start", "claude:research", ".", "--instance-id", ID),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void blankProfileIsLeftOut() {
        final SandboxConfig config = SandboxConfig.defaults().withProfile("  ");
        assertEquals("claude", HoleCommandBuilder.start(HOLE, config, ID).get(2));
    }

    @Test
    void allFlagsFollowMockupOrder() {
        final SandboxConfig config = SandboxConfig.defaults()
            .withWithDocker(true)
            .withUnrestrictedNetwork(true)
            .withTrustProject(true)
            .withDumpNetworkAccess(true)
            .withRebuild(true)
            .withUpdateAgents(true);
        assertEquals(
            List.of(
                HOLE, "start", "claude", ".",
                "-n", "-r", "--update-agents", "-u", "--with-docker", "--trust-project",
                "--instance-id", ID
            ),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void debugPutsDebugFlagBeforeOtherFlags() {
        final SandboxConfig config = SandboxConfig.defaults().withWithDocker(true).withRebuild(true);
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "-d", "-r", "--with-docker", "--instance-id", ID),
            HoleCommandBuilder.debug(HOLE, config, ID)
        );
    }

    @Test
    void librariesAreRepeatedInOrderWithReadWriteSuffix() {
        final SandboxConfig config = SandboxConfig.defaults().withLibraries(List.of(
            new Library("~/.m2/repository", false),
            new Library("/opt/shared libs", true)
        ));
        assertEquals(
            List.of(
                HOLE, "start", "claude", ".",
                "--library", "~/.m2/repository",
                "--library", "/opt/shared libs:rw",
                "--instance-id", ID
            ),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void blankLibraryRowsAreSkippedAndPathsTrimmed() {
        final SandboxConfig config = SandboxConfig.defaults().withLibraries(List.of(
            new Library("  ", true),
            new Library(" /lib ", false)
        ));
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "--library", "/lib", "--instance-id", ID),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void agentArgsFollowSeparatorAndAreSplitLikeShellWords() {
        final SandboxConfig config = SandboxConfig.defaults().withAgentArgs("--model opus -p \"explain this function\" 'a b'");
        assertEquals(
            List.of(
                HOLE, "start", "claude", ".", "--instance-id", ID,
                "--", "--model", "opus", "-p", "explain this function", "a b"
            ),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void emptyQuotedAgentArgIsKept() {
        final SandboxConfig config = SandboxConfig.defaults().withAgentArgs("-p \"\" --x");
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "--instance-id", ID, "--", "-p", "", "--x"),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void blankAgentArgsAddNoSeparator() {
        final SandboxConfig config = SandboxConfig.defaults().withAgentArgs("   ");
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "--instance-id", ID),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
    }

    @Test
    void debugLeavesOutAgentArgs() {
        final SandboxConfig config = SandboxConfig.defaults().withAgentArgs("--model opus");
        assertEquals(
            List.of(HOLE, "start", "claude", ".", "-d", "--instance-id", ID),
            HoleCommandBuilder.debug(HOLE, config, ID)
        );
    }

    @Test
    void projectPathIsPassedVerbatimIncludingSpaces() {
        final SandboxConfig config = SandboxConfig.defaults().withProjectPath("/Users/me/my project");
        assertEquals("/Users/me/my project", HoleCommandBuilder.start(HOLE, config, ID).get(3));
        assertEquals("../other", HoleCommandBuilder.start(HOLE, config.withProjectPath("../other"), ID).get(3));
    }

    @Test
    void blankProjectPathFallsBackToCurrentDirectory() {
        final SandboxConfig config = SandboxConfig.defaults().withProjectPath(" ");
        assertEquals(".", HoleCommandBuilder.start(HOLE, config, ID).get(3));
    }

    @Test
    void executableWithSpacesIsOneArgument() {
        final List<String> command = HoleCommandBuilder.start("/Users/me/my tools/hole", SandboxConfig.defaults(), ID);
        assertEquals("/Users/me/my tools/hole", command.get(0));
        assertEquals("start", command.get(1));
    }

    @Test
    void fullConfiguration() {
        final SandboxConfig config = new SandboxConfig(
            "codex", "review", "/work/acme-shop",
            true, true, true, true, true, true,
            List.of(new Library("~/lib", true)),
            "--full-auto"
        );
        assertEquals(
            List.of(
                HOLE, "start", "codex:review", "/work/acme-shop",
                "-n", "-r", "--update-agents", "-u", "--with-docker", "--trust-project",
                "--library", "~/lib:rw",
                "--instance-id", ID,
                "--", "--full-auto"
            ),
            HoleCommandBuilder.start(HOLE, config, ID)
        );
        assertEquals(
            List.of(
                HOLE, "start", "codex:review", "/work/acme-shop", "-d",
                "-n", "-r", "--update-agents", "-u", "--with-docker", "--trust-project",
                "--library", "~/lib:rw",
                "--instance-id", ID
            ),
            HoleCommandBuilder.debug(HOLE, config, ID)
        );
    }

    @Test
    void rejectsMalformedInstanceId() {
        assertThrows(IllegalArgumentException.class, () -> HoleCommandBuilder.start(HOLE, SandboxConfig.defaults(), "ABC123"));
        assertThrows(IllegalArgumentException.class, () -> HoleCommandBuilder.start(HOLE, SandboxConfig.defaults(), "abc12"));
        assertThrows(IllegalArgumentException.class, () -> HoleCommandBuilder.debug(HOLE, SandboxConfig.defaults(), "abc-12"));
    }

    @Test
    void rejectsBlankAgent() {
        assertThrows(IllegalArgumentException.class, () -> HoleCommandBuilder.start(HOLE, SandboxConfig.defaults().withAgent(" "), ID));
    }

    @Test
    void listJsonWithAndWithoutFilterSkipsGc() {
        assertEquals(List.of(HOLE, "list", "--json", "--no-gc"), HoleCommandBuilder.listJson(HOLE, null));
        assertEquals(List.of(HOLE, "list", "--json", "--no-gc", ID), HoleCommandBuilder.listJson(HOLE, ID));
    }

    @Test
    void versionAndSettingsGet() {
        assertEquals(List.of(HOLE, "version"), HoleCommandBuilder.version(HOLE));
        assertEquals(List.of(HOLE, "settings", "get", "global"), HoleCommandBuilder.settingsGetGlobal(HOLE));
        assertEquals(List.of(HOLE, "settings", "get", "project", "/work/my project"),
            HoleCommandBuilder.settingsGetProject(HOLE, "/work/my project"));
    }

    @Test
    void returnedListIsImmutable() {
        final List<String> command = HoleCommandBuilder.start(HOLE, SandboxConfig.defaults(), ID);
        assertThrows(UnsupportedOperationException.class, () -> command.add("x"));
    }
}
