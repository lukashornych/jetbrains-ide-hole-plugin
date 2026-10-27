package io.github.lukashornych.hole.idea.storage;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.RoamingType;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import lombok.Data;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Application-level plugin settings (<i>Settings › Tools › Hole</i>), stored in the IDE configuration
 * (`options/hole.xml`). Not roamed by Settings Sync, because the executable path is specific to the machine.
 */
@Service(Service.Level.APP)
@State(name = "HolePluginSettings", storages = @Storage(value = "hole.xml", roamingType = RoamingType.LOCAL))
public final class HolePluginSettings implements PersistentStateComponent<HolePluginSettings.SettingsState> {

    private SettingsState state = new SettingsState();

    /**
     * Returns the application-wide instance.
     */
    @NotNull
    public static HolePluginSettings getInstance() {
        return ApplicationManager.getApplication().getService(HolePluginSettings.class);
    }

    @NotNull
    @Override
    public SettingsState getState() {
        return this.state;
    }

    @Override
    public void loadState(@NotNull SettingsState state) {
        this.state = state;
    }

    /**
     * Returns the configured path of the `hole` executable, empty when it is detected automatically.
     */
    @NotNull
    public String getHoleExecutablePath() {
        final String path = this.state.getHoleExecutablePath();
        return path == null ? "" : path.trim();
    }

    /**
     * Sets the path of the `hole` executable; blank means automatic detection.
     */
    public void setHoleExecutablePath(String path) {
        this.state.setHoleExecutablePath(path == null ? "" : path.trim());
    }

    /**
     * Validates a value typed into the settings page.
     *
     * @return an error message, or {@code null} when the value is blank (automatic detection), absolute, or starts
     * with `~/` (expanded against the home directory)
     */
    @Nullable
    public static String validateExecutablePath(@Nullable String path) {
        final String value = path == null ? "" : path.trim();
        if (value.isEmpty() || value.startsWith("~/")) {
            return null;
        }
        try {
            if (Path.of(value).isAbsolute()) {
                return null;
            }
        } catch (InvalidPathException e) {
            return "The Hole executable path is not a valid path.";
        }
        return "The Hole executable path must be absolute (or start with ~/). Leave it empty to detect Hole automatically.";
    }

    /**
     * Persisted state bean. Mutable with a no-arg constructor, as required by the XML serializer.
     */
    @Data
    public static class SettingsState {
        /** Path of the `hole` executable; empty for automatic detection. */
        private String holeExecutablePath = "";
    }
}
