package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import io.github.lukashornych.hole.idea.storage.HolePluginSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Settings page <i>Tools › Hole</i>: the path of the `hole` executable.
 */
public final class HoleSettingsConfigurable implements SearchableConfigurable {

    /** Configurable ID, also referenced from notifications that link to this page. */
    public static final String ID = "io.github.lukashornych.hole.idea.settings";

    private TextFieldWithBrowseButton executableField;
    private JPanel panel;

    @NotNull
    @Override
    public String getId() {
        return ID;
    }

    @Nls
    @Override
    public String getDisplayName() {
        return "Hole";
    }

    @Nullable
    @Override
    public JComponent createComponent() {
        this.executableField = new TextFieldWithBrowseButton();
        this.executableField.addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.singleFile().withTitle("Hole Executable")
        );
        this.panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Hole executable:", this.executableField)
            .addTooltip("Absolute path, or ~/... Leave empty to use hole from the shell PATH, or ~/.local/bin/hole.")
            .addComponentFillVertically(new JPanel(), 0)
            .getPanel();
        reset();
        return this.panel;
    }

    @Override
    public boolean isModified() {
        return this.executableField != null
            && !this.executableField.getText().trim().equals(HolePluginSettings.getInstance().getHoleExecutablePath());
    }

    @Override
    public void apply() throws ConfigurationException {
        final String error = HolePluginSettings.validateExecutablePath(this.executableField.getText());
        if (error != null) {
            throw new ConfigurationException(error);
        }
        HolePluginSettings.getInstance().setHoleExecutablePath(this.executableField.getText());
    }

    @Override
    public void reset() {
        if (this.executableField != null) {
            this.executableField.setText(HolePluginSettings.getInstance().getHoleExecutablePath());
        }
    }

    @Override
    public void disposeUIResources() {
        this.executableField = null;
        this.panel = null;
    }
}
