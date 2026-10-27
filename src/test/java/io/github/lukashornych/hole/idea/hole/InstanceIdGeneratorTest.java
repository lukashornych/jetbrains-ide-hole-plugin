package io.github.lukashornych.hole.idea.hole;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceIdGeneratorTest {

    @RepeatedTest(20)
    void generatesSixLowercaseAlphanumericCharacters() {
        final String id = new InstanceIdGenerator().generate();
        assertEquals(6, id.length());
        assertTrue(id.matches("[a-z0-9]{6}"), id);
        assertTrue(InstanceIdGenerator.isValid(id));
    }

    @Test
    void usesWholeAlphabet() {
        final InstanceIdGenerator generator = new InstanceIdGenerator(new Random(42));
        final Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            for (char c : generator.generate().toCharArray()) {
                seen.add(c);
            }
        }
        assertEquals(InstanceIdGenerator.ALPHABET.length(), seen.size());
    }

    @Test
    void sameSeedGivesSameIds() {
        assertEquals(
            new InstanceIdGenerator(new Random(7)).generate(),
            new InstanceIdGenerator(new Random(7)).generate()
        );
    }

    @Test
    void validation() {
        assertTrue(InstanceIdGenerator.isValid("r8tq2c"));
        assertFalse(InstanceIdGenerator.isValid(null));
        assertFalse(InstanceIdGenerator.isValid(""));
        assertFalse(InstanceIdGenerator.isValid("r8tq2"));
        assertFalse(InstanceIdGenerator.isValid("r8tq2cc"));
        assertFalse(InstanceIdGenerator.isValid("R8TQ2C"));
        assertFalse(InstanceIdGenerator.isValid("r8-q2c"));
    }
}
