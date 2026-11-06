package io.github.lukashornych.hole.idea.ui.library;

import com.intellij.openapi.application.ModalityState;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.ui.settings.SettingsDocuments;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The top-level `libraries` of the sandbox's global and project settings files: read both as Hole merges them, add,
 * change and remove a library in one of them ({@link SettingsLibraries} edits the JSON, {@link SettingsDocuments} the
 * file). Blocking; call off the EDT.
 */
final class LibraryFiles {

    private final SettingsDocuments documents;
    private final SettingsLibraries.HostResolver resolver;

    /**
     * @param documents the sandbox's settings files
     * @param home      the user's home directory (host paths with {@code ~})
     */
    LibraryFiles(@NotNull SettingsDocuments documents, @NotNull Path home) {
        this.documents = documents;
        this.resolver = new SettingsLibraries.HostResolver(home, documents.projectDirectory());
    }

    /**
     * Returns the resolver of host paths of this sandbox.
     */
    @NotNull
    SettingsLibraries.HostResolver resolver() {
        return this.resolver;
    }

    /**
     * The libraries of both files.
     *
     * @param libraries merged as by Hole
     * @param problems  files that could not be read (they count as empty), e.g. not valid JSON
     */
    record Loaded(@NotNull List<SettingsLibraries.Library> libraries, @NotNull List<String> problems) {
    }

    /**
     * Reads both files. A file Hole names no existing file for counts as empty.
     */
    @NotNull
    Loaded load() {
        final List<String> problems = new ArrayList<>();
        final String global = readable(HoleSettingsClient.Scope.GLOBAL, problems);
        final String project = readable(HoleSettingsClient.Scope.PROJECT, problems);
        return new Loaded(SettingsLibraries.merged(global, project), List.copyOf(problems));
    }

    @NotNull
    private String readable(@NotNull HoleSettingsClient.Scope scope, @NotNull List<String> problems) {
        final String text = this.documents.read(scope).orElse("");
        try {
            SettingsLibraries.libraries(text, SettingsLibraries.Origin.GLOBAL);
            return text;
        } catch (IllegalArgumentException e) {
            problems.add("The " + scope.label() + " settings file was not read: " + e.getMessage());
            return "";
        }
    }

    /**
     * Adds a library to a settings file, creating the file when missing.
     *
     * @param modality modality of the window that asked (the edit runs on the EDT in it)
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    Optional<String> add(
        @NotNull HoleSettingsClient.Scope scope, @NotNull String hostPath, @NotNull String mountPath, boolean readWrite,
        @NotNull ModalityState modality
    ) {
        // Hole expands `$VAR` in its own environment first; such a path is left for Hole to check
        if (!hostPath.contains("$") && !Files.isDirectory(Path.of(this.resolver.resolve(hostPath)))) {
            return Optional.of(hostPath + " is not a directory");
        }
        return this.documents.edit(scope, true, modality,
            text -> Optional.of(SettingsLibraries.withLibrary(text, hostPath, mountPath, readWrite, this.resolver)));
    }

    /**
     * Changes the mount path and access of a library in a settings file.
     *
     * @param hostPath the literal key
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    Optional<String> change(
        @NotNull HoleSettingsClient.Scope scope, @NotNull String hostPath, @NotNull String mountPath, boolean readWrite,
        @NotNull ModalityState modality
    ) {
        return this.documents.edit(scope, false, modality,
            text -> SettingsLibraries.withChangedLibrary(text, hostPath, mountPath, readWrite));
    }

    /**
     * Removes a library from a settings file; a missing file changes nothing.
     *
     * @param hostPath the literal key
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    Optional<String> remove(@NotNull HoleSettingsClient.Scope scope, @NotNull String hostPath, @NotNull ModalityState modality) {
        return this.documents.edit(scope, false, modality, text -> SettingsLibraries.withoutLibrary(text, hostPath));
    }
}
