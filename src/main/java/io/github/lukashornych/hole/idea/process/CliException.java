package io.github.lukashornych.hole.idea.process;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A command line process could not be run, did not finish in time, exited with an error or produced output that
 * could not be understood. The message always names the command, and the exit code when there is one, so it can be
 * logged as it is. It never contains the process environment.
 */
@Getter
public class CliException extends Exception {

    /** The command that failed. */
    @NotNull
    private final List<String> command;
    /** Exit code of the process, {@code null} when it did not start or did not finish. */
    @Nullable
    private final Integer exitCode;
    /** First non-blank stderr line of the process, empty when unknown. */
    @NotNull
    private final String errorOutput;

    public CliException(@NotNull String message, @NotNull List<String> command, @Nullable Integer exitCode) {
        this(message, command, exitCode, null);
    }

    public CliException(
        @NotNull String message,
        @NotNull List<String> command,
        @Nullable Integer exitCode,
        @Nullable Throwable cause
    ) {
        this(message, command, exitCode, "", cause);
    }

    /**
     * Creates the exception for a process that finished with an error; the first stderr line is appended to the
     * message and kept as {@link #getErrorOutput()}.
     */
    public CliException(@NotNull CliResult result, @NotNull String message) {
        this(message + (result.firstStderrLine().isEmpty() ? "" : ": " + result.firstStderrLine()),
            result.command(), result.exitCode(), result.firstStderrLine(), null);
    }

    private CliException(
        @NotNull String message,
        @NotNull List<String> command,
        @Nullable Integer exitCode,
        @NotNull String errorOutput,
        @Nullable Throwable cause
    ) {
        super(message + " [command: " + String.join(" ", command) + (exitCode == null ? "" : ", exit code: " + exitCode) + "]", cause);
        this.command = List.copyOf(command);
        this.exitCode = exitCode;
        this.errorOutput = errorOutput;
    }
}
