package io.github.lukashornych.hole.idea.testutil;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Polling waits for asynchronous tests.
 */
public final class Waiting {

    private Waiting() {
    }

    public static void waitUntil(@NotNull BooleanSupplier condition, @NotNull String what) {
        waitUntil(condition, () -> what);
    }

    public static void waitUntil(@NotNull BooleanSupplier condition, @NotNull Supplier<String> what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20);
        }
        fail("Timed out waiting for: " + what.get());
    }

    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        }
    }
}
