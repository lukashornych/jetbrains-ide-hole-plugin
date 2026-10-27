package io.github.lukashornych.hole.idea.testutil;

import org.jetbrains.annotations.NotNull;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests move forward by hand.
 */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock(@NotNull Instant start) {
        this.now = start;
    }

    public void advance(@NotNull Duration duration) {
        this.now = this.now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return this.now;
    }
}
