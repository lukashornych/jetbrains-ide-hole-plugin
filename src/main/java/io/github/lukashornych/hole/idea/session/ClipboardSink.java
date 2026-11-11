package io.github.lukashornych.hole.idea.session;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import org.jetbrains.annotations.NotNull;

import java.awt.datatransfer.StringSelection;

/**
 * Receives the clipboard writes (OSC 52) a program in a sandbox makes. Called on the terminal's reader thread.
 */
public interface ClipboardSink {

    /** Ignores every write. */
    ClipboardSink NONE = new ClipboardSink() {
        @Override
        public void copy(@NotNull SandboxSession session, @NotNull String text) {
        }

        @Override
        public void tooLarge(@NotNull SandboxSession session) {
        }
    };

    /**
     * Puts {@code text} into the host clipboard.
     */
    void copy(@NotNull SandboxSession session, @NotNull String text);

    /**
     * A write was dropped because it exceeded the size limit.
     */
    void tooLarge(@NotNull SandboxSession session);

    /**
     * Returns the sink used in the IDE: the system clipboard, with a message in the project's status bar. It runs in
     * any modality, so a copy is not held back by an open modal dialog.
     */
    @NotNull
    static ClipboardSink platform(@NotNull Project project) {
        return new ClipboardSink() {
            @Override
            public void copy(@NotNull SandboxSession session, @NotNull String text) {
                final int characters = text.codePointCount(0, text.length());
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        CopyPasteManager.getInstance().setContents(new StringSelection(text));
                        StatusBar.Info.set("Hole: copied " + characters + (characters == 1 ? " character" : " characters")
                            + " from " + tabTitle(session), project);
                    }
                }, ModalityState.any());
            }

            @Override
            public void tooLarge(@NotNull SandboxSession session) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (!project.isDisposed()) {
                        StatusBar.Info.set("Hole: text from " + tabTitle(session) + " too large to copy", project);
                    }
                }, ModalityState.any());
            }
        };
    }

    @NotNull
    private static String tabTitle(@NotNull SandboxSession session) {
        final SandboxSession.Launch launch = session.launch();
        return SessionPresentation.tabTitle(
            launch.config(), session.snapshot().agentTitle(), SessionPresentation.projectFolder(launch.projectDirectory())
        );
    }
}
