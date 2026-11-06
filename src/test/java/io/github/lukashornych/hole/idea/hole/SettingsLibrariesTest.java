package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.hole.SettingsLibraries.HostResolver;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries.Library;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries.Origin;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The `libraries` setting edited from the Libraries window keeps the rest of the document and the form of every
 * entry it does not touch, and reads the two files the way Hole merges them.
 */
class SettingsLibrariesTest {

    private static final HostResolver RESOLVER = new HostResolver(Path.of("/home/user"), Path.of("/home/user/project"));

    @Test
    void bothFormsAreRead() {
        final String document = """
            {"libraries": {"~/a": "/libs/a", "~/b": {"path": "/libs/b", "readwrite": true}, "~/c": {"path": "/libs/c"},
             "~/d": 42, "~/e": {"readwrite": true}}}""";
        assertEquals(List.of(
            new Library("~/a", "/libs/a", false, true, Origin.GLOBAL),
            new Library("~/b", "/libs/b", true, true, Origin.GLOBAL),
            new Library("~/c", "/libs/c", false, true, Origin.GLOBAL),
            new Library("~/d", "", false, false, Origin.GLOBAL),
            new Library("~/e", "", true, false, Origin.GLOBAL)
        ), SettingsLibraries.libraries(document, Origin.GLOBAL));
    }

    @Test
    void readOnlyLibraryIsAddedAsShorthandAndEverythingElseIsKept() {
        final String document = """
            {"$schema": "x", "libraries": {"~/b": {"path": "/libs/b", "readwrite": true}},
             "profiles": {"p": {"libraries": {"~/p": "/libs/p"}}}, "container": {"memory": 4}}""";
        assertEquals("""
                {
                  "$schema": "x",
                  "libraries": {
                    "~/b": {
                      "path": "/libs/b",
                      "readwrite": true
                    },
                    "~/a": "/libs/a"
                  },
                  "profiles": {
                    "p": {
                      "libraries": {
                        "~/p": "/libs/p"
                      }
                    }
                  },
                  "container": {
                    "memory": 4
                  }
                }
                """,
            SettingsLibraries.withLibrary(document, "~/a", "/libs/a", false, RESOLVER));
    }

    @Test
    void readWriteLibraryIsAddedInObjectFormToABlankDocument() {
        assertEquals("""
                {
                  "libraries": {
                    "/srv/lib": {
                      "path": "/libs/lib",
                      "readwrite": true
                    }
                  }
                }
                """,
            SettingsLibraries.withLibrary("", "/srv/lib", "/libs/lib", true, RESOLVER));
    }

    @Test
    void sameDirectoryUnderAnotherKeyIsRefused() {
        final String document = "{\"libraries\": {\"~/lib/\": \"/libs/lib\"}}";
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SettingsLibraries.withLibrary(document, "/home/user/lib", "/x", false, RESOLVER));
        assertEquals("~/lib/ is already in this file", e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> SettingsLibraries.withLibrary(document, "~/lib/", "/x", false, RESOLVER));
    }

    @Test
    void invalidShapesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SettingsLibraries.libraries("{\"libraries\": []}", Origin.GLOBAL));
        assertThrows(IllegalArgumentException.class, () -> SettingsLibraries.withLibrary("[]", "~/a", "/a", false, RESOLVER));
        assertThrows(IllegalArgumentException.class, () -> SettingsLibraries.withLibrary("{", "~/a", "/a", false, RESOLVER));
        assertEquals(List.of(), SettingsLibraries.libraries("{\"libraries\": null}", Origin.GLOBAL));
    }

    @Test
    void removalTakesTheLiteralKeyOnly() {
        final String document = "{\"libraries\": {\"~/a\": \"/libs/a\", \"/home/user/a\": \"/libs/a2\"}}";
        assertEquals(Optional.of("""
                {
                  "libraries": {
                    "/home/user/a": "/libs/a2"
                  }
                }
                """),
            SettingsLibraries.withoutLibrary(document, "~/a"));
        assertEquals(Optional.empty(), SettingsLibraries.withoutLibrary(document, "~/missing"));
        assertEquals(Optional.empty(), SettingsLibraries.withoutLibrary("{}", "~/a"));
    }

    @Test
    void changeKeepsTheShorthandUntilItTurnsReadWrite() {
        final String document = "{\"libraries\": {\"~/a\": \"/libs/a\"}}";
        assertEquals(Optional.of("{\n  \"libraries\": {\n    \"~/a\": \"/libs/x\"\n  }\n}\n"),
            SettingsLibraries.withChangedLibrary(document, "~/a", "/libs/x", false));
        assertEquals(Optional.of("{\n  \"libraries\": {\n    \"~/a\": {\n      \"path\": \"/libs/a\",\n      \"readwrite\": true\n    }\n  }\n}\n"),
            SettingsLibraries.withChangedLibrary(document, "~/a", "/libs/a", true));
        assertEquals(Optional.empty(), SettingsLibraries.withChangedLibrary(document, "~/a", "/libs/a", false));
    }

    @Test
    void changeOfAnObjectWritesBothFieldsAndKeepsOthers() {
        final String document = "{\"libraries\": {\"~/a\": {\"readwrite\": true, \"path\": \"/libs/a\"}, \"~/b\": {\"path\": \"/libs/b\"}}}";
        assertEquals(Optional.of("""
                {
                  "libraries": {
                    "~/a": {
                      "readwrite": false,
                      "path": "/libs/a"
                    },
                    "~/b": {
                      "path": "/libs/b"
                    }
                  }
                }
                """),
            SettingsLibraries.withChangedLibrary(document, "~/a", "/libs/a", false));
        // an object without `readwrite` gets an explicit false: in a project file it would otherwise keep a global true
        assertTrue(SettingsLibraries.withChangedLibrary(document, "~/b", "/libs/b", false).orElseThrow()
            .contains("\"path\": \"/libs/b\",\n      \"readwrite\": false"));
        assertThrows(IllegalArgumentException.class, () -> SettingsLibraries.withChangedLibrary(document, "~/gone", "/x", false));
    }

    @Test
    void mergeFollowsHolePerLiteralKey() {
        final String global = """
            {"libraries": {"~/a": "/libs/a", "~/b": {"path": "/libs/b", "readwrite": true}, "~/c": {"path": "/libs/c", "readwrite": true},
             "~/d": "/libs/d", "/home/user/e": "/libs/e"}}""";
        final String project = """
            {"libraries": {"~/n": "/libs/n", "~/b": {"path": "/libs/b2"}, "~/c": "/libs/c2", "~/d": {"readwrite": true},
             "~/e": "/libs/e2"}}""";
        assertEquals(List.of(
            new Library("~/a", "/libs/a", false, true, Origin.GLOBAL),
            // two objects merge: the path is the project's, readwrite stays the global one
            new Library("~/b", "/libs/b2", true, true, Origin.PROJECT_OVERRIDES_GLOBAL),
            // a string replaces the object whole
            new Library("~/c", "/libs/c2", false, true, Origin.PROJECT_OVERRIDES_GLOBAL),
            // an object replaces a string whole and has no path then
            new Library("~/d", "", true, false, Origin.PROJECT_OVERRIDES_GLOBAL),
            new Library("/home/user/e", "/libs/e", false, true, Origin.GLOBAL),
            new Library("~/n", "/libs/n", false, true, Origin.PROJECT),
            // another literal key for the same directory is another entry
            new Library("~/e", "/libs/e2", false, true, Origin.PROJECT)
        ), SettingsLibraries.merged(global, project));
        assertEquals(List.of(new Library("~/n", "/libs/n", false, true, Origin.PROJECT)),
            SettingsLibraries.merged("", "{\"libraries\": {\"~/n\": \"/libs/n\"}}"));
    }

    @Test
    void hostPathsResolveLikeHole() {
        assertEquals("/home/user", RESOLVER.resolve("~"));
        assertEquals("/home/user/lib", RESOLVER.resolve("~/lib/"));
        assertEquals("/home/user/lib", RESOLVER.resolve("/home/user/./lib"));
        assertEquals("/home/user/project/vendor/lib", RESOLVER.resolve("vendor/lib"));
        assertEquals("/home/user/lib", RESOLVER.resolve("../lib"));
        assertEquals("/", RESOLVER.resolve("/"));
    }

    @Test
    void mountPathMustBeAbsoluteInTheSandboxHomeOrStartWithAVariable() {
        assertEquals(Optional.empty(), SettingsLibraries.mountPathProblem("/libs/a"));
        assertEquals(Optional.empty(), SettingsLibraries.mountPathProblem("~/a"));
        assertEquals(Optional.empty(), SettingsLibraries.mountPathProblem("$HOME/lib-test"));
        assertEquals(Optional.empty(), SettingsLibraries.mountPathProblem("${HOME}/lib-test"));
        assertFalse(SettingsLibraries.mountPathProblem("libs/a").isEmpty());
        assertFalse(SettingsLibraries.mountPathProblem(" ").isEmpty());
    }
}
