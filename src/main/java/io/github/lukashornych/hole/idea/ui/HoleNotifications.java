package io.github.lukashornych.hole.idea.ui;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.session.StartFailureNotifier;
import org.jetbrains.annotations.NotNull;

/**
 * Balloon notifications of the plugin (group {@value #GROUP_ID}, registered in `plugin.xml`).
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

    @Override
    public void spawnFailed(@NotNull String reason) {
        notify("Hole sandbox not started", "The Hole process could not be started: " + reason, NotificationType.ERROR);
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
