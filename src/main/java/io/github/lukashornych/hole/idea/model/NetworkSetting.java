package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;

/**
 * The settings `hole modify` can change in a running sandbox.
 */
public enum NetworkSetting {
    /** Egress allow list; Hole also lists the names the gateway blocked. */
    ALLOW("network.allow", true),
    /** Domains resolved to the host gateway; no blocked list. */
    HOST_GATEWAY_DOMAINS("network.hostGatewayDomains", false);

    private final String key;
    private final boolean hasBlocked;

    NetworkSetting(@NotNull String key, boolean hasBlocked) {
        this.key = key;
        this.hasBlocked = hasBlocked;
    }

    /**
     * Returns the setting as `hole modify` names it, e.g. `network.allow`.
     */
    @NotNull
    public String key() {
        return this.key;
    }

    /**
     * Returns {@code true} when `hole modify … list-blocked` is supported for the setting.
     */
    public boolean hasBlocked() {
        return this.hasBlocked;
    }
}
