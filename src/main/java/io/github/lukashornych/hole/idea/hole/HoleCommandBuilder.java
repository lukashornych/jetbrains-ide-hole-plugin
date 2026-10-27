package io.github.lukashornych.hole.idea.hole;

import com.intellij.util.execution.ParametersListUtil;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the argument lists of the Hole commands the plugin runs. Commands are always lists of arguments that are
 * passed to the process as they are, never shell strings.
 *
 * <p>`hole start` has the shape (flag order as in the design mockup):</p>
 * <pre>
 * hole start agent[:profile] path [-d] [-n] [-r] [--update-agents] [-u] [--with-docker] [--trust-project]
 *      [--library PATH[:rw]]... --instance-id ID [-- agent args...]
 * </pre>
 *
 * <p>The project path is passed verbatim, so `.` stays `.` and is resolved by Hole against the working directory
 * (the IDE project root). The debug variant adds `-d` and leaves out the agent arguments, because Hole refuses `-d`
 * together with arguments after `--`.</p>
 */
public final class HoleCommandBuilder {

    /** Separator after which Hole passes all arguments to the agent. */
    public static final String AGENT_ARGS_SEPARATOR = "--";

    private HoleCommandBuilder() {
    }

    /**
     * Returns the `hole start` command that runs the agent.
     *
     * @param executable path of the `hole` binary (first element of the list)
     * @param config     sandbox configuration
     * @param instanceId instance ID chosen by the plugin
     * @throws IllegalArgumentException when the agent is blank or the instance ID is malformed
     */
    @NotNull
    public static List<String> start(@NotNull String executable, @NotNull SandboxConfig config, @NotNull String instanceId) {
        return buildStart(executable, config, instanceId, false);
    }

    /**
     * Returns the `hole start -d` command that opens a debug shell instead of the agent. Agent arguments are left out.
     *
     * @param executable path of the `hole` binary (first element of the list)
     * @param config     sandbox configuration
     * @param instanceId instance ID chosen by the plugin
     * @throws IllegalArgumentException when the agent is blank or the instance ID is malformed
     */
    @NotNull
    public static List<String> debug(@NotNull String executable, @NotNull SandboxConfig config, @NotNull String instanceId) {
        return buildStart(executable, config, instanceId, true);
    }

    /**
     * Returns `hole list --json --no-gc`, optionally filtered to one instance. `--no-gc` skips Hole's cleanup of dead
     * sandboxes, which costs several container engine calls; the list stays correct (a dead sandbox is never listed),
     * and Hole still cleans up on every `hole start` and every plain `hole list`. The plugin polls, so it always
     * passes it.
     *
     * @param executable path of the `hole` binary
     * @param instanceId instance to filter to, or {@code null} for all running instances
     */
    @NotNull
    public static List<String> listJson(@NotNull String executable, @Nullable String instanceId) {
        final List<String> command = new ArrayList<>(5);
        command.add(executable);
        command.add("list");
        command.add("--json");
        command.add("--no-gc");
        if (instanceId != null) {
            command.add(instanceId);
        }
        return Collections.unmodifiableList(command);
    }

    /**
     * Returns `hole version`, which prints `hole <version>` (see {@link HoleVersion}).
     *
     * @param executable path of the `hole` binary
     */
    @NotNull
    public static List<String> version(@NotNull String executable) {
        return List.of(executable, "version");
    }

    /**
     * Returns `hole settings get global`: prints the path of the global settings file and exits 0 when it exists,
     * fails otherwise.
     *
     * @param executable path of the `hole` binary
     */
    @NotNull
    public static List<String> settingsGetGlobal(@NotNull String executable) {
        return List.of(executable, "settings", "get", "global");
    }

    /**
     * Returns `hole settings get project <path>`: prints the path of the project settings file and exits 0 when it
     * exists, fails otherwise.
     *
     * @param executable       path of the `hole` binary
     * @param projectDirectory the project directory, resolved to an absolute path
     */
    @NotNull
    public static List<String> settingsGetProject(@NotNull String executable, @NotNull String projectDirectory) {
        return List.of(executable, "settings", "get", "project", projectDirectory);
    }

    /**
     * Splits the free-text agent arguments the way a shell would split them (whitespace separated, single and double
     * quotes group words). Quotes are removed, empty quoted arguments (`""`) are kept, nothing is expanded.
     */
    @NotNull
    public static List<String> parseAgentArgs(@NotNull String agentArgs) {
        if (agentArgs.isBlank()) {
            return List.of();
        }
        return List.copyOf(ParametersListUtil.parse(agentArgs.trim(), false, true, true));
    }

    @NotNull
    private static List<String> buildStart(
        @NotNull String executable,
        @NotNull SandboxConfig config,
        @NotNull String instanceId,
        boolean debug
    ) {
        if (config.agent().isBlank()) {
            throw new IllegalArgumentException("Agent must not be blank.");
        }
        if (!InstanceIdGenerator.isValid(instanceId)) {
            throw new IllegalArgumentException(
                "Invalid instance ID '" + instanceId + "': expected 6 characters a-z, 0-9."
            );
        }

        final List<String> command = new ArrayList<>(16);
        command.add(executable);
        command.add("start");
        command.add(config.agentSpec());
        command.add(config.projectPath().isBlank() ? SandboxConfig.DEFAULT_PROJECT_PATH : config.projectPath());
        if (debug) {
            command.add("-d");
        }
        if (config.dumpNetworkAccess()) {
            command.add("-n");
        }
        if (config.rebuild()) {
            command.add("-r");
        }
        if (config.updateAgents()) {
            command.add("--update-agents");
        }
        if (config.unrestrictedNetwork()) {
            command.add("-u");
        }
        if (config.withDocker()) {
            command.add("--with-docker");
        }
        if (config.trustProject()) {
            command.add("--trust-project");
        }
        for (Library library : config.libraries()) {
            if (!library.isBlank()) {
                command.add("--library");
                command.add(library.toArgument());
            }
        }
        command.add("--instance-id");
        command.add(instanceId);
        if (!debug) {
            final List<String> agentArgs = parseAgentArgs(config.agentArgs());
            if (!agentArgs.isEmpty()) {
                command.add(AGENT_ARGS_SEPARATOR);
                command.addAll(agentArgs);
            }
        }
        return Collections.unmodifiableList(command);
    }
}
