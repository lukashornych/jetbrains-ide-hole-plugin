package io.github.lukashornych.hole.idea.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxConfigTest {

    @Test
    void defaultsMatchFreshDialog() {
        final SandboxConfig defaults = SandboxConfig.defaults();
        assertEquals("claude", defaults.agent());
        assertEquals("", defaults.profile());
        assertEquals(".", defaults.projectPath());
        assertFalse(defaults.withDocker());
        assertFalse(defaults.unrestrictedNetwork());
        assertFalse(defaults.trustProject());
        assertFalse(defaults.dumpNetworkAccess());
        assertFalse(defaults.rebuild());
        assertFalse(defaults.updateAgents());
        assertTrue(defaults.libraries().isEmpty());
        assertEquals("", defaults.agentArgs());
    }

    @Test
    void librariesAreCopiedDefensively() {
        final List<Library> libraries = new ArrayList<>(List.of(new Library("/a", false)));
        final SandboxConfig config = SandboxConfig.defaults().withLibraries(libraries);
        libraries.add(new Library("/b", true));
        assertEquals(1, config.libraries().size());
        assertThrows(UnsupportedOperationException.class, () -> config.libraries().add(new Library("/c", false)));
    }

    @Test
    void agentSpec() {
        assertEquals("claude", SandboxConfig.defaults().agentSpec());
        assertEquals("gemini:docker", SandboxConfig.defaults().withAgent("gemini").withProfile("docker").agentSpec());
    }

    @Test
    void libraryArgument() {
        assertEquals("/a", new Library("/a", false).toArgument());
        assertEquals("/a:rw", new Library("/a", true).toArgument());
        assertTrue(new Library(" ", true).isBlank());
    }

    @Test
    void favouriteWithScope() {
        final Favourite favourite = new Favourite("", FavouriteScope.PROJECT, SandboxConfig.defaults());
        assertEquals(FavouriteScope.GLOBAL, favourite.withScope(FavouriteScope.GLOBAL).scope());
    }
}
