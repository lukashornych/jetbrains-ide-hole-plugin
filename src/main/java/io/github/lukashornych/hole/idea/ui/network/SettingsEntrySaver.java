package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsEntries;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Reads and changes the entries of the Network window in the global and project settings files of the sandbox: Hole
 * names the files (`hole settings get`; for a save a missing one is created with `hole settings create` without
 * asking — the user chose to save there), and {@link SettingsEntries} reads or edits the document. Files are read and
 * changed through their IDE documents, so unsaved edits in an open editor count and are kept, and a change can be
 * undone. Blocking; call off the EDT.
 */
public final class SettingsEntrySaver {

    private static final Logger LOG = Logger.getInstance(SettingsEntrySaver.class);

    private final Project project;
    private final SettingsFileResolver resolver;
    private final Path projectDirectory;

    /**
     * @param project          the IDE project (undo, write command)
     * @param resolver         `hole settings get|create`
     * @param projectDirectory the sandbox's absolute project directory (for the project settings file)
     */
    public SettingsEntrySaver(@NotNull Project project, @NotNull SettingsFileResolver resolver, @NotNull Path projectDirectory) {
        this.project = project;
        this.resolver = resolver;
        this.projectDirectory = projectDirectory;
    }

    /**
     * Returns the canonical top-level entries of the setting per existing settings file. A file Hole cannot name or
     * that cannot be read counts as empty.
     */
    @NotNull
    Map<HoleSettingsClient.Scope, Set<String>> savedEntries(@NotNull NetworkSetting setting) {
        final Map<HoleSettingsClient.Scope, Set<String>> saved = new EnumMap<>(HoleSettingsClient.Scope.class);
        for (HoleSettingsClient.Scope scope : HoleSettingsClient.Scope.values()) {
            final SettingsFileResolver.Result result = this.resolver.resolve(scope, this.projectDirectory, path -> false);
            final VirtualFile file = result.openable() && result.path() != null
                ? LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.path())
                : null;
            if (file == null) {
                saved.put(scope, Set.of());
                continue;
            }
            try {
                final String text = ReadAction.compute(() -> {
                    final Document document = FileDocumentManager.getInstance().getDocument(file);
                    return document == null ? "" : document.getText();
                });
                saved.put(scope, SettingsEntries.entries(text, setting));
            } catch (IllegalArgumentException e) {
                LOG.info("The " + scope.label() + " settings file was not read for the Network window: " + e.getMessage());
                saved.put(scope, Set.of());
            }
        }
        return saved;
    }

    /**
     * Saves the values to a settings file, creating it when missing.
     *
     * @param modality modality of the window that asked (the edit runs on the EDT in it)
     * @return the reason it failed, or nothing on success (also when the values were there already)
     */
    @NotNull
    Optional<String> save(
        @NotNull HoleSettingsClient.Scope scope, @NotNull NetworkSetting setting, @NotNull List<String> values,
        @NotNull ModalityState modality
    ) {
        final SettingsFileResolver.Result result = this.resolver.resolve(scope, this.projectDirectory, path -> true);
        return edit(scope, result, modality, text -> SettingsEntries.withEntries(text, setting, values));
    }

    /**
     * Removes every value matching the entry from a settings file; a missing file changes nothing.
     *
     * @param modality modality of the window that asked (the edit runs on the EDT in it)
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    Optional<String> remove(
        @NotNull HoleSettingsClient.Scope scope, @NotNull NetworkSetting setting, @NotNull String entry,
        @NotNull ModalityState modality
    ) {
        final SettingsFileResolver.Result result = this.resolver.resolve(scope, this.projectDirectory, path -> false);
        if (result.outcome() == SettingsFileResolver.Outcome.CANCELLED) {
            return Optional.empty();
        }
        return edit(scope, result, modality, text -> SettingsEntries.withoutEntry(text, setting, entry));
    }

    @NotNull
    private Optional<String> edit(
        @NotNull HoleSettingsClient.Scope scope,
        @NotNull SettingsFileResolver.Result result,
        @NotNull ModalityState modality,
        @NotNull Function<String, Optional<String>> change
    ) {
        final Path path = result.path();
        if (!result.openable() || path == null) {
            return Optional.of(result.message().isEmpty() ? "Hole did not name the " + scope.label() + " settings file" : result.message());
        }
        final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
        if (file == null) {
            return Optional.of(path + " was not found");
        }
        final AtomicReference<String> failure = new AtomicReference<>();
        ApplicationManager.getApplication().invokeAndWait(() -> failure.set(write(file, change)), modality);
        return Optional.ofNullable(failure.get());
    }

    /**
     * Changes the file's document and saves it. EDT only.
     *
     * @return the reason it failed, or {@code null}
     */
    @Nullable
    String write(@NotNull VirtualFile file, @NotNull Function<String, Optional<String>> change) {
        final Document document = FileDocumentManager.getInstance().getDocument(file);
        if (document == null) {
            return file.getPresentableUrl() + " cannot be edited";
        }
        final Optional<String> text;
        try {
            text = change.apply(document.getText());
        } catch (IllegalArgumentException e) {
            return file.getPresentableUrl() + " was not changed: " + e.getMessage();
        }
        text.ifPresent(changed -> {
            WriteCommandAction.runWriteCommandAction(this.project, "Change Hole Settings", null, () -> document.setText(changed));
            FileDocumentManager.getInstance().saveDocument(document);
        });
        return null;
    }
}
