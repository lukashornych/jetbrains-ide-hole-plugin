package io.github.lukashornych.hole.idea.model;

import lombok.With;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Everything the user chooses for one sandbox start: the values of the New Sandbox form and of a favourite.
 * The instance ID and the start mode (agent or debug shell) are not part of it, they are chosen at start time.
 *
 * @param agent               agent name, e.g. `claude`
 * @param profile             profile name, empty when none is selected
 * @param projectPath         project path exactly as typed; `.` means the IDE project root and stays `.` in the command
 * @param withDocker          `--with-docker`, Docker-in-Docker sidecar
 * @param unrestrictedNetwork `-u`, no egress filtering
 * @param trustProject        `--trust-project`
 * @param dumpNetworkAccess   `-n`, log network access on exit
 * @param rebuild             `-r`, rebuild images
 * @param updateAgents        `--update-agents`
 * @param imagePaste          mount a read-only folder for pasted images (not a Hole flag: the plugin adds a
 *                            `--library`, see {@code PasteDirectory})
 * @param libraries           `--library` rows in the order given
 * @param agentArgs           free text passed to the agent after `--` (split like a shell command line)
 */
@With
public record SandboxConfig(
    @NotNull String agent,
    @NotNull String profile,
    @NotNull String projectPath,
    boolean withDocker,
    boolean unrestrictedNetwork,
    boolean trustProject,
    boolean dumpNetworkAccess,
    boolean rebuild,
    boolean updateAgents,
    boolean imagePaste,
    @NotNull List<Library> libraries,
    @NotNull String agentArgs
) {

    /** Agent selected in a fresh New Sandbox dialog. */
    public static final String DEFAULT_AGENT = "claude";
    /** Project path of a fresh New Sandbox dialog: the IDE project root. */
    public static final String DEFAULT_PROJECT_PATH = ".";

    public SandboxConfig {
        libraries = List.copyOf(libraries);
    }

    /**
     * Returns the values a New Sandbox dialog opens with: `claude`, no profile, `.`, no options except image paste,
     * no libraries and no agent arguments.
     */
    @NotNull
    public static SandboxConfig defaults() {
        return new SandboxConfig(
            DEFAULT_AGENT, "", DEFAULT_PROJECT_PATH,
            false, false, false, false, false, false, true,
            List.of(), ""
        );
    }

    /**
     * Returns the agent positional of `hole start`: `agent` or `agent:profile`.
     */
    @NotNull
    public String agentSpec() {
        return this.profile.isBlank() ? this.agent.trim() : this.agent.trim() + ":" + this.profile.trim();
    }
}
