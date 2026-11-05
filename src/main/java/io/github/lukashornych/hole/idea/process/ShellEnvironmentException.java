package io.github.lukashornych.hole.idea.process;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;

/**
 * The environment of the user's shell could not be read, or the user cancelled waiting for it. The message names the
 * shell and the reason (exit code, timeout); it never contains environment values or output of the rc files.
 */
@Getter
public class ShellEnvironmentException extends Exception {

    /** The shell that was run. */
    @NotNull
    private final String shell;
    /** Why the read failed, for the user. */
    @NotNull
    private final String reason;
    /** The user cancelled waiting for the read; nothing needs to be reported. */
    private final boolean cancelled;

    public ShellEnvironmentException(@NotNull String shell, @NotNull String reason) {
        this(shell, reason, false);
    }

    private ShellEnvironmentException(@NotNull String shell, @NotNull String reason, boolean cancelled) {
        super(shell + ": " + reason);
        this.shell = shell;
        this.reason = reason;
        this.cancelled = cancelled;
    }

    /**
     * Creates the exception for a read the user stopped waiting for.
     */
    @NotNull
    public static ShellEnvironmentException cancelled(@NotNull String shell) {
        return new ShellEnvironmentException(shell, "cancelled", true);
    }
}
