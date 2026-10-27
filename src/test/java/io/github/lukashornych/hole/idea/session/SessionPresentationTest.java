package io.github.lukashornych.hole.idea.session;

import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionPresentationTest {

    @Test
    void titleUsesAgentTitleOrProjectFolder() {
        final SandboxConfig config = SandboxConfig.defaults().withProfile("research");
        assertEquals("claude:research · ✳ Fix EntityIndexTest", SessionPresentation.tabTitle(config, "✳ Fix EntityIndexTest", "acme-cms"));
        assertEquals("claude:research · acme-cms", SessionPresentation.tabTitle(config, "", "acme-cms"));
        assertEquals("claude · acme-cms", SessionPresentation.tabTitle(SandboxConfig.defaults(), " \u0007 ", "acme-cms"));
    }

    @Test
    void longAndControlCharacterTitlesAreCleaned() {
        assertEquals("a b", SessionPresentation.cleanTitle("a\u001b\tb\n"));
        final String shortened = SessionPresentation.cleanTitle("x".repeat(200));
        assertEquals(SessionPresentation.MAX_TITLE_LENGTH, shortened.length());
        assertTrue(shortened.endsWith("…"));
    }

    @Test
    void tooltipEscapesHtml() {
        assertEquals("<html>Instance: r8tq2c<br>Project: /work/&lt;x&gt;</html>", SessionPresentation.tooltip("r8tq2c", "/work/<x>"));
    }

    @Test
    void restartQuestionNamesAgentProjectAndDockerChange() {
        final SandboxConfig config = SandboxConfig.defaults().withProfile("research");
        assertEquals("Restart 'claude:research' in 'acme-cms'? The agent session ends and a new sandbox starts in this tab.",
            SessionPresentation.restartQuestion(config, "acme-cms", null, 0));
        assertEquals("Restart 'claude:research' in 'acme-cms' with Docker-in-Docker enabled? The agent session ends and a new "
            + "sandbox starts in this tab.", SessionPresentation.restartQuestion(config, "acme-cms", true, 0));
        assertTrue(SessionPresentation.restartQuestion(config, "acme-cms", false, 0).contains("with Docker-in-Docker disabled?"));
    }

    @Test
    void restartQuestionWarnsAboutRuntimeNetworkEntries() {
        final SandboxConfig config = SandboxConfig.defaults();
        assertTrue(SessionPresentation.restartQuestion(config, "acme-cms", null, 3)
            .endsWith("in this tab. 3 network entries added with 'hole modify' will be lost."));
        assertTrue(SessionPresentation.restartQuestion(config, "acme-cms", true, 1)
            .endsWith(" 1 network entry added with 'hole modify' will be lost."), "also for the Docker toggle");
    }

    @Test
    void projectFolderIsTheLastPathElement() {
        assertEquals("acme-cms", SessionPresentation.projectFolder(java.nio.file.Path.of("/work/acme-cms")));
        assertEquals("/", SessionPresentation.projectFolder(java.nio.file.Path.of("/")));
    }
}
