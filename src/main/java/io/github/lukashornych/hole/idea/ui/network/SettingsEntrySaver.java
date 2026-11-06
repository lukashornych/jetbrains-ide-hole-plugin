package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsEntries;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import io.github.lukashornych.hole.idea.ui.settings.SettingsDocuments;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and changes the entries of the Network window in the global and project settings files of the sandbox
 * through {@link SettingsDocuments}; {@link SettingsEntries} reads or edits the document. Blocking; call off the EDT.
 */
public final class SettingsEntrySaver {

    private static final Logger LOG = Logger.getInstance(SettingsEntrySaver.class);

    private final SettingsDocuments documents;

    /**
     * @param documents the sandbox's settings files
     */
    public SettingsEntrySaver(@NotNull SettingsDocuments documents) {
        this.documents = documents;
    }

    /**
     * Returns the canonical top-level entries of the setting per existing settings file. A file Hole cannot name or
     * that cannot be read counts as empty.
     */
    @NotNull
    Map<HoleSettingsClient.Scope, Set<String>> savedEntries(@NotNull NetworkSetting setting) {
        final Map<HoleSettingsClient.Scope, Set<String>> saved = new EnumMap<>(HoleSettingsClient.Scope.class);
        for (HoleSettingsClient.Scope scope : HoleSettingsClient.Scope.values()) {
            try {
                saved.put(scope, this.documents.read(scope).map(text -> SettingsEntries.entries(text, setting)).orElse(Set.of()));
            } catch (IllegalArgumentException e) {
                LOG.info("The " + scope.label() + " settings file was not read for the Network window: " + e.getMessage());
                saved.put(scope, Set.of());
            }
        }
        return saved;
    }

    /**
     * Saves the values to a settings file, creating it when missing.
     *
     * @param modality modality of the window that asked (the edit runs on the EDT in it)
     * @return the reason it failed, or nothing on success (also when the values were there already)
     */
    @NotNull
    Optional<String> save(
        @NotNull HoleSettingsClient.Scope scope, @NotNull NetworkSetting setting, @NotNull List<String> values,
        @NotNull ModalityState modality
    ) {
        return this.documents.edit(scope, true, modality, text -> SettingsEntries.withEntries(text, setting, values));
    }

    /**
     * Removes every value matching the entry from a settings file; a missing file changes nothing.
     *
     * @param modality modality of the window that asked (the edit runs on the EDT in it)
     * @return the reason it failed, or nothing on success
     */
    @NotNull
    Optional<String> remove(
        @NotNull HoleSettingsClient.Scope scope, @NotNull NetworkSetting setting, @NotNull String entry,
        @NotNull ModalityState modality
    ) {
        return this.documents.edit(scope, false, modality, text -> SettingsEntries.withoutEntry(text, setting, entry));
    }
}
