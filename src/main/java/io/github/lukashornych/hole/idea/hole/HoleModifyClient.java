package io.github.lukashornych.hole.idea.hole;

import com.intellij.openapi.diagnostic.Logger;
import io.github.lukashornych.hole.idea.model.BlockedName;
import io.github.lukashornych.hole.idea.model.NetworkEntry;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliResult;
import io.github.lukashornych.hole.idea.process.CliRunner;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Changes the network policy of one running sandbox through `hole modify` (Hole 2.6.0): lists the effective entries
 * and the blocked names, adds and removes runtime entries. Every call names the sandbox by its absolute project
 * directory and `--instance-id`, because without a terminal Hole cannot ask which sandbox is meant. Each add or remove
 * is applied by the gateway at once (several values in one apply). Blocking; call off the EDT.
 *
 * <p>Exit codes of `hole modify` are mapped to {@link ModifyFailure}: 2 invalid value or conflict, 3 not running /
 * starting / stopping / started by an older Hole, 4 the gateway did not apply the change (the previous policy is still
 * in force). Entries are domains, not secrets, so the commands may be logged.</p>
 */
public final class HoleModifyClient {

    /** A gateway apply can take a few seconds; `list-blocked` reads the whole gateway log. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Logger LOG = Logger.getInstance(HoleModifyClient.class);
    private static final String OLDER_HOLE_MESSAGE = "started by an older Hole";

    private final String executable;
    private final CliRunner runner;
    private final Path projectDirectory;
    private final String instanceId;

    /**
     * @param executable       path of the `hole` binary
     * @param runner           runs Hole with the user's shell environment
     * @param projectDirectory absolute project directory of the sandbox
     * @param instanceId       instance ID of the sandbox
     */
    public HoleModifyClient(
        @NotNull String executable, @NotNull CliRunner runner, @NotNull Path projectDirectory, @NotNull String instanceId
    ) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    /**
     * Returns the instance ID the client changes.
     */
    @NotNull
    public String instanceId() {
        return this.instanceId;
    }

    /**
     * Lists the effective entries of a setting with their sources.
     */
    @NotNull
    public Result<List<NetworkEntry>> list(@NotNull NetworkSetting setting) {
        return run(HoleCommandBuilder.modifyList(this.executable, directory(), this.instanceId, setting.key()),
            HoleModifyParser::entries);
    }

    /**
     * Lists the names the gateway refused, newest first (only {@link NetworkSetting#ALLOW}).
     */
    @NotNull
    public Result<List<BlockedName>> listBlocked() {
        return run(HoleCommandBuilder.modifyListBlocked(this.executable, directory(), this.instanceId, NetworkSetting.ALLOW.key()),
            HoleModifyParser::blocked);
    }

    /**
     * Adds runtime entries in one gateway apply; entries that are already allowed change nothing.
     */
    @NotNull
    public Result<Void> add(@NotNull NetworkSetting setting, @NotNull List<String> values) {
        return run(HoleCommandBuilder.modifyAdd(this.executable, directory(), this.instanceId, setting.key(), values), out -> null);
    }

    /**
     * Removes runtime entries in one gateway apply; entries from the settings or an agent cannot be removed (exit 2).
     */
    @NotNull
    public Result<Void> remove(@NotNull NetworkSetting setting, @NotNull List<String> values) {
        return run(HoleCommandBuilder.modifyRemove(this.executable, directory(), this.instanceId, setting.key(), values), out -> null);
    }

    @NotNull
    private String directory() {
        return this.projectDirectory.toString();
    }

    @NotNull
    private <T> Result<T> run(@NotNull List<String> command, @NotNull Function<String, T> parse) {
        // entries are domains: the command line may be logged, unlike `hole start`'s
        LOG.debug("Running " + String.join(" ", command.subList(1, command.size())));
        final CliResult result;
        try {
            result = this.runner.run(command, null, TIMEOUT);
        } catch (CliException e) {
            LOG.info("`hole modify` failed for instance " + this.instanceId + ": " + e.getMessage());
            return Result.failure(ModifyFailure.OTHER, Objects.requireNonNullElse(e.getMessage(), "Hole could not be run"));
        }
        if (!result.isSuccess()) {
            final ModifyFailure failure = ModifyFailure.of(result.exitCode(), result.errorMessage());
            LOG.info("`hole modify` exited with code " + result.exitCode() + " for instance " + this.instanceId + ": "
                + result.errorMessage());
            return Result.failure(failure, result.errorMessage());
        }
        try {
            return Result.success(parse.apply(result.stdout()));
        } catch (IllegalArgumentException e) {
            LOG.warn("Unexpected output of `hole modify` for instance " + this.instanceId + ": " + e.getMessage());
            return Result.failure(ModifyFailure.OTHER, Objects.requireNonNullElse(e.getMessage(), "Unexpected output"));
        }
    }

    /**
     * Why a `hole modify` call failed.
     */
    public enum ModifyFailure {
        /** Exit 2: invalid value, a conflict, a base entry to remove, `network.allow` with `-u`. */
        INVALID,
        /** Exit 3: the sandbox is not running, still starting or stopping. */
        NOT_RUNNING,
        /** Exit 3 for a sandbox started by a Hole before `modify`: it must be restarted. */
        OLDER_HOLE,
        /** Exit 4: the gateway did not apply the change; the previous policy is still in force. */
        APPLY_FAILED,
        /** Anything else (usage error, timeout, Hole not runnable, unparsable output). */
        OTHER;

        /**
         * Maps an exit code and Hole's message.
         */
        @NotNull
        static ModifyFailure of(int exitCode, @NotNull String message) {
            return switch (exitCode) {
                case 2 -> INVALID;
                case 3 -> message.contains(OLDER_HOLE_MESSAGE) ? OLDER_HOLE : NOT_RUNNING;
                case 4 -> APPLY_FAILED;
                default -> OTHER;
            };
        }
    }

    /**
     * Outcome of one call.
     *
     * @param value   the parsed answer, {@code null} on failure (and for add / remove)
     * @param failure {@code null} on success
     * @param message Hole's message on failure, otherwise empty
     */
    public record Result<T>(@Nullable T value, @Nullable ModifyFailure failure, @NotNull String message) {

        @NotNull
        static <T> Result<T> success(@Nullable T value) {
            return new Result<>(value, null, "");
        }

        @NotNull
        static <T> Result<T> failure(@NotNull ModifyFailure failure, @NotNull String message) {
            return new Result<>(null, failure, message);
        }

        /**
         * Runs a call and turns an unexpected exception into an {@link ModifyFailure#OTHER} failure, so callers that
         * track calls in flight always get a result.
         */
        @NotNull
        public static <T> Result<T> guarded(@NotNull java.util.function.Supplier<Result<T>> call) {
            try {
                return call.get();
            } catch (RuntimeException e) {
                LOG.warn("`hole modify` call failed unexpectedly", e);
                return failure(ModifyFailure.OTHER, Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName()));
            }
        }

        /**
         * Returns {@code true} when Hole succeeded.
         */
        public boolean isSuccess() {
            return this.failure == null;
        }
    }
}
