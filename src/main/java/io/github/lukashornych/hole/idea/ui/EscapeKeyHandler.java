package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.terminal.JBTerminalWidget;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import java.awt.event.KeyEvent;
import java.io.IOException;

/**
 * Sends a plain `Escape` to the program in a sandbox terminal instead of letting the IDE move the focus to the editor.
 *
 * <p>The platform's {@code TerminalEscapeKeyListener} (internal API) moves the focus to the editor on `Escape` in any
 * tool window but the Terminal one, unless the program uses the alternate screen buffer. Claude Code draws in the main
 * buffer and binds `Escape` (interrupt, close a menu), so the key would never reach it. Installed as a pre-key handler
 * of the terminal panel, which runs before that listener: the consumed key is written to the pty on a pooled thread
 * (the write blocks while the program does not read). The Terminal plugin's "Move focus to the editor with Escape"
 * advanced setting is not honoured: its id is not a public contract and reading an unknown id throws.</p>
 */
final class EscapeKeyHandler {

    private static final Logger LOG = Logger.getInstance(EscapeKeyHandler.class);
    private static final byte[] ESC = {0x1b};

    private final JBTerminalWidget widget;
    /** The pressed event of `Escape` was consumed; its typed event must not reach the terminal either. EDT only. */
    private boolean swallowTyped;

    EscapeKeyHandler(@NotNull JBTerminalWidget widget) {
        this.widget = widget;
    }

    /**
     * Installs the handler on the widget's terminal panel. Call before the widget is started.
     */
    void install() {
        this.widget.getTerminalPanel().addPreKeyEventHandler(this::handle);
    }

    /**
     * Pre-key handler: consumes a plain `Escape` and sends it to the terminal. EDT.
     */
    void handle(@NotNull KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_TYPED) {
            if (this.swallowTyped) {
                this.swallowTyped = false;
                if (event.getKeyChar() == ESC[0]) {
                    event.consume();
                }
            }
            return;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED) {
            return;
        }
        this.swallowTyped = false;
        if (event.getKeyCode() != KeyEvent.VK_ESCAPE || event.getModifiersEx() != 0) {
            return;
        }
        final TtyConnector connector = this.widget.getTtyConnector();
        if (connector == null) {
            return;
        }
        event.consume();
        this.swallowTyped = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                connector.write(ESC);
            } catch (IOException e) {
                LOG.info("Escape not written to the terminal: " + e.getMessage());
            }
        });
    }
}
