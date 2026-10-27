package io.github.lukashornych.hole.idea.ui.network;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Parity with Hole's `WildcardFor` and the TUI's prefills (`internal/modifyui/staging.go`).
 */
class BlockedSuggestionsTest {

    @Test
    void portPrefills() {
        assertEquals("telemetry.example.com:443,80", BlockedSuggestions.allow("telemetry.example.com"));
        assertEquals("telemetry.example.com:", BlockedSuggestions.allowOnPorts("telemetry.example.com"));
    }

    @Test
    void wildcardOnlyWhenTheParentHasAtLeastTwoDots() {
        assertEquals(Optional.empty(), BlockedSuggestions.wildcard("api.example.com"), "example.com has one dot");
        assertEquals(Optional.of("*.b.example.com:443,80"), BlockedSuggestions.wildcard("a.b.example.com"));
        assertEquals(Optional.of("*.b.c.example.com:443,80"), BlockedSuggestions.wildcard("a.b.c.example.com"));
        assertEquals(Optional.empty(), BlockedSuggestions.wildcard("localhost"));
        assertEquals(Optional.empty(), BlockedSuggestions.wildcard("example.com"));
    }
}
