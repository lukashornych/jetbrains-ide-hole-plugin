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
}
