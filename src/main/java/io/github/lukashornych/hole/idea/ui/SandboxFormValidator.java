package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleSettingsLocator;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Validation and normalisation of the sandbox form, free of Swing.
 */
public final class SandboxFormValidator {

    private SandboxFormValidator() {
    }

    /**
     * A form field.
     */
    public enum Field { AGENT, PROFILE, PROJECT_PATH, LIBRARY }

    /**
     * One validation problem.
     *
     * @param field   the field
     * @param index   row of a library, -1 otherwise
     * @param message message for the user
     */
    public record Problem(@NotNull Field field, int index, @NotNull String message) {
    }

    /**
     * Normalises a typed project path: trims it and expands `~` and `~/…` against {@code home}, because Hole receives
     * the path without a shell and does not expand it. Other values stay as typed (`.` stays `.`).
     */
    @NotNull
    public static String normalizeProjectPath(@NotNull String typed, @NotNull String home) {
        final String value = typed.trim();
        if (value.equals("~")) {
            return home;
        }
        if (value.startsWith("~/")) {
            return home + value.substring(1);
        }
        return value;
    }

    /**
     * Validates a (normalised) configuration.
     *
     * @param config the form values
     * @param checks file system facts for these values, {@code null} or outdated while they are being gathered (path
     *               checks are skipped then)
     */
    @NotNull
    public static List<Problem> validate(@NotNull SandboxConfig config, @Nullable FormChecks checks) {
        final List<Problem> problems = new ArrayList<>();
        final String agent = config.agent().trim();
        if (agent.isEmpty()) {
            problems.add(new Problem(Field.AGENT, -1, "Choose an agent."));
        } else if (!HoleSettingsLocator.NAME_PATTERN.matcher(agent).matches()) {
            problems.add(new Problem(Field.AGENT, -1, "Agent names consist of a-z, 0-9 and '-'."));
        }
        final String profile = config.profile().trim();
        if (!profile.isEmpty() && !HoleSettingsLocator.NAME_PATTERN.matcher(profile).matches()) {
            problems.add(new Problem(Field.PROFILE, -1, "Profile names consist of a-z, 0-9 and '-' and start with a letter or digit."));
        }
        final String path = config.projectPath().trim();
        final boolean current = checks != null && checks.projectPath().equals(config.projectPath());
        if (path.startsWith("~")) {
            problems.add(new Problem(Field.PROJECT_PATH, -1, "Only ~ and ~/… are expanded. Use an absolute path."));
        } else if (current && checks.projectDirectory() == null) {
            problems.add(new Problem(Field.PROJECT_PATH, -1, "Not a valid path."));
        } else if (current && !checks.projectDirectoryExists()) {
            problems.add(new Problem(Field.PROJECT_PATH, -1, "Directory does not exist."));
        }
        if (checks != null) {
            final List<Library> libraries = config.libraries();
            for (int i = 0; i < libraries.size(); i++) {
                final Library library = libraries.get(i);
                if (!library.isBlank() && checks.missingLibraries().contains(library.path().trim())) {
                    problems.add(new Problem(Field.LIBRARY, i, "Path does not exist."));
                }
            }
        }
        return problems;
    }
}
