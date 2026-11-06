package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Entries saved from the Network window extend the settings document and leave the rest of it alone.
 */
class SettingsEntriesTest {

    @Test
    void templateGetsTheSettingWithHolesFormatting() {
        final String template = "{\n  \"$schema\": \"https://example.invalid/hole/settings.schema.json\"\n}\n";
        assertEquals(Optional.of("""
                {
                  "$schema": "https://example.invalid/hole/settings.schema.json",
                  "network": {
                    "allow": [
                      "api.example.com:443,80"
                    ]
                  }
                }
                """),
            SettingsEntries.withEntries(template, NetworkSetting.ALLOW, List.of("api.example.com:443,80")));
    }

    @Test
    void existingArrayIsExtendedWithoutDuplicatesAndOtherKeysAreKept() {
        final String document = """
            {"network": {"hostGatewayDomains": ["host.example.com"], "allow": ["a.example.com"]},
             "profiles": {"research": {"network": {"allow": ["p.example.com"]}}}, "container": {"memory": 4}}""";
        assertEquals(Optional.of("""
                {
                  "network": {
                    "hostGatewayDomains": [
                      "host.example.com"
                    ],
                    "allow": [
                      "a.example.com",
                      "b.example.com:5432"
                    ]
                  },
                  "profiles": {
                    "research": {
                      "network": {
                        "allow": [
                          "p.example.com"
                        ]
                      }
                    }
                  },
                  "container": {
                    "memory": 4
                  }
                }
                """),
            SettingsEntries.withEntries(document, NetworkSetting.ALLOW, List.of("a.example.com", "b.example.com:5432")));
    }

    @Test
    void nothingChangesWhenEveryValueIsThere() {
        assertEquals(Optional.empty(),
            SettingsEntries.withEntries("{\"network\": {\"hostGatewayDomains\": [\"h.example.com\"]}}",
                NetworkSetting.HOST_GATEWAY_DOMAINS, List.of("h.example.com")));
    }

    @Test
    void blankDocumentCountsAsEmptyObject() {
        assertTrue(SettingsEntries.withEntries("  \n", NetworkSetting.HOST_GATEWAY_DOMAINS, List.of("h.example.com"))
            .orElseThrow().contains("\"hostGatewayDomains\": [\n      \"h.example.com\"\n    ]"));
    }

    @Test
    void unexpectedShapesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> SettingsEntries.withEntries("{\"network\": ", NetworkSetting.ALLOW, List.of("a.example.com")));
        assertThrows(IllegalArgumentException.class,
            () -> SettingsEntries.withEntries("[]", NetworkSetting.ALLOW, List.of("a.example.com")));
        assertEquals("`network` is not an object", assertThrows(IllegalArgumentException.class,
            () -> SettingsEntries.withEntries("{\"network\": true}", NetworkSetting.ALLOW, List.of("a.example.com"))).getMessage());
        assertEquals("`network.allow` is not an array", assertThrows(IllegalArgumentException.class,
            () -> SettingsEntries.withEntries("{\"network\": {\"allow\": \"x\"}}", NetworkSetting.ALLOW, List.of("a.example.com"))).getMessage());
    }

    @Test
    void entriesAreComparedInHolesCanonicalForm() {
        assertEquals("example.com:80,443", SettingsEntries.canonical(NetworkSetting.ALLOW, "Example.COM"));
        assertEquals("*.example.com:80,443", SettingsEntries.canonical(NetworkSetting.ALLOW, " *.example.com:443,80,443 "));
        assertEquals("db.example.com:5432", SettingsEntries.canonical(NetworkSetting.HOST_GATEWAY_DOMAINS, "DB.example.com:5432"));
        assertEquals("host.example.com", SettingsEntries.canonical(NetworkSetting.HOST_GATEWAY_DOMAINS, "host.example.com"));
        assertEquals(Optional.empty(),
            SettingsEntries.withEntries("{\"network\": {\"allow\": [\"Example.com\"]}}", NetworkSetting.ALLOW, List.of("example.com:443,80")));
    }

    @Test
    void topLevelEntriesAreReadCanonically() {
        assertEquals(java.util.Set.of("a.example.com:80,443", "b.example.com:5432"), SettingsEntries.entries("""
            {"network": {"allow": ["A.example.com", "b.example.com:5432", 7]},
             "profiles": {"research": {"network": {"allow": ["p.example.com"]}}}}""", NetworkSetting.ALLOW));
        assertEquals(java.util.Set.of(), SettingsEntries.entries("{}", NetworkSetting.ALLOW));
    }

    @Test
    void removalTakesEveryMatchingValueAndKeepsTheRest() {
        assertEquals(Optional.of("""
                {
                  "network": {
                    "allow": [
                      "b.example.com"
                    ]
                  }
                }
                """),
            SettingsEntries.withoutEntry("{\"network\": {\"allow\": [\"a.example.com:443,80\", \"b.example.com\", \"A.example.com\"]}}",
                NetworkSetting.ALLOW, "a.example.com:80,443"));
        assertEquals(Optional.empty(), SettingsEntries.withoutEntry("{}", NetworkSetting.ALLOW, "a.example.com:80,443"));
    }
}
