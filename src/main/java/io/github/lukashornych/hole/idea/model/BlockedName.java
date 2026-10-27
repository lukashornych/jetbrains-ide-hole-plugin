package io.github.lukashornych.hole.idea.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.OffsetDateTime;

/**
 * A name the sandbox's gateway refused, as `hole modify … list-blocked --json` reports it.
 *
 * @param name      the refused DNS name
 * @param count     how often it was refused
 * @param firstSeen first refusal, {@code null} when Hole reported none or an unparsable value
 * @param lastSeen  last refusal, {@code null} when Hole reported none or an unparsable value
 */
public record BlockedName(@NotNull String name, long count, @Nullable OffsetDateTime firstSeen, @Nullable OffsetDateTime lastSeen) {
}
