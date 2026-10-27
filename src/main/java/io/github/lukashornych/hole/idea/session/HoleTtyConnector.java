package io.github.lukashornych.hole.idea.session;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Connects the terminal widget to the `hole` pty and lets the session see what the widget reads, so it can recognise
 * Hole's "instance ID already in use" error without parsing anything else. Resizes of the widget are forwarded to the
 * pty.
 *
 * <p>It extends jediterm's {@link ProcessTtyConnector}, not IntelliJ's {@code PtyProcessTtyConnector}, whose
 * constructor changed from {@code PtyProcess} to {@code Process} in 2026.3: a plugin compiled against one does not
 * run on the other.</p>
 */
public final class HoleTtyConnector extends ProcessTtyConnector {

    private final PtyProcess process;
    private final OutputTap tap;

    /**
     * @param process the `hole` process
     * @param tap     receives everything read from the pty, and its end
     */
    public HoleTtyConnector(@NotNull PtyProcess process, @NotNull OutputTap tap) {
        super(process, StandardCharsets.UTF_8);
        this.process = process;
        this.tap = tap;
    }

    @Override
    public String getName() {
        return "Hole";
    }

    @Override
    public void resize(@NotNull TermSize termSize) {
        if (isConnected()) {
            this.process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
        }
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        final int read;
        try {
            read = super.read(buf, offset, length);
        } catch (IOException e) {
            // the pty reports the end of the child as an I/O error on some systems
            this.tap.closed();
            throw e;
        }
        if (read < 0) {
            this.tap.closed();
        } else if (read > 0) {
            this.tap.output(buf, offset, read);
        }
        return read;
    }

    /**
     * Receives the terminal output of one process.
     */
    public interface OutputTap {

        /**
         * Characters read from the pty.
         */
        void output(char @NotNull [] buffer, int offset, int length);

        /**
         * The pty reached its end; no more output follows.
         */
        void closed();
    }
}
