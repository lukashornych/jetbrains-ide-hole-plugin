package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Opens a Hole settings file from the sandbox header in an IDE editor: `hole settings get`, and for a missing file a
 * confirmation and `hole settings create` (see {@link SettingsFileResolver}). Runs in a background task; other
 * failures (e.g. a relative `HOLE_SETTINGS_FILE`) end in a balloon with Hole's message.
 */
final class SettingsFileOpener {

    private SettingsFileOpener() {
    }

    /**
     * Opens (and, after a confirmation, creates) a settings file.
     *
     * @param project          the IDE project (editor, notifications)
     * @param parent           parent of the confirmation
     * @param scope            which file
     * @param projectDirectory the sandbox's absolute project directory for {@link HoleSettingsClient.Scope#PROJECT}
     */
    static void open(
        @NotNull Project project,
        @NotNull JComponent parent,
        @NotNull HoleSettingsClient.Scope scope,
        @Nullable Path projectDirectory
    ) {
        new Task.Backgroundable(project, "Opening Hole " + scope.label() + " settings", false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                final HoleExecutable hole = HoleExecutable.getInstance();
                final SettingsFileResolver.Result result = hole.locate()
                    .map(executable -> new SettingsFileResolver(new HoleSettingsClient(executable.toString(), hole.runner())))
                    .map(resolver -> resolver.resolve(scope, projectDirectory, path -> confirmCreate(parent, scope, path)))
                    .orElseGet(() -> new SettingsFileResolver.Result(SettingsFileResolver.Outcome.FAILED, null, "Hole was not found"));
                switch (result.outcome()) {
                    case EXISTS, CREATED -> openInEditor(project, result.path());
                    case FAILED -> new HoleNotifications(project).settingsFileFailed(scope.label(), result.message());
                    case CANCELLED -> {
                        // the user did not want the file
                    }
                }
            }
        }.queue();
    }

    private static boolean confirmCreate(@NotNull JComponent parent, @NotNull HoleSettingsClient.Scope scope, @NotNull Path file) {
        final AtomicBoolean answer = new AtomicBoolean();
        ApplicationManager.getApplication().invokeAndWait(() -> answer.set(
            parent.isShowing() && MessageDialogBuilder.yesNo(
                    "Create Hole Settings File",
                    "The " + scope.label() + " settings file " + file + " does not exist. Create it from Hole's template?")
                .yesText("Create")
                .noText("Cancel")
                .ask(parent)
        ), ModalityState.defaultModalityState());
        return answer.get();
    }

    private static void openInEditor(@NotNull Project project, @Nullable Path file) {
        if (file == null) {
            return;
        }
        final VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
        if (virtualFile == null) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!project.isDisposed()) {
                final OpenFileDescriptor descriptor = new OpenFileDescriptor(project, virtualFile);
                if (descriptor.canNavigate()) {
                    descriptor.navigate(true);
                }
            }
        }, ModalityState.nonModal());
    }
}
