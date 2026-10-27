package io.github.lukashornych.hole.idea.hole;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing of `hole version` output in the shapes Hole's `version.Display()` produces.
 */
class HoleVersionTest {

    @Test
    void releaseAndGoInstallBuilds() {
        assertVersion("hole 2.6.0", "2.6.0", true);
        assertVersion("hole 2.6.0\n", "2.6.0", true);
        assertVersion("hole 2.6.0 (go install)", "2.6.0", true);
        assertVersion("hole 2.6.1-0.20261020101010-abcdef123456 (go install)", "2.6.1", true);
        assertVersion("hole 2.7.0", "2.7.0", true);
        assertVersion("hole 3.0.0", "3.0.0", true);
        assertVersion("hole 10.0.0", "10.0.0", true);
        assertVersion("hole v2.6.0", "2.6.0", true);
        assertVersion("hole 2.6.0-rc1", "2.6.0", true);
        assertVersion("hole 2.5.9", "2.5.9", false);
        assertVersion("hole 2.3.0", "2.3.0", false);
        assertVersion("hole 1.9.9", "1.9.9", false);
        assertVersion("hole 2.5.10 (go install)", "2.5.10", false);
        assertVersion("hole 2.8.0 (homebrew)", "2.8.0", true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hole development", "hole development (abc1234)", "hole development (abc1234, dirty)"})
    void developmentBuildsAreSupported(String output) {
        final HoleVersion version = HoleVersion.parse(output).orElseThrow();
        assertTrue(version.development());
        assertTrue(version.isAtLeastMinimum());
        assertEquals(HoleVersion.DEVELOPMENT, version.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  \n", "garbage", "hole", "hole 2.6", "hole two", "Usage: hole {command}", "hole 2.6.0 extra",
        "hole 99999999999.0.0"})
    void unparsableOutputGivesNothing(String output) {
        assertEquals(Optional.empty(), HoleVersion.parse(output));
    }

    private static void assertVersion(String output, String version, boolean supported) {
        final Optional<HoleVersion> parsed = HoleVersion.parse(output);
        assertTrue(parsed.isPresent(), output);
        assertEquals(version, parsed.get().toString(), output);
        assertFalse(parsed.get().development(), output);
        assertEquals(supported, parsed.get().isAtLeastMinimum(), output);
    }

    @Test
    void minimumIsTwoSixZero() {
        assertEquals("2.6.0", HoleVersion.MINIMUM.toString());
        assertTrue(HoleVersion.MINIMUM.isAtLeastMinimum());
    }
}
