package io.github.lukashornych.hole.idea.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsFileLinksTest {

    @Test
    void missingFileIsMarkedAsNotCreated() {
        final Path home = Path.of(System.getProperty("user.home"));
        assertEquals("~/.hole/settings.json", SettingsFileLinks.pathText(home.resolve(".hole/settings.json"), true));
        assertEquals("~/.hole/settings.json (not created)", SettingsFileLinks.pathText(home.resolve(".hole/settings.json"), false));
        assertEquals("/etc/hole/global.json (not created)", SettingsFileLinks.pathText(Path.of("/etc/hole/global.json"), false));
    }
}
