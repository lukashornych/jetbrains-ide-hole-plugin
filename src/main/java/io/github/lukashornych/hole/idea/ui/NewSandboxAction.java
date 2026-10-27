package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/**
 * The {@code +} tab action: opens <i>New Sandbox</i> and starts what the user chose.
 */
final class NewSandboxAction extends DumbAwareAction {

    NewSandboxAction() {
        super("New Sandbox…", "Start a new Hole sandbox", AllIcons.General.Add);
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

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        final Project project = e.getProject();
        if (project != null) {
            showDialog(project);
        }
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabled(e.getProject() != null);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
