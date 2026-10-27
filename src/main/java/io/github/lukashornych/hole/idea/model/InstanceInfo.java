package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Metadata of one running sandbox, parsed from `hole list --json`. Hole's merged settings snapshot is deliberately not
 * part of the JSON contract and therefore not part of this record.
 *
 * @param instanceId    6-character instance ID
 * @param instanceName  `<project>-<id>`
 * @param status        lifecycle status
 * @param agent         agent name
 * @param profile       profile name, empty when none
 * @param projectPath   absolute project path
 * @param projectName   Docker-safe project name
 * @param startedAt     start time, {@code null} when Hole reported none or an unparsable value
 * @param uptimeSeconds uptime when Hole answered
 * @param docker        Docker-in-Docker sidecar enabled
 * @param networks      Docker networks of the sandbox
 * @param settings      settings labels as in the `hole list` table, e.g. `global(~/.hole/settings.json)`, `project`
 * @param settingsFiles absolute paths of the settings files used
 * @param flags         start flags
 * @param pid           PID of the `hole start` process, 0 when unknown
 * @param version       Hole version that started the sandbox
 * @param runtimeEntryCount entries added with `hole modify` to the running sandbox (lost on a restart), 0 when Hole
 *                          reported none
 */
public record InstanceInfo(
    @NotNull String instanceId,
    @NotNull String instanceName,
    @NotNull InstanceStatus status,
    @NotNull String agent,
    @NotNull String profile,
    @NotNull String projectPath,
    @NotNull String projectName,
    @Nullable OffsetDateTime startedAt,
    long uptimeSeconds,
    boolean docker,
    @NotNull List<String> networks,
    @NotNull List<String> settings,
    @NotNull List<String> settingsFiles,
    @NotNull InstanceFlags flags,
    long pid,
    @NotNull String version,
    long runtimeEntryCount
) {

    public InstanceInfo {
        networks = List.copyOf(networks);
        settings = List.copyOf(settings);
        settingsFiles = List.copyOf(settingsFiles);
    }

    /**
     * Returns the settings labels joined like the `hole list` table, e.g. `global(~/.hole/settings.json)+project`.
     */
    @NotNull
    public String settingsLabel() {
        return String.join("+", this.settings);
    }

    /**
     * Returns the uptime at {@code now}, computed locally from {@link #startedAt()} so it can tick between polls.
     * Falls back to {@link #uptimeSeconds()} when the start time is unknown; never negative.
     */
    @NotNull
    public Duration uptimeAt(@NotNull Instant now) {
        if (this.startedAt == null) {
            return Duration.ofSeconds(Math.max(0, this.uptimeSeconds));
        }
        final Duration uptime = Duration.between(this.startedAt.toInstant(), now);
        return uptime.isNegative() ? Duration.ZERO : uptime;
    }
}
