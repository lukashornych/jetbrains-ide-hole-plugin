package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.util.text.StringUtil;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;

/**
 * Texts of a sandbox tab, kept free of Swing so they can be tested.
 */
public final class SessionPresentation {

    /** Separator between the agent and the title. */
    public static final String SEPARATOR = " · ";
    /** Longest agent title shown in a tab; longer ones are shortened with an ellipsis. */
    static final int MAX_TITLE_LENGTH = 60;

    private SessionPresentation() {
    }

    /**
     * Returns the tab title {@code agent[:profile] · title}: the title the agent set through OSC 0/2, or the project
     * folder name until it sets one (plain text).
     *
     * @param config        the started configuration
     * @param agentTitle    terminal title set by the agent, blank when none
     * @param projectFolder name of the project folder
     */
    @NotNull
    public static String tabTitle(@NotNull SandboxConfig config, @NotNull String agentTitle, @NotNull String projectFolder) {
        final String title = cleanTitle(agentTitle);
        return config.agentSpec() + SEPARATOR + (title.isEmpty() ? projectFolder : title);
    }

    /**
     * Returns the tooltip of a tab: instance ID and absolute project path, as HTML.
     */
    @NotNull
    public static String tooltip(@NotNull String instanceId, @NotNull String projectPath) {
        return "<html>Instance: " + StringUtil.escapeXmlEntities(instanceId)
            + "<br>Project: " + StringUtil.escapeXmlEntities(projectPath) + "</html>";
    }

    /**
     * Removes control characters, collapses whitespace and shortens a terminal title.
     */
    @NotNull
    static String cleanTitle(@NotNull String title) {
        final String clean = title.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > MAX_TITLE_LENGTH ? clean.substring(0, MAX_TITLE_LENGTH - 1) + "…" : clean;
    }
}
