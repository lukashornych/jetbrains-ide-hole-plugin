package io.github.lukashornych.hole.idea.ui.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Reads and changes the global and project settings files of one sandbox for the windows that edit a curated setting
 * (Network, Libraries). Hole names the files (`hole settings get`; for an edit that adds something, a missing file is
 * created with `hole settings create` without asking — the user chose to save there). Files are read and changed
 * through their IDE documents, so unsaved edits in an open editor count and are kept, and a change can be undone.
 * Blocking; call off the EDT.
 */
public final class SettingsDocuments {

    private final Project project;
    private final SettingsFileResolver resolver;
    private final Path projectDirectory;

    /**
     * @param project          the IDE project (undo, write command)
     * @param resolver         `hole settings get|create`
     * @param projectDirectory the sandbox's absolute project directory (for the project settings file)
     */
    public SettingsDocuments(@NotNull Project project, @NotNull SettingsFileResolver resolver, @NotNull Path projectDirectory) {
        this.project = project;
        this.resolver = resolver;
        this.projectDirectory = projectDirectory;
    }

    /**
     * Returns the sandbox's project directory, against which Hole resolves relative paths of the project file.
     */
    @NotNull
    public Path projectDirectory() {
        return this.projectDirectory;
    }

    /**
     * Returns the text of the settings file, or nothing when Hole names no existing file. Never creates one.
     */
    @NotNull
    public Optional<String> read(@NotNull HoleSettingsClient.Scope scope) {
        final SettingsFileResolver.Result result = this.resolver.resolve(scope, this.projectDirectory, path -> false);
        final VirtualFile file = result.openable() && result.path() != null
            ? LocalFileSystem.getInstance().refreshAndFindFileByNioFile(result.path())
            : null;
        if (file == null) {
            return Optional.empty();
        }
        return Optional.of(ReadAction.compute(() -> {
            final Document document = FileDocumentManager.getInstance().getDocument(file);
            return document == null ? "" : document.getText();
        }));
    }

    /**
     * Changes a settings file.
     *
     * @param create   create a missing file first; otherwise a missing file changes nothing
     * @param modality modality of the window that asked (the edit runs on the EDT in it; a modal input's state, or
     *                 the edit waits until the input is closed)
     * @param change   the new text for the current one, or nothing when there is nothing to change; an
     *                 {@link IllegalArgumentException} leaves the file alone and its message is the reason
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    public Optional<String> edit(
        @NotNull HoleSettingsClient.Scope scope, boolean create, @NotNull ModalityState modality,
        @NotNull Function<String, Optional<String>> change
    ) {
        final SettingsFileResolver.Result result = this.resolver.resolve(scope, this.projectDirectory, path -> create);
        if (!create && result.outcome() == SettingsFileResolver.Outcome.CANCELLED) {
            return Optional.empty();
        }
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
