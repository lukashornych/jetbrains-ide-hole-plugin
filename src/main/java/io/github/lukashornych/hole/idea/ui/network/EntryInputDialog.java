package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.awt.Component;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Add input of the Network window: one or more entries (separated by spaces) with Hole's syntax hint, optionally
 * prefilled from a blocked name. <i>Add</i> applies them at once (`hole modify … add`, one gateway apply) and closes
 * the input on success; an invalid value (exit 2, e.g. a host gateway conflict) is shown under the field and the input
 * stays open. Other failures close it and are reported by the window.
 */
final class EntryInputDialog extends DialogWrapper {

    private final JBTextField field = new JBTextField(40);
    private final NetworkSetting setting;
    private final Function<List<String>, HoleModifyClient.Result<Void>> apply;
    private final Consumer<HoleModifyClient.Result<Void>> done;
    private boolean applying;

    /**
     * @param parent  the Network window
     * @param setting where the entries go
     * @param prefill initial text, e.g. {@code name:443,80}
     * @param apply   runs `hole modify … add` (blocking; called off the EDT)
     * @param done    called on the EDT with the result once the input is closed after an apply
     */
    EntryInputDialog(
        @NotNull Component parent,
        @NotNull NetworkSetting setting,
        @NotNull String prefill,
        @NotNull Function<List<String>, HoleModifyClient.Result<Void>> apply,
        @NotNull Consumer<HoleModifyClient.Result<Void>> done
    ) {
        super(parent, false);
        this.setting = setting;
        this.apply = apply;
        this.done = done;
        setTitle("Add to " + setting.key());
        setOKButtonText("Add");
        this.field.setText(prefill);
        init();
    }

    /**
     * Splits the input into entries: separated by whitespace (entries never contain spaces; ports are separated by
     * commas).
     */
    @NotNull
    static List<String> values(@NotNull String text) {
        return Arrays.stream(text.trim().split("\\s+")).filter(value -> !value.isEmpty()).toList();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        final JBLabel hint = new JBLabel(this.setting == NetworkSetting.ALLOW
            ? "e.g. example.com, *.example.com:443,80, db.example.com:5432 — several separated by spaces"
            : "e.g. host.example.com, *.internal.example.com — several separated by spaces");
        hint.setForeground(UIUtil.getContextHelpForeground());
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Entry:", this.field)
            .addComponentToRightColumn(hint)
            .getPanel();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return this.field;
    }

    @Override
    protected void doOKAction() {
        if (this.applying) {
            return;
        }
        final List<String> values = values(this.field.getText());
        if (values.isEmpty()) {
            setErrorText("Enter at least one entry", this.field);
            return;
        }
        this.applying = true;
        getOKAction().setEnabled(false);
        setErrorText(null);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleModifyClient.Result<Void> result = HoleModifyClient.Result.guarded(() -> this.apply.apply(values));
            ApplicationManager.getApplication().invokeLater(() -> applied(result), ModalityState.any());
        });
    }

    private void applied(@NotNull HoleModifyClient.Result<Void> result) {
        this.applying = false;
        if (isDisposed()) {
            this.done.accept(result);
            return;
        }
        getOKAction().setEnabled(true);
        if (result.failure() == HoleModifyClient.ModifyFailure.INVALID) {
            // Hole's message, e.g. an invalid entry or a host gateway conflict; the input stays open
            setErrorText(result.message(), this.field);
            return;
        }
        close(OK_EXIT_CODE);
        this.done.accept(result);
    }
}
