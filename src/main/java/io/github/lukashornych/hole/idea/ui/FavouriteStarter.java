package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.ListPopupStepEx;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.util.ui.StatusText;
import io.github.lukashornych.hole.idea.git.Worktree;
import io.github.lukashornych.hole.idea.git.WorktreeLister;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.process.CliException;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingConstants;
import java.awt.event.InputEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Starts a favourite (favourites dropdown, empty start page) in the project root, or in a git worktree of it: when the
 * project has linked worktrees, a popup lets the user choose, with <i>Project root</i> first and selected. The
 * worktrees are listed anew for every start, as they come and go.
 */
final class FavouriteStarter {

    private static final Logger LOG = Logger.getInstance(FavouriteStarter.class);

    private FavouriteStarter() {
    }

    /**
     * Lists the worktrees in the background, then starts or asks where. Called on the EDT.
     *
     * @param favourite what to start
     * @param debug     open a debug shell instead of the agent; decided by the click on the favourite
     * @param placement shows the location popup, e.g. underneath the component the favourite was chosen from
     */
    static void start(@NotNull Project project, @NotNull Favourite favourite, boolean debug, @NotNull Consumer<ListPopup> placement) {
        final Path projectRoot = SandboxSessionManager.getInstance(project).projectRoot();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<Worktree> worktrees;
            try {
                worktrees = new WorktreeLister(new CliRunner(CliRunner.SHELL_ENVIRONMENT)).list(projectRoot);
            } catch (CliException e) {
                // not a git repository, or git missing: the project root is the only location
                LOG.debug("No worktrees for " + projectRoot + ": " + e.getMessage());
                worktrees = List.of();
            } catch (RuntimeException e) {
                // a click must start something even when listing fails unexpectedly
                LOG.warn("Could not list worktrees for " + projectRoot, e);
                worktrees = List.of();
            }
            final List<StartLocations.Location> locations = StartLocations.of(projectRoot, worktrees);
            ApplicationManager.getApplication().invokeLater(() -> {
                if (project.isDisposed()) {
                    return;
                }
                if (locations.size() == 1) {
                    start(project, favourite, debug, locations.get(0));
                    return;
                }
                final ListPopup popup = JBPopupFactory.getInstance().createListPopup(new LocationStep(project, favourite, debug, locations));
                if (debug) {
                    popup.setAdText("Debug Shell", SwingConstants.LEFT);
                }
                placement.accept(popup);
            }, ModalityState.nonModal());
        });
    }

    private static void start(@NotNull Project project, @NotNull Favourite favourite, boolean debug, @NotNull StartLocations.Location location) {
        SandboxStarter.start(project, favourite.config().withProjectPath(location.projectPath()), debug, null);
    }

    /**
     * The location popup. Shift is not read here: the click on the favourite already decided the mode.
     */
    private static final class LocationStep extends BaseListPopupStep<StartLocations.Location>
        implements ListPopupStepEx<StartLocations.Location> {

        private final Project project;
        private final Favourite favourite;
        private final boolean debug;

        private LocationStep(
            @NotNull Project project, @NotNull Favourite favourite, boolean debug, @NotNull List<StartLocations.Location> locations
        ) {
            super("Start '" + favourite.name() + "' in", locations);
            this.project = project;
            this.favourite = favourite;
            this.debug = debug;
        }

        @Override
        public @NotNull String getTextFor(StartLocations.Location value) {
            return value.label();
        }

        @Override
        public @Nullable String getSecondaryTextFor(StartLocations.Location value) {
            return value.secondary();
        }

        @Override
        public @Nullable ListSeparator getSeparatorAbove(StartLocations.Location value) {
            return getValues().indexOf(value) == 1 ? new ListSeparator() : null;
        }

        @Override
        public boolean isSpeedSearchEnabled() {
            return true;
        }

        @Override
        public @Nullable String getTooltipTextFor(StartLocations.Location value) {
            return null;
        }

        @Override
        public void setEmptyText(@NotNull StatusText emptyText) {
        }

        @Override
        public @Nullable PopupStep<?> onChosen(StartLocations.Location selectedValue, boolean finalChoice, @Nullable InputEvent inputEvent) {
            return onChosen(selectedValue, finalChoice);
        }

        @Override
        public @Nullable PopupStep<?> onChosen(StartLocations.Location selectedValue, boolean finalChoice) {
            return doFinalStep(() -> start(this.project, this.favourite, this.debug, selectedValue));
        }
    }
}
