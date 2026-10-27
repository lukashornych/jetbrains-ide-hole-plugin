package io.github.lukashornych.hole.idea.ui.network;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Prefills of the Add input for a blocked name, like the keys of Hole's `hole modify` TUI (`internal/modifyui`):
 * <i>Allow</i> ({@code a}) → {@code name:443,80}, <i>Allow on ports…</i> ({@code p}) → {@code name:}, <i>Allow
 * {@code *.parent}</i> ({@code w}) → {@code *.<parent>:443,80}. The entry is shown before it is applied.
 */
public final class BlockedSuggestions {

    /** Ports of a plain allow: HTTPS and HTTP. */
    static final String WEB_PORTS = ":443,80";

    private BlockedSuggestions() {
    }

    /**
     * Returns {@code name:443,80}.
     */
    @NotNull
    public static String allow(@NotNull String name) {
        return name + WEB_PORTS;
    }

    /**
     * Returns {@code name:}, so the user types the ports.
     */
    @NotNull
    public static String allowOnPorts(@NotNull String name) {
        return name + ":";
    }

    /**
     * Returns {@code *.<parent>:443,80}, or nothing when Hole's {@code WildcardFor} offers no wildcard: the parent
     * (the name without its first label) must contain at least two dots, so a registrable domain such as
     * {@code example.com} is never wildcarded.
     */
    @NotNull
    public static Optional<String> wildcard(@NotNull String name) {
        final int dot = name.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        final String parent = name.substring(dot + 1);
        if (parent.chars().filter(c -> c == '.').count() < 2) {
            return Optional.empty();
        }
        return Optional.of("*." + parent + WEB_PORTS);
    }
}
