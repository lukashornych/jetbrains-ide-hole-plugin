package io.github.lukashornych.hole.idea.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopupStepEx;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.util.ui.StatusText;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Items of the favourites dropdown. The modifiers of the choosing event decide the mode: with Shift the favourite
 * starts in Debug Shell.
 */
final class FavouritesPopupStep extends BaseListPopupStep<FavouritesPopupStep.Item> implements ListPopupStepEx<FavouritesPopupStep.Item> {

    private final Project project;
    private final HoleCapability capability;

    FavouritesPopupStep(@NotNull Project project, @NotNull List<Favourite> favourites, @Nullable HoleCapability capability) {
        super(null, items(favourites));
        this.project = project;
        this.capability = capability;
    }

    @NotNull
    static List<Item> items(@NotNull List<Favourite> favourites) {
        final List<Item> items = new ArrayList<>();
        favourites.stream().filter(f -> f.scope() == FavouriteScope.PROJECT).forEach(f -> items.add(new FavouriteItem(f)));
        favourites.stream().filter(f -> f.scope() == FavouriteScope.GLOBAL).forEach(f -> items.add(new FavouriteItem(f)));
        items.add(new CommandItem(Command.NEW_SANDBOX));
        items.add(new CommandItem(Command.EDIT_FAVOURITES));
        return List.copyOf(items);
    }

    @Override
    public @NotNull String getTextFor(Item value) {
        return switch (value) {
            case FavouriteItem favourite -> favourite.favourite().name();
            case CommandItem command -> command.command() == Command.NEW_SANDBOX ? "New Sandbox…" : "Edit Favourites…";
        };
    }

    @Override
    public @Nullable String getSecondaryTextFor(Item value) {
        return value instanceof FavouriteItem favourite
            ? favourite.favourite().config().agentSpec() + " " + favourite.favourite().config().projectPath()
            : null;
    }

    @Override
    public Icon getIconFor(Item value) {
        return value instanceof CommandItem command && command.command() == Command.NEW_SANDBOX ? AllIcons.General.Add : null;
    }

    @Override
    public @Nullable ListSeparator getSeparatorAbove(Item value) {
        final List<Item> values = getValues();
        final int index = values.indexOf(value);
        final Item previous = index > 0 ? values.get(index - 1) : null;
        if (value instanceof FavouriteItem favourite) {
            final FavouriteScope scope = favourite.favourite().scope();
            if (!(previous instanceof FavouriteItem prev) || prev.favourite().scope() != scope) {
                return new ListSeparator(scope == FavouriteScope.PROJECT ? "This project" : "All projects");
            }
            return null;
        }
        return previous instanceof FavouriteItem ? new ListSeparator() : null;
    }

    @Override
    public boolean isSelectable(Item value) {
        return !(value instanceof FavouriteItem) || this.capability == null || this.capability.canStart();
    }

    @Override
    public @Nullable String getTooltipTextFor(Item value) {
        if (value instanceof FavouriteItem && this.capability != null && !this.capability.canStart()) {
            return this.capability.message();
        }
        return null;
    }

    @Override
    public void setEmptyText(@NotNull StatusText emptyText) {
    }

    @Override
    public @Nullable PopupStep<?> onChosen(Item selectedValue, boolean finalChoice, @Nullable InputEvent inputEvent) {
        final boolean debug = debugRequested(inputEvent);
        return doFinalStep(() -> run(selectedValue, debug));
    }

    /**
     * Returns {@code true} when the choosing event asks for a Debug Shell start: Shift+click or Shift+Enter.
     *
     * @param inputEvent the mouse or key event that chose the item, {@code null} when unknown
     */
    static boolean debugRequested(@Nullable InputEvent inputEvent) {
        return inputEvent != null && inputEvent.isShiftDown();
    }

    @Override
    public @Nullable PopupStep<?> onChosen(Item selectedValue, boolean finalChoice) {
        return doFinalStep(() -> run(selectedValue, false));
    }

    private void run(@NotNull Item item, boolean debug) {
        switch (item) {
            case FavouriteItem favourite -> SandboxStarter.start(this.project, favourite.favourite().config(), debug, null);
            case CommandItem command -> {
                if (command.command() == Command.NEW_SANDBOX) {
                    NewSandboxAction.showDialog(this.project);
                } else {
                    new EditFavouritesDialog(this.project, null).show();
                }
            }
        }
    }

    /**
     * One row of the dropdown.
     */
    sealed interface Item permits FavouriteItem, CommandItem {
    }

    /**
     * A favourite to start.
     */
    record FavouriteItem(@NotNull Favourite favourite) implements Item {
    }

    /**
     * New Sandbox… or Edit Favourites….
     */
    record CommandItem(@NotNull Command command) implements Item {
    }

    /**
     * The fixed entries.
     */
    enum Command { NEW_SANDBOX, EDIT_FAVOURITES }
}
