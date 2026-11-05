package io.github.lukashornych.hole.idea.ui;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageDialogBuilder;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.session.StartFailureNotifier;
import org.jetbrains.annotations.NotNull;

/**
 * Balloon notifications of the plugin (group {@value #GROUP_ID}, registered in `plugin.xml`), and the dialog for an
 * unreadable shell environment.
 */
public final class HoleNotifications implements StartFailureNotifier {

    /** Notification group ID. */
    public static final String GROUP_ID = "Hole";

    private final Project project;

    /**
     * @param project project the notifications belong to
     */
    public HoleNotifications(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public void cannotStart(@NotNull HoleCapability capability) {
        final String title = switch (capability.status()) {
            case NOT_FOUND -> "Hole not found";
            case UNSUPPORTED -> "Hole update needed";
            default -> "Hole sandbox not started";
        };
        notify(title, capability.message(), NotificationType.WARNING);
    }

    /**
     * Asks on the EDT whether to retry; a dialog rather than a balloon, because the user is waiting for the start.
     * Non-modal context is enough: every start runs after the New Sandbox dialog has closed.
     */
    @Override
    public void environmentUnavailable(@NotNull String shell, @NotNull String reason, @NotNull Runnable retry) {
        ApplicationManager.getApplication().invokeLater(() -> {
            final boolean again = MessageDialogBuilder.yesNo(
                    "Hole sandbox not started",
                    "Could not read the environment of your shell (" + shell + ": " + reason + "). "
                        + "Variables from your shell profile would be missing in the sandbox."
                )
                .yesText("Retry")
                .noText("Cancel")
                .asWarning()
                .ask(this.project);
            if (again) {
                retry.run();
            }
        }, this.project.getDisposed());
    }

    @Override
    public void spawnFailed(@NotNull String reason) {
        notify("Hole sandbox not started", "The Hole process could not be started: " + reason, NotificationType.ERROR);
    }

    @Override
    public void restartFailed(@NotNull String reason) {
        notify("Hole sandbox could not be restarted",
            "The old sandbox is gone, and the new Hole process could not be started: " + reason, NotificationType.ERROR);
    }

    /**
     * A settings file from the sandbox header could not be opened or created.
     *
     * @param scope   `global` or `project`
     * @param message Hole's message
     */
    public void settingsFileFailed(@NotNull String scope, @NotNull String message) {
        notify("Hole " + scope + " settings not opened", message.isBlank() ? "Hole gave no reason." : message,
            NotificationType.WARNING);
    }

    /**
     * An image could not be pasted into a sandbox terminal (or the sandbox has no image paste).
     *
     * @param reason why, for the user
     */
    public void imagePasteFailed(@NotNull String reason) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification("Image not pasted", reason, NotificationType.WARNING)
            .notify(this.project);
    }

    private void notify(@NotNull String title, @NotNull String message, @NotNull NotificationType type) {
        final Notification notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, message, type);
        notification.addAction(NotificationAction.createSimpleExpiring(
            "Open Settings",
            () -> ShowSettingsUtil.getInstance().showSettingsDialog(this.project, HoleSettingsConfigurable.class)
        ));
        notification.notify(this.project);
    }
}
