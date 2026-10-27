package io.github.lukashornych.hole.idea.process;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Outcome of a finished command line process.
 *
 * @param command  the command that ran (executable resolved to an absolute path)
 * @param exitCode exit code of the process
 * @param stdout   everything the process wrote to stdout, decoded as UTF-8
 * @param stderr   everything the process wrote to stderr, decoded as UTF-8
 */
public record CliResult(@NotNull List<String> command, int exitCode, @NotNull String stdout, @NotNull String stderr) {

    /** Prefix of Hole's error lines (uncoloured: Hole colours them only on a terminal). */
    private static final String ERROR_PREFIX = "[ERROR]";

    public CliResult {
        command = List.copyOf(command);
    }

    /**
     * Returns {@code true} when the process exited with code 0.
     */
    public boolean isSuccess() {
        return this.exitCode == 0;
    }

    /**
     * Returns the first non-blank line of stderr (trimmed), or an empty string. Used in error messages; the full
     * stderr may be long.
     */
    @NotNull
    public String firstStderrLine() {
        return this.stderr.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
    }

    /**
     * Returns Hole's error message: the first `[ERROR] …` line of stderr without the prefix (Hole may print
     * `[INFO] …` lines before it), or {@link #firstStderrLine()} when there is none.
     */
    @NotNull
    public String errorMessage() {
        return this.stderr.lines().map(String::trim)
            .filter(line -> line.startsWith(ERROR_PREFIX))
            .map(line -> line.substring(ERROR_PREFIX.length()).trim())
            .findFirst()
            .orElseGet(this::firstStderrLine);
    }
}
