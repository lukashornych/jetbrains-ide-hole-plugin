package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import io.github.lukashornych.hole.idea.hole.HoleModifyClient;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Add input of the Network window: one or more entries (separated by spaces) with Hole's syntax hint, optionally
 * prefilled from a blocked name. <i>Add</i> first asks in a popup how long the entries stay ({@link Target}: this
 * sandbox only, or also saved to the global or project settings), then applies them at once (`hole modify … add`, one
 * gateway apply) and closes the input on success; an invalid value (exit 2, e.g. a host gateway conflict) is shown
 * under the field and the input stays open. Other failures close it and are reported by the window.
 */
final class EntryInputDialog extends DialogWrapper {

    /** Hole gates `network.*` of a project settings file behind its trust prompt. */
    static final String PROJECT_TRUST_HINT = "Project settings: the next start asks you to trust the change";

    private final JBTextField field = new JBTextField(40);
    private final NetworkSetting setting;
    private final Applier apply;
    private final Consumer<HoleModifyClient.Result<Void>> done;
    private boolean applying;

    /**
     * @param parent  the Network window
     * @param setting where the entries go
     * @param prefill initial text, e.g. {@code name:443,80}
     * @param apply   runs `hole modify … add` and saves to the chosen settings file (blocking; called off the EDT)
     * @param done    called on the EDT with the result once the input is closed after an apply
     */
    EntryInputDialog(
        @NotNull Component parent,
        @NotNull NetworkSetting setting,
        @NotNull String prefill,
        @NotNull Applier apply,
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
        final ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<>(null, Target.values()) {
            @Override
            public @NotNull String getTextFor(Target target) {
                return target.text();
            }

            @Override
            public @Nullable PopupStep<?> onChosen(Target target, boolean finalChoice) {
                return doFinalStep(() -> apply(values, target));
            }
        });
        popup.setAdText(PROJECT_TRUST_HINT, SwingConstants.LEFT);
        final JButton button = getButton(getOKAction());
        if (button != null && button.isShowing()) {
            popup.showUnderneathOf(button);
        } else {
            popup.showInCenterOf(getContentPanel());
        }
    }

    private void apply(@NotNull List<String> values, @NotNull Target target) {
        if (this.applying || isDisposed()) {
            return;
        }
        this.applying = true;
        getOKAction().setEnabled(false);
        setErrorText(null);
        // the input is modal: a save that waits for the EDT must run in its modality, not after it closed
        final ModalityState modality = ModalityState.stateForComponent(getContentPanel());
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleModifyClient.Result<Void> result = HoleModifyClient.Result.guarded(() -> this.apply.apply(values, target, modality));
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

    /**
     * Applies the entries; blocking, called off the EDT.
     */
    @FunctionalInterface
    interface Applier {
        /**
         * @param values   the entries
         * @param target   how long they stay
         * @param modality modality of the input, for work that has to wait for the EDT
         */
        @NotNull
        HoleModifyClient.Result<Void> apply(@NotNull List<String> values, @NotNull Target target, @NotNull ModalityState modality);
    }

    /**
     * How long added entries stay; the first one is the default (Enter in the popup).
     */
    enum Target {
        /** Runtime entries only: gone when the sandbox stops. */
        SANDBOX("Only Until the Sandbox Stops", null),
        /** Runtime entries, also saved to the global settings file. */
        GLOBAL("Also Save to Global Settings", HoleSettingsClient.Scope.GLOBAL),
        /** Runtime entries, also saved to the project settings file. */
        PROJECT("Also Save to Project Settings", HoleSettingsClient.Scope.PROJECT);

        private final String text;
        private final HoleSettingsClient.Scope scope;

        Target(@NotNull String text, @Nullable HoleSettingsClient.Scope scope) {
            this.text = text;
            this.scope = scope;
        }

        @NotNull
        String text() {
            return this.text;
        }

        /**
         * Returns the settings file to save to, or {@code null} for runtime entries only.
         */
        @Nullable
        HoleSettingsClient.Scope scope() {
            return this.scope;
        }
    }
}
