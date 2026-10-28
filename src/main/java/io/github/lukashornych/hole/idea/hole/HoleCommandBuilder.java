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
 * <p>`hole start` has the shape:</p>
 * <pre>
 * hole start agent[:profile] path [-d] [-n] [-r] [--update-agents] [-u] [--with-docker] [--trust-project]
 *      [--library PATH[:rw]]... [--library PASTE_DIR:/tmp/hole-idea-paste] --instance-id ID [-- agent args...]
 * </pre>
 *
 * <p>The project path is passed verbatim, so `.` stays `.` and is resolved by Hole against the working directory
 * (the IDE project root). The debug variant adds `-d` and leaves out the agent arguments, because Hole refuses `-d`
 * together with arguments after `--`. The paste directory of the image paste is mounted read-only after the user's
 * libraries; the builder only formats it, creating it is up to the caller.</p>
 */
public final class HoleCommandBuilder {

    /** Separator after which Hole passes all arguments to the agent. */
    public static final String AGENT_ARGS_SEPARATOR = "--";
    /** Container path of the paste directory: short, no spaces, and it does not reveal the host home path. */
    public static final String PASTE_MOUNT = "/tmp/hole-idea-paste";

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
        return start(executable, config, instanceId, null);
    }

    /**
     * Returns the `hole start` command that runs the agent, with the paste directory mounted.
     *
     * @param executable     path of the `hole` binary (first element of the list)
     * @param config         sandbox configuration
     * @param instanceId     instance ID chosen by the plugin
     * @param pasteDirectory host directory mounted read-only at {@link #PASTE_MOUNT}, {@code null} for none
     * @throws IllegalArgumentException when the agent is blank or the instance ID is malformed
     */
    @NotNull
    public static List<String> start(
        @NotNull String executable, @NotNull SandboxConfig config, @NotNull String instanceId, @Nullable String pasteDirectory
    ) {
        return buildStart(executable, config, instanceId, pasteDirectory, false);
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
        return debug(executable, config, instanceId, null);
    }

    /**
     * Returns the `hole start -d` command with the paste directory mounted. Agent arguments are left out.
     *
     * @param executable     path of the `hole` binary (first element of the list)
     * @param config         sandbox configuration
     * @param instanceId     instance ID chosen by the plugin
     * @param pasteDirectory host directory mounted read-only at {@link #PASTE_MOUNT}, {@code null} for none
     * @throws IllegalArgumentException when the agent is blank or the instance ID is malformed
     */
    @NotNull
    public static List<String> debug(
        @NotNull String executable, @NotNull SandboxConfig config, @NotNull String instanceId, @Nullable String pasteDirectory
    ) {
        return buildStart(executable, config, instanceId, pasteDirectory, true);
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
     * Returns `hole settings create global`: writes Hole's settings template to the global settings file (creating
     * its directories); fails when the file exists. No `--overwrite` / `--append`: the plugin only creates files.
     *
     * @param executable path of the `hole` binary
     */
    @NotNull
    public static List<String> settingsCreateGlobal(@NotNull String executable) {
        return List.of(executable, "settings", "create", "global");
    }

    /**
     * Returns `hole settings create project <path>`: like {@link #settingsCreateGlobal(String)} for the project
     * settings file of the directory.
     *
     * @param executable       path of the `hole` binary
     * @param projectDirectory the project directory, resolved to an absolute path
     */
    @NotNull
    public static List<String> settingsCreateProject(@NotNull String executable, @NotNull String projectDirectory) {
        return List.of(executable, "settings", "create", "project", projectDirectory);
    }

    /**
     * Returns `hole modify <dir> <setting> list --instance-id ID --json`: the effective entries with their sources.
     *
     * @param executable       path of the `hole` binary
     * @param projectDirectory absolute project directory of the sandbox
     * @param instanceId       the sandbox (always given: without a terminal Hole cannot ask which one)
     * @param setting          `network.allow` or `network.hostGatewayDomains`
     */
    @NotNull
    public static List<String> modifyList(
        @NotNull String executable, @NotNull String projectDirectory, @NotNull String instanceId, @NotNull String setting
    ) {
        return modify(executable, projectDirectory, instanceId, setting, "list", List.of(), true);
    }

    /**
     * Returns `hole modify <dir> network.allow list-blocked --instance-id ID --json`: the names the gateway refused,
     * newest first. It reads the gateway log, so it is the slowest call.
     */
    @NotNull
    public static List<String> modifyListBlocked(
        @NotNull String executable, @NotNull String projectDirectory, @NotNull String instanceId, @NotNull String setting
    ) {
        return modify(executable, projectDirectory, instanceId, setting, "list-blocked", List.of(), true);
    }

    /**
     * Returns `hole modify <dir> <setting> add <values…> --instance-id ID`: all values in one gateway apply. Values
     * are separate arguments (no shell), so `*.example.com` needs no quoting.
     */
    @NotNull
    public static List<String> modifyAdd(
        @NotNull String executable, @NotNull String projectDirectory, @NotNull String instanceId, @NotNull String setting,
        @NotNull List<String> values
    ) {
        return modify(executable, projectDirectory, instanceId, setting, "add", values, false);
    }

    /**
     * Returns `hole modify <dir> <setting> remove <values…> --instance-id ID`; Hole removes only runtime entries.
     */
    @NotNull
    public static List<String> modifyRemove(
        @NotNull String executable, @NotNull String projectDirectory, @NotNull String instanceId, @NotNull String setting,
        @NotNull List<String> values
    ) {
        return modify(executable, projectDirectory, instanceId, setting, "remove", values, false);
    }

    @NotNull
    private static List<String> modify(
        @NotNull String executable, @NotNull String projectDirectory, @NotNull String instanceId, @NotNull String setting,
        @NotNull String operation, @NotNull List<String> values, boolean json
    ) {
        if (!InstanceIdGenerator.isValid(instanceId)) {
            throw new IllegalArgumentException("Invalid instance ID '" + instanceId + "': expected 6 characters a-z, 0-9.");
        }
        if ((operation.equals("add") || operation.equals("remove")) && values.isEmpty()) {
            throw new IllegalArgumentException(operation + " needs at least one value.");
        }
        final List<String> command = new ArrayList<>(8 + values.size());
        command.add(executable);
        command.add("modify");
        command.add(projectDirectory);
        command.add(setting);
        command.add(operation);
        command.addAll(values);
        command.add("--instance-id");
        command.add(instanceId);
        if (json) {
            command.add("--json");
        }
        return Collections.unmodifiableList(command);
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
        @Nullable String pasteDirectory,
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
        if (pasteDirectory != null) {
            // no `:rw`: the sandbox only reads the pasted images
            command.add("--library");
            command.add(pasteDirectory + ":" + PASTE_MOUNT);
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
