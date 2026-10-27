package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxFormValidatorTest {

    @Test
    void homeIsExpandedOtherPathsStayAsTyped() {
        assertEquals("/home/me", SandboxFormValidator.normalizeProjectPath(" ~ ", "/home/me"));
        assertEquals("/home/me/work/x", SandboxFormValidator.normalizeProjectPath("~/work/x", "/home/me"));
        assertEquals(".", SandboxFormValidator.normalizeProjectPath(" . ", "/home/me"));
        assertEquals("../other", SandboxFormValidator.normalizeProjectPath("../other", "/home/me"));
        assertEquals("~bob/x", SandboxFormValidator.normalizeProjectPath("~bob/x", "/home/me"), "left for the validator to reject");
    }

    @Test
    void defaultsAreValid() {
        assertTrue(SandboxFormValidator.validate(SandboxConfig.defaults(), null).isEmpty());
    }

    @Test
    void agentAndProfileNames() {
        assertEquals(List.of(SandboxFormValidator.Field.AGENT), fields(SandboxConfig.defaults().withAgent(" "), null));
        assertEquals(List.of(SandboxFormValidator.Field.AGENT), fields(SandboxConfig.defaults().withAgent("Claude"), null));
        assertEquals(List.of(SandboxFormValidator.Field.PROFILE), fields(SandboxConfig.defaults().withProfile("-x"), null));
        assertTrue(fields(SandboxConfig.defaults().withAgent("my-agent").withProfile("research2"), null).isEmpty());
    }

    @Test
    void projectPathChecksApplyOnlyToTheCheckedValue() {
        final SandboxConfig config = SandboxConfig.defaults().withProjectPath("/missing");
        final FormChecks missing = checks("/missing", Path.of("/missing"), false, Set.of());
        assertEquals(List.of(SandboxFormValidator.Field.PROJECT_PATH), fields(config, missing));
        assertTrue(fields(config.withProjectPath("/other"), missing).isEmpty(), "outdated checks are ignored");
        assertEquals(List.of(SandboxFormValidator.Field.PROJECT_PATH), fields(config.withProjectPath("~bob/x"), null), "~user is rejected");
    }

    @Test
    void missingLibrariesPointToTheirRow() {
        final SandboxConfig config = SandboxConfig.defaults().withLibraries(List.of(
            new Library("/ok", false), new Library("", true), new Library(" /gone ", true)
        ));
        final List<SandboxFormValidator.Problem> problems = SandboxFormValidator.validate(
            config, checks(".", Path.of("/p"), true, Set.of("/gone"))
        );
        assertEquals(1, problems.size());
        assertEquals(SandboxFormValidator.Field.LIBRARY, problems.get(0).field());
        assertEquals(2, problems.get(0).index());
    }

    private static List<SandboxFormValidator.Field> fields(SandboxConfig config, FormChecks checks) {
        return SandboxFormValidator.validate(config, checks).stream().map(SandboxFormValidator.Problem::field).toList();
    }

    private static FormChecks checks(String path, Path directory, boolean exists, Set<String> missing) {
        return new FormChecks(path, directory, exists, Path.of("/home/.hole/settings.json"), true, null, missing, List.of(), List.of());
    }
}
