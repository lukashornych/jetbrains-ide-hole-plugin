package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * Opens <i>New Sandbox</i> and starts what the user chose; serves the favourites popup and the empty start page.
 */
final class NewSandboxAction {

    private NewSandboxAction() {
    }

    /**
     * Opens the dialog and starts the result.
     */
    static void showDialog(@NotNull Project project) {
        final NewSandboxDialog dialog = new NewSandboxDialog(project);
        if (dialog.showAndGet() && dialog.result() != null) {
            final NewSandboxDialog.Result result = dialog.result();
            SandboxStarter.start(project, result.config(), result.debug(), result.instanceId());
        }
    }
}
