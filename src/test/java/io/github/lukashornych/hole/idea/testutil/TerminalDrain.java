package io.github.lukashornych.hole.idea.testutil;

import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Reads a terminal connector until its end on a background thread, like the terminal widget does, and keeps the
 * text. Tests use it instead of a widget.
 */
public final class TerminalDrain {

    private final StringBuffer output = new StringBuffer();
    private final TtyConnector connector;

    private TerminalDrain(@NotNull TtyConnector connector) {
        this.connector = connector;
    }

    @NotNull
    public static TerminalDrain start(@NotNull TtyConnector connector) {
        final TerminalDrain drain = new TerminalDrain(connector);
        Thread.ofPlatform().daemon().name("test terminal drain").start(drain::run);
        return drain;
    }

    private void run() {
        final char[] buffer = new char[4096];
        try {
            int read;
            while ((read = this.connector.read(buffer, 0, buffer.length)) >= 0) {
                this.output.append(buffer, 0, read);
            }
        } catch (IOException e) {
            // end of the pty
        }
    }

    /**
     * Types into the terminal.
     */
    public void write(@NotNull String text) throws IOException {
        this.connector.write(text);
    }

    @NotNull
    public String output() {
        return this.output.toString();
    }
}
