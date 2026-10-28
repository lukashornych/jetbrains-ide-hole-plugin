package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import io.github.lukashornych.hole.idea.hole.HoleCapability;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.InstanceIdGenerator;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import io.github.lukashornych.hole.idea.session.SandboxSessionManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * <i>New Sandbox</i>: the sandbox form, always with default values (no memory of previous ad-hoc values), and the
 * buttons Save as Favourite…, Cancel, Start in Debug Shell and Start. Starting is disabled with the reason when Hole
 * is missing or too old.
 */
public final class NewSandboxDialog extends DialogWrapper {

    private final Project project;
    private final SandboxForm form;
    private final String instanceId = new InstanceIdGenerator().generate();
    private final Action debugAction = new AbstractAction("Start in Debug Shell", HoleIcons.DEBUG) {
        @Override
        public void actionPerformed(ActionEvent e) {
            startDebug();
        }
    };
    private final Action saveAsFavouriteAction = new AbstractAction("Save as Favourite…") {
        @Override
        public void actionPerformed(ActionEvent e) {
            saveAsFavourite();
        }
    };
    private volatile HoleCapability capability;
    private Result result;

    /**
     * @param project current project
     */
    public NewSandboxDialog(@NotNull Project project) {
        super(project);
        this.project = project;
        setTitle("New Sandbox");
        setOKButtonText("Start");
        this.form = new SandboxForm(project, SandboxSessionManager.getInstance(project).projectRoot(), true, getDisposable(), () -> { });
        this.form.setConfig(SandboxConfig.defaults());
        this.form.setPreviewInstanceId(this.instanceId);
        init();
        initValidation();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final HoleCapability probed = HoleExecutable.getInstance().probe();
            ApplicationManager.getApplication().invokeLater(() -> this.capability = probed, ModalityState.any());
        });
    }

    /**
     * Returns what to start after the dialog was closed with Start or Start in Debug Shell.
     */
    @Nullable
    public Result result() {
        return this.result;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return this.form.component();
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return this.form.preferredFocus();
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "Hole.NewSandboxDialog";
    }

    @Override
    protected Action @NotNull [] createActions() {
        return new Action[]{getCancelAction(), this.debugAction, getOKAction()};
    }

    @Override
    protected Action @NotNull [] createLeftSideActions() {
        return new Action[]{this.saveAsFavouriteAction};
    }

    @Override
    public void setOKActionEnabled(boolean isEnabled) {
        super.setOKActionEnabled(isEnabled);
        this.debugAction.setEnabled(isEnabled);
    }

    @Override
    protected @NotNull List<ValidationInfo> doValidateAll() {
        final List<ValidationInfo> problems = new ArrayList<>(this.form.validate());
        final HoleCapability hole = this.capability;
        if (hole != null && !hole.canStart()) {
            problems.add(new ValidationInfo(hole.message()));
        }
        return problems;
    }

    @Override
    protected void doOKAction() {
        this.result = new Result(this.form.getConfig(), false, this.instanceId);
        super.doOKAction();
    }

    private void startDebug() {
        final List<ValidationInfo> problems = doValidateAll();
        if (!problems.isEmpty()) {
            updateErrorInfo(problems);
            final ValidationInfo first = problems.get(0);
            if (first.component != null) {
                first.component.requestFocusInWindow();
            }
            return;
        }
        this.result = new Result(this.form.getConfig(), true, this.instanceId);
        close(OK_EXIT_CODE);
    }

    private void saveAsFavourite() {
        final SandboxConfig config = this.form.getConfig();
        new EditFavouritesDialog(this.project, new Favourite(config.agentSpec(), FavouriteScope.PROJECT, config)).show();
    }

    /**
     * What the user chose to start.
     *
     * @param config     form values
     * @param debug      Start in Debug Shell
     * @param instanceId the instance ID shown in the preview
     */
    public record Result(@NotNull SandboxConfig config, boolean debug, @NotNull String instanceId) {
    }
}
