package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.InstanceInfo;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Reads running sandboxes through `hole list --json --no-gc [instance-id]`. Blocking; call off the EDT.
 */
public final class HoleListClient {

    /**
     * `hole list` asks the container engine. With `--no-gc` it does not tear down abandoned sandboxes, so it is quick,
     * but a busy engine can still be slow; the budget stays generous, polls never overlap anyway.
     */
    public static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String executable;
    private final CliRunner runner;

    /**
     * @param executable path of the `hole` binary
     * @param runner     runs the process with the user's shell environment
     */
    public HoleListClient(@NotNull String executable, @NotNull CliRunner runner) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    /**
     * Returns all running sandboxes.
     *
     * @throws CliException when Hole fails (non-zero exit, e.g. no container engine) or prints something that is not
     *                      the expected JSON array
     */
    @NotNull
    public List<InstanceInfo> listAll() throws CliException {
        return list(null);
    }

    /**
     * Returns the running sandboxes, filtered to one instance when {@code instanceId} is given. An unknown ID gives
     * an empty list.
     *
     * @throws CliException when Hole fails or prints something that is not the expected JSON array
     */
    @NotNull
    public List<InstanceInfo> list(@Nullable String instanceId) throws CliException {
        return list(instanceId, TIMEOUT);
    }

    /**
     * Like {@link #list(String)} with a custom time budget, e.g. a shorter one for the capability probe.
     *
     * @param instanceId instance to filter to, or {@code null} for all
     * @param timeout    how long to wait for Hole; afterwards it gets SIGTERM, then SIGKILL
     * @throws CliException when Hole fails, does not finish in time or prints something that is not the expected
     *                      JSON array
     */
    @NotNull
    public List<InstanceInfo> list(@Nullable String instanceId, @NotNull Duration timeout) throws CliException {
        final List<String> command = HoleCommandBuilder.listJson(this.executable, instanceId);
        final CliResult result = this.runner.run(command, null, timeout);
        if (!result.isSuccess()) {
            throw new CliException(result, "`hole list --json` failed");
        }
        try {
            return HoleListParser.parse(result.stdout());
        } catch (IllegalArgumentException e) {
            throw new CliException(e.getMessage(), result.command(), result.exitCode(), e);
        }
    }
}
