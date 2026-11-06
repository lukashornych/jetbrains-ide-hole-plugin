package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.util.text.StringUtil;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

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
     * Returns the name of the project folder shown in titles: the last path element, or the whole path for a root.
     */
    @NotNull
    public static String projectFolder(@NotNull Path directory) {
        return directory.getFileName() == null ? directory.toString() : directory.getFileName().toString();
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
     * Returns the question of the restart confirmation, e.g. {@code Restart 'claude' in 'my-project' with
     * Docker-in-Docker enabled? The agent session ends and a new sandbox starts in this tab.} (or {@code with
     * unrestricted network enabled}, see {@link RestartChange}).
     *
     * @param config         the running configuration
     * @param projectFolder  name of the project folder
     * @param change         the option the restart toggles, {@code null} for a plain restart with the same configuration
     * @param runtimeEntries network entries added with 'hole modify' (`runtimeEntryCount` of `hole list`); a restart
     *                       loses them, so the question says so when there are any
     */
    @NotNull
    public static String restartQuestion(
        @NotNull SandboxConfig config, @NotNull String projectFolder, @Nullable RestartChange change, long runtimeEntries
    ) {
        final String changed = change == null ? "" : " " + change.text;
        final String lost = runtimeEntries <= 0 ? ""
            : " " + runtimeEntries + (runtimeEntries == 1 ? " network entry" : " network entries")
                + " added with 'hole modify' will be lost.";
        return "Restart '" + config.agentSpec() + "' in '" + projectFolder + "'" + changed + "? "
            + "The agent session ends and a new sandbox starts in this tab." + lost;
    }

    /**
     * An option a header pill toggles through a restart.
     */
    public enum RestartChange {
        DOCKER_ON("with Docker-in-Docker enabled"),
        DOCKER_OFF("with Docker-in-Docker disabled"),
        UNRESTRICTED_ON("with unrestricted network enabled"),
        UNRESTRICTED_OFF("with unrestricted network disabled");

        private final String text;

        RestartChange(@NotNull String text) {
            this.text = text;
        }

        /**
         * Returns the change the Docker pill makes when Docker-in-Docker is currently {@code enabled}.
         */
        @NotNull
        public static RestartChange toggleDocker(boolean enabled) {
            return enabled ? DOCKER_OFF : DOCKER_ON;
        }

        /**
         * Returns the change the Network pill makes when unrestricted network is currently {@code enabled}.
         */
        @NotNull
        public static RestartChange toggleUnrestricted(boolean enabled) {
            return enabled ? UNRESTRICTED_OFF : UNRESTRICTED_ON;
        }

        /**
         * Returns the configuration the restarted sandbox starts with.
         */
        @NotNull
        public SandboxConfig apply(@NotNull SandboxConfig config) {
            return switch (this) {
                case DOCKER_ON -> config.withWithDocker(true);
                case DOCKER_OFF -> config.withWithDocker(false);
                case UNRESTRICTED_ON -> config.withUnrestrictedNetwork(true);
                case UNRESTRICTED_OFF -> config.withUnrestrictedNetwork(false);
            };
        }
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
