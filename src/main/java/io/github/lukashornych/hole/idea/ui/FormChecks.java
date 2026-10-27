package io.github.lukashornych.hole.idea.ui;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsLocator;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * File system facts about the values of a sandbox form, gathered off the EDT (debounced) and then used for the
 * settings links, the agent and profile suggestions and the validation. Relative paths are resolved against the IDE
 * project root only here; the command keeps them as typed. The settings files are located by Hole itself
 * (`hole settings get`, see {@link HoleSettingsClient}).
 *
 * @param projectPath            the project path the checks were made for (as in the config)
 * @param projectDirectory       the resolved project directory, {@code null} when the value is not a valid path
 * @param projectDirectoryExists the resolved project path is a directory
 * @param globalSettings         path of the global settings file, {@code null} when Hole could not tell (e.g. Hole
 *                               not found)
 * @param globalSettingsExists   the global settings file exists
 * @param projectSettings        the project settings file of the resolved project directory (existing or not),
 *                               {@code null} when Hole could not tell or the project path is not a directory
 * @param missingLibraries       library paths (as typed) that do not exist
 * @param agents                 agent suggestions
 * @param profiles               profile suggestions
 */
public record FormChecks(
    @NotNull String projectPath,
    @Nullable Path projectDirectory,
    boolean projectDirectoryExists,
    @Nullable Path globalSettings,
    boolean globalSettingsExists,
    @Nullable HoleSettingsClient.SettingsFile projectSettings,
    @NotNull Set<String> missingLibraries,
    @NotNull List<String> agents,
    @NotNull List<String> profiles
) {

    public FormChecks {
        missingLibraries = Set.copyOf(missingLibraries);
        agents = List.copyOf(agents);
        profiles = List.copyOf(profiles);
    }

    /**
     * Runs the checks. Blocking file access and `hole settings get` calls; call off the EDT.
     *
     * @param locator     resolves paths and reads suggestions from settings files
     * @param settings    asks Hole for the settings files, {@code null} when Hole was not found (no links then)
     * @param projectRoot the IDE project root
     * @param config      the form values
     */
    @NotNull
    public static FormChecks run(
        @NotNull HoleSettingsLocator locator,
        @Nullable HoleSettingsClient settings,
        @NotNull Path projectRoot,
        @NotNull SandboxConfig config
    ) {
        final Optional<Path> directory = locator.resolveProjectDirectory(projectRoot, config.projectPath());
        final boolean exists = directory.map(Files::isDirectory).orElse(false);
        // suggestions fall back to the IDE project root while the typed path is not a directory
        final Path settingsBase = exists ? directory.get() : projectRoot;
        final Optional<HoleSettingsClient.SettingsFile> global = settings == null ? Optional.empty() : settings.global();
        final Optional<HoleSettingsClient.SettingsFile> project = settings == null ? Optional.empty() : settings.project(settingsBase);
        final List<Path> existingFiles = new ArrayList<>(2);
        global.filter(HoleSettingsClient.SettingsFile::exists).ifPresent(file -> existingFiles.add(file.path()));
        project.filter(HoleSettingsClient.SettingsFile::exists).ifPresent(file -> existingFiles.add(file.path()));
        final Set<String> missing = new LinkedHashSet<>();
        for (Library library : config.libraries()) {
            if (library.isBlank()) {
                continue;
            }
            final String path = library.path().trim();
            final Optional<Path> resolved = locator.resolveProjectDirectory(projectRoot, path);
            if (resolved.isEmpty() || !Files.exists(resolved.get())) {
                missing.add(path);
            }
        }
        return new FormChecks(
            config.projectPath(),
            directory.orElse(null),
            exists,
            global.map(HoleSettingsClient.SettingsFile::path).orElse(null),
            global.map(HoleSettingsClient.SettingsFile::exists).orElse(false),
            exists ? project.orElse(null) : null,
            missing,
            locator.agentNames(existingFiles),
            locator.profileNames(existingFiles)
        );
    }
}
