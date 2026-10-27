package io.github.lukashornych.hole.idea.hole;

import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Generates sandbox instance IDs in the format Hole accepts for `--instance-id`: exactly 6 characters of `[a-z0-9]`
 * (`instanceIDLength` and `instanceIDAlphabet` in Hole's `internal/hostenv`).
 *
 * <p>Uniqueness is not checked here. Hole rejects an ID that is already in use and the caller regenerates it.</p>
 */
public final class InstanceIdGenerator {

    /** Length of an instance ID. */
    public static final int LENGTH = 6;
    /** Characters an instance ID consists of. */
    public static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9]{" + LENGTH + "}");

    private final RandomGenerator random;

    /**
     * Creates a generator backed by {@link SecureRandom}.
     */
    public InstanceIdGenerator() {
        this(new SecureRandom());
    }

    /**
     * Creates a generator backed by the given random source (tests pass a seeded one).
     */
    public InstanceIdGenerator(@NotNull RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Returns a new random instance ID.
     */
    @NotNull
    public String generate() {
        final char[] id = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            id[i] = ALPHABET.charAt(this.random.nextInt(ALPHABET.length()));
        }
        return new String(id);
    }

    /**
     * Returns {@code true} when the value is a well-formed instance ID.
     */
    public static boolean isValid(String id) {
        return id != null && VALID_ID.matcher(id).matches();
    }
}
