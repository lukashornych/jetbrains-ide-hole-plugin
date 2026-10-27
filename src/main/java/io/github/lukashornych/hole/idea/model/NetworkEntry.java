package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;

/**
 * One effective entry of a network setting as `hole modify … list --json` reports it.
 *
 * @param entry  canonical entry, e.g. `*.example.com:443,80`
 * @param source where it comes from: `settings`, `agent:<name>` or `runtime` (added with `hole modify`)
 */
public record NetworkEntry(@NotNull String entry, @NotNull String source) {

    /** Source of entries added with `hole modify`. */
    public static final String RUNTIME = "runtime";

    /**
     * Returns {@code true} for an entry added with `hole modify`: only those can be removed at runtime.
     */
    public boolean isRuntime() {
        return RUNTIME.equals(this.source);
    }
}
