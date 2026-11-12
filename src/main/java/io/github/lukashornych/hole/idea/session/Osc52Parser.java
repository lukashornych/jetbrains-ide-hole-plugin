package io.github.lukashornych.hole.idea.session;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Finds OSC 52 clipboard writes ({@code ESC ] 52 ; targets ; base64 BEL} or {@code … ESC \}) in terminal output fed
 * in arbitrary chunks. Other OSC sequences are skipped without buffering them. Queries ({@code ?}), empty and invalid
 * payloads are ignored: the sandbox can neither read nor clear the clipboard. Payloads longer than the limit are
 * dropped without being buffered beyond it.
 *
 * <p>Not thread-safe; the caller feeds it from one reader at a time.</p>
 */
public final class Osc52Parser {

    /** Largest base64 payload accepted (1 MiB, about 768 KiB of text). */
    static final int DEFAULT_LIMIT = 1024 * 1024;

    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;
    private static final char CAN = 0x18;
    private static final char SUB = 0x1A;
    /** Longest OSC number collected; anything longer is not 52. */
    private static final int MAX_PARAM = 8;

    private final Listener listener;
    private final int limit;
    private final StringBuilder param = new StringBuilder(MAX_PARAM);
    private final StringBuilder payload = new StringBuilder();
    private State state = State.TEXT;
    /** An ESC was seen inside an OSC; the next character decides whether it is ST or a new sequence. */
    private boolean pendingEscape;

    /**
     * @param listener receives the decoded text of every complete write
     */
    public Osc52Parser(@NotNull Listener listener) {
        this(listener, DEFAULT_LIMIT);
    }

    Osc52Parser(@NotNull Listener listener, int limit) {
        this.listener = listener;
        this.limit = limit;
    }

    /**
     * Processes the next chunk of terminal output.
     */
    public void feed(char @NotNull [] buffer, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            accept(buffer[i]);
        }
    }

    private void accept(char c) {
        if (this.state == State.TEXT) {
            if (c == ESC) {
                this.state = State.ESCAPE;
            }
            return;
        }
        if (this.state == State.ESCAPE) {
            afterEscape(c);
            return;
        }
        // inside an OSC
        if (this.pendingEscape) {
            this.pendingEscape = false;
            if (c == '\\') {
                terminate();
            } else {
                // another escape sequence starts: the OSC is abandoned, like in xterm
                reset();
                afterEscape(c);
            }
            return;
        }
        if (c == BEL) {
            terminate();
        } else if (c == ESC) {
            this.pendingEscape = true;
        } else if (c == CAN || c == SUB) {
            reset();
        } else {
            inOsc(c);
        }
    }

    private void afterEscape(char c) {
        if (c == ']') {
            this.param.setLength(0);
            this.state = State.OSC_PARAM;
        } else {
            this.state = c == ESC ? State.ESCAPE : State.TEXT;
        }
    }

    private void inOsc(char c) {
        switch (this.state) {
            case OSC_PARAM -> {
                if (c == ';') {
                    this.state = "52".contentEquals(this.param) ? State.TARGETS : State.OSC_SKIP;
                } else if (this.param.length() < MAX_PARAM) {
                    this.param.append(c);
                } else {
                    this.state = State.OSC_SKIP;
                }
            }
            case TARGETS -> {
                if (c == ';') {
                    this.payload.setLength(0);
                    this.state = State.PAYLOAD;
                }
            }
            case PAYLOAD -> {
                if (this.payload.length() < this.limit) {
                    this.payload.append(c);
                } else {
                    this.payload.setLength(0);
                    this.payload.trimToSize();
                    this.state = State.OVERFLOW;
                }
            }
            default -> {
                // OSC_SKIP, OVERFLOW: drop until the terminator
            }
        }
    }

    private void terminate() {
        final State ended = this.state;
        final String data = ended == State.PAYLOAD ? this.payload.toString() : null;
        reset();
        if (ended == State.OVERFLOW) {
            this.listener.tooLarge();
        } else if (data != null) {
            final String text = decode(data);
            if (text != null) {
                this.listener.copied(text);
            }
        }
    }

    private void reset() {
        this.state = State.TEXT;
        this.pendingEscape = false;
        this.param.setLength(0);
        this.payload.setLength(0);
        if (this.payload.capacity() > 64 * 1024) {
            this.payload.trimToSize();
        }
    }

    /**
     * Returns the text of a payload, {@code null} for a query, an empty payload, invalid base64 or invalid UTF-8.
     * Line breaks and spaces are removed first (some programs wrap the base64); any other character outside the
     * alphabet makes it invalid.
     */
    private static String decode(@NotNull String data) {
        if (data.equals("?")) {
            return null;
        }
        final String compact = data.replaceAll("[\\r\\n\\t ]", "");
        if (compact.isEmpty()) {
            return null;
        }
        try {
            final byte[] bytes = Base64.getDecoder().decode(compact);
            if (bytes.length == 0) {
                return null;
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | CharacterCodingException e) {
            return null;
        }
    }

    private enum State {
        TEXT, ESCAPE, OSC_PARAM, OSC_SKIP, TARGETS, PAYLOAD, OVERFLOW
    }

    /**
     * Receives what the parser found.
     */
    public interface Listener {

        /**
         * A program wrote {@code text} to the clipboard.
         */
        void copied(@NotNull String text);

        /**
         * A program wrote a payload over the limit; it was dropped.
         */
        void tooLarge();
    }
}
