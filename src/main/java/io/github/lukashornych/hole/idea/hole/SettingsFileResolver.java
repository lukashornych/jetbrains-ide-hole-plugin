package io.github.lukashornych.hole.idea.hole;

import io.github.lukashornych.hole.idea.hole.HoleSettingsClient.Scope;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient.SettingsFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Finds a Hole settings file to open, creating it from Hole's template when the user agrees: `settings get` → (file
 * missing) ask → `settings create` → `settings get`. A file created meanwhile (`create` reports that it already
 * exists) counts as created. Free of Swing: the question is a callback, so the flow is tested against the fake
 * `hole` script; the header ({@code SettingsFileOpener}) opens the result in an editor, the New Sandbox / Edit
 * Favourites dialogs ({@code SettingsFileLinks}) use {@link #create} and refresh their rows. Blocking; call off the
 * EDT.
 */
public final class SettingsFileResolver {

    private final HoleSettingsClient client;

    /**
     * @param client runs `hole settings get|create`
     */
    public SettingsFileResolver(@NotNull HoleSettingsClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Returns the settings file to open, asking before a missing one is created.
     *
     * @param scope            which file
     * @param projectDirectory the sandbox's absolute project directory for {@link Scope#PROJECT}
     * @param confirmCreate    asked with the path of a missing file; {@code true} creates it (may block, e.g. for a
     *                         dialog on the EDT)
     */
    @NotNull
    public Result resolve(@NotNull Scope scope, @Nullable Path projectDirectory, @NotNull Predicate<Path> confirmCreate) {
        final HoleSettingsClient.Lookup lookup = this.client.lookup(scope, projectDirectory);
        final SettingsFile file = lookup.file();
        if (file == null) {
            return new Result(Outcome.FAILED, null, lookup.failure());
        }
        if (file.exists()) {
            return new Result(Outcome.EXISTS, file.path(), "");
        }
        if (!confirmCreate.test(file.path())) {
            return new Result(Outcome.CANCELLED, file.path(), "");
        }
        return create(scope, projectDirectory);
    }

    /**
     * Creates a missing settings file (`settings create`, then `settings get` for its path). An existing file is
     * reported as created and left alone.
     *
     * @param scope            which file
     * @param projectDirectory the absolute project directory for {@link Scope#PROJECT}
     */
    @NotNull
    public Result create(@NotNull Scope scope, @Nullable Path projectDirectory) {
        final HoleSettingsClient.Creation creation = this.client.create(scope, projectDirectory);
        if (creation.outcome() == HoleSettingsClient.Created.FAILED) {
            return new Result(Outcome.FAILED, null, creation.message());
        }
        final HoleSettingsClient.Lookup lookup = this.client.lookup(scope, projectDirectory);
        final SettingsFile file = lookup.file();
        if (file == null) {
            return new Result(Outcome.FAILED, null, lookup.failure());
        }
        if (!file.exists()) {
            return new Result(Outcome.FAILED, file.path(), "Hole did not create " + file.path());
        }
        return new Result(Outcome.CREATED, file.path(), "");
    }

    /**
     * What happened.
     */
    public enum Outcome {
        /** The file existed: open it. */
        EXISTS,
        /** The file was created now (or meanwhile): open it. */
        CREATED,
        /** The file is missing and the user did not want it created. */
        CANCELLED,
        /** Hole failed; see {@link Result#message()}. */
        FAILED
    }

    /**
     * Outcome of {@link #resolve} / {@link #create}.
     *
     * @param outcome what happened
     * @param path    the file, {@code null} when Hole could not tell
     * @param message Hole's message for {@link Outcome#FAILED}, otherwise empty
     */
    public record Result(@NotNull Outcome outcome, @Nullable Path path, @NotNull String message) {

        /**
         * Returns {@code true} when there is a file to open.
         */
        public boolean openable() {
            return (this.outcome == Outcome.EXISTS || this.outcome == Outcome.CREATED) && this.path != null;
        }
    }
}
