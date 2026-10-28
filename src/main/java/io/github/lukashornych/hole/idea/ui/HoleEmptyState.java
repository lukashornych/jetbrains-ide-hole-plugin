package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.util.ui.StatusText;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.storage.FavouritesListener;
import io.github.lukashornych.hole.idea.storage.GlobalFavouritesService;
import org.jetbrains.annotations.NotNull;

/**
 * Renders the {@link EmptyStateModel} into the tool window's empty text: <i>New Sandbox…</i>, up to five favourites
 * (a click starts one in agent mode; the status text drops key modifiers, so Debug Shell stays in the dropdown),
 * <i>More…</i> and <i>Edit Favourites…</i>. Re-rendered when favourites are saved and after the Hole probe. EDT only.
 */
final class HoleEmptyState {

    private final Project project;
    private final ToolWindow toolWindow;
    private final StatusText text;

    /**
     * @param project    the IDE project
     * @param toolWindow the Hole tool window; its disposal ends the favourites subscription
     * @param text       the tool window's empty text
     */
    HoleEmptyState(@NotNull Project project, @NotNull ToolWindow toolWindow, @NotNull StatusText text) {
        this.project = project;
        this.toolWindow = toolWindow;
        this.text = text;
        ApplicationManager.getApplication().getMessageBus().connect(toolWindow.getDisposable())
            .subscribe(FavouritesListener.TOPIC, (FavouritesListener) () -> ApplicationManager.getApplication().invokeLater(this::render));
    }

    /**
     * Shows the current favourites and the last known Hole capability.
     */
    void render() {
        if (this.project.isDisposed()) {
            return;
        }
        final EmptyStateModel model = EmptyStateModel.of(
            GlobalFavouritesService.all(this.project), HoleExecutable.getInstance().lastCapability()
        );
        this.text.clear();
        for (EmptyStateModel.Line line : model.lines()) {
            switch (line.kind()) {
                case TITLE -> this.text.setText(line.text());
                case NEW_SANDBOX -> this.text.appendLine(line.text(), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                    event -> NewSandboxAction.showDialog(this.project));
                case GAP -> this.text.appendLine("");
                case HEADER, CAPABILITY -> this.text.appendLine(line.text(), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
                case FAVOURITE -> {
                    this.text.appendLine(line.text(), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES, event -> {
                        if (line.favourite() != null) {
                            FavouriteStarter.start(this.project, line.favourite(), false,
                                popup -> {
                                    if (this.toolWindow.getComponent().isShowing()) {
                                        popup.showInCenterOf(this.toolWindow.getComponent());
                                    } else {
                                        popup.showInFocusCenter();
                                    }
                                });
                        }
                    });
                    this.text.appendText("   " + line.secondary(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                case DISABLED_FAVOURITE -> {
                    this.text.appendLine(line.text(), SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
                    this.text.appendText("   " + line.secondary(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                case EDIT_FAVOURITES -> this.text.appendLine(line.text(), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                    event -> new EditFavouritesDialog(this.project, null).show());
                case MORE_AND_EDIT -> {
                    this.text.appendLine("More…", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                        event -> FavouritesPopupAction.show(this.project, popup -> popup.showInCenterOf(this.toolWindow.getComponent())));
                    this.text.appendText("   ", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                    this.text.appendText(line.text(), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES,
                        event -> new EditFavouritesDialog(this.project, null).show());
                }
            }
        }
    }
}
