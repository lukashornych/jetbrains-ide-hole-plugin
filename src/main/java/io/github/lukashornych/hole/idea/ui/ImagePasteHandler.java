package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.terminal.JBTerminalWidget;
import com.jediterm.terminal.TerminalMode;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.JediTerminal;
import io.github.lukashornych.hole.idea.session.PasteStore;
import io.github.lukashornych.hole.idea.session.SandboxSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.KeyStroke;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Pastes a clipboard image into a sandbox terminal: on the terminal's paste shortcuts (`Cmd+V` on macOS, `Ctrl+Shift+V`
 * in the XWin keymap) or `Ctrl+V` (what
 * Claude Code tells users to press) with an image on the clipboard, the image is saved as PNG into the sandbox's paste
 * directory and its container path is typed into the terminal, as a bracketed paste when the program asked for one.
 * Claude Code turns such a pasted image path into an attached image. Any other key, or a paste key without an image on
 * the clipboard, goes to the terminal unchanged.
 *
 * <p>Installed as a pre-key handler of the terminal panel, which sees every key before jediterm's own handling (and,
 * through the terminal's own event dispatcher, before the IDE keymap); consuming the event suppresses the terminal's
 * text paste and the `0x16` of `Ctrl+V`. Only a key event of the IDE reaches the host clipboard: nothing inside the
 * sandbox can trigger it.</p>
 *
 * <p>The decision runs on the EDT and only asks which flavours the clipboard has; reading and encoding the image and
 * writing the file run on a pooled thread (reading can block on an X11 owner or a large macOS TIFF).</p>
 */
final class ImagePasteHandler {

    /** Bracketed paste start (`CSI 200 ~`). */
    static final String PASTE_START = "\u001b[200~";
    /** Bracketed paste end (`CSI 201 ~`). */
    static final String PASTE_END = "\u001b[201~";
    /** Offered by some clipboard owners instead of {@link DataFlavor#imageFlavor}. */
    static final DataFlavor PNG_STREAM = new DataFlavor("image/png", "PNG image");

    private static final Logger LOG = Logger.getInstance(ImagePasteHandler.class);
    private static final int MODIFIERS = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
        | InputEvent.META_DOWN_MASK | InputEvent.ALT_GRAPH_DOWN_MASK;
    private static final char CTRL_V = 0x16;
    private static final String SANDBOX_ENDED = "The sandbox ended before the image was pasted.";

    private final JBTerminalWidget widget;
    private final JBTerminalSystemSettingsProvider settings;
    private final Target target;
    private final Consumer<String> failures;
    private final PasteStore store;
    /** The pressed event of a paste key was consumed; its typed event must not reach the terminal either. EDT only. */
    private boolean swallowTyped;
    /** The user was told once why this sandbox has no image paste. EDT only. */
    private boolean explained;

    /**
     * @param widget   the terminal the path is typed into
     * @param settings the widget's settings, whose paste shortcuts are the ones the terminal pastes on
     * @param target   the sandbox behind the terminal
     * @param failures shows why an image was not pasted (a balloon in the IDE); called on any thread
     */
    ImagePasteHandler(
        @NotNull JBTerminalWidget widget, @NotNull JBTerminalSystemSettingsProvider settings, @NotNull Target target,
        @NotNull Consumer<String> failures
    ) {
        this.widget = widget;
        this.settings = settings;
        this.target = target;
        this.failures = failures;
        this.store = new PasteStore(Clock.systemDefaultZone());
    }

    /**
     * Installs the handler on the widget's terminal panel. Call before the widget is started.
     */
    void install() {
        this.widget.getTerminalPanel().addPreKeyEventHandler(this::handle);
    }

    /**
     * Pre-key handler: consumes a paste key when it pastes an image. EDT.
     */
    void handle(@NotNull KeyEvent event) {
        if (event.getID() == KeyEvent.KEY_TYPED) {
            if (this.swallowTyped) {
                this.swallowTyped = false;
                if (isPasteChar(event.getKeyChar())) {
                    event.consume();
                }
            }
            return;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED || isModifierKey(event.getKeyCode())) {
            return;
        }
        // a typed event that never came must not swallow the next key
        this.swallowTyped = false;
        if (!isPasteKey(event, this.settings)) {
            return;
        }
        if (!CopyPasteManager.getInstance().areDataFlavorsAvailable(DataFlavor.imageFlavor, PNG_STREAM)) {
            logClipboardFlavours();
            return;
        }
        final Path directory = this.target.pasteDirectory();
        if (directory == null) {
            explainUnavailable();
            return;
        }
        event.consume();
        this.swallowTyped = true;
        ApplicationManager.getApplication().executeOnPooledThread(() -> paste(directory));
    }

    /**
     * Reads the clipboard image, stores it and types its path. Pooled thread.
     */
    private void paste(@NotNull Path directory) {
        final String path;
        try {
            final Image image = readClipboardImage();
            if (image == null) {
                this.failures.accept("The clipboard no longer holds an image.");
                return;
            }
            path = this.store.store(directory, toPng(image));
        } catch (IOException | RuntimeException e) {
            // never the image itself
            LOG.info("Image not pasted into " + directory + ": " + e);
            if (!directory.equals(this.target.pasteDirectory())) {
                // the directory went with the sandbox while the image was read
                this.failures.accept(SANDBOX_ENDED);
            } else if (e instanceof PasteStore.TooLargeException) {
                this.failures.accept(e.getMessage());
            } else {
                this.failures.accept("Could not save the image: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!directory.equals(this.target.pasteDirectory())) {
                this.failures.accept(SANDBOX_ENDED);
                return;
            }
            type(path);
        }, ModalityState.any());
    }

    /**
     * Types the path into the terminal as the terminal's own paste would: the mode is read here on the EDT, the pty
     * is written on a pooled thread (the write blocks while the program does not read).
     */
    private void type(@NotNull String path) {
        final TtyConnector connector = this.widget.getTtyConnector();
        if (connector == null) {
            this.failures.accept("The terminal is not connected.");
            return;
        }
        final boolean bracketed = this.widget.getTerminal() instanceof JediTerminal terminal
            ? terminal.isModelEnabled(TerminalMode.BracketedPasteMode)
            // unknown terminal: the agent asks for bracketed paste, a debug shell may not understand it
            : !this.target.debugShell();
        final String text = bracketed ? PASTE_START + path + PASTE_END : path;
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                connector.write(text);
            } catch (IOException e) {
                LOG.info("Image path not typed into the terminal: " + e.getMessage());
                this.failures.accept("The terminal did not accept the image path: " + e.getMessage());
            }
        });
    }

    private void explainUnavailable() {
        final String reason = this.target.unavailableReason();
        if (reason != null && !this.explained) {
            this.explained = true;
            this.failures.accept(reason);
        }
    }

    /**
     * Logs the MIME types the clipboard offers when a paste key finds no image (to see what a screenshot tool puts
     * there); types only, never contents.
     */
    private static void logClipboardFlavours() {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final Transferable contents = CopyPasteManager.getInstance().getContents();
            if (contents != null) {
                LOG.debug("Paste key without an image on the clipboard; offered: " + Arrays.stream(contents.getTransferDataFlavors())
                    .map(DataFlavor::getMimeType).distinct().collect(Collectors.joining(", ")));
            }
        });
    }

    @Nullable
    private static Image readClipboardImage() throws IOException {
        final CopyPasteManager clipboard = CopyPasteManager.getInstance();
        final Image image = clipboard.getContents(DataFlavor.imageFlavor);
        if (image != null) {
            return image;
        }
        final InputStream png = clipboard.getContents(PNG_STREAM);
        if (png == null) {
            return null;
        }
        try (png) {
            final byte[] bytes = png.readNBytes(PasteStore.MAX_BYTES + 1);
            if (bytes.length > PasteStore.MAX_BYTES) {
                throw new PasteStore.TooLargeException("The image is larger than " + PasteStore.MAX_BYTES / (1024 * 1024) + " MB.");
            }
            final BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (decoded == null) {
                throw new IOException("The clipboard content is not a readable image.");
            }
            return decoded;
        }
    }

    /**
     * Encodes an image as PNG. A multi-resolution image (macOS Retina) is encoded at its largest resolution.
     */
    static byte @NotNull [] toPng(@NotNull Image image) throws IOException {
        final BufferedImage buffered = toBufferedImage(image);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        // false: no PNG writer for this colour model; a plain ARGB copy always has one
        if (!ImageIO.write(buffered, "png", out) && !ImageIO.write(argbCopy(buffered), "png", out)) {
            throw new IOException("The clipboard image cannot be encoded as PNG.");
        }
        return out.toByteArray();
    }

    @NotNull
    private static BufferedImage toBufferedImage(@NotNull Image image) throws IOException {
        if (image instanceof BufferedImage buffered) {
            return buffered;
        }
        Image source = image;
        if (image instanceof MultiResolutionImage variants) {
            source = variants.getResolutionVariants().stream()
                .max(Comparator.comparingLong(variant -> (long) variant.getWidth(null) * variant.getHeight(null)))
                .orElse(image);
            if (source instanceof BufferedImage buffered) {
                return buffered;
            }
        }
        // waits until a toolkit image is loaded, so its size is known
        return argbCopy(new ImageIcon(source).getImage());
    }

    @NotNull
    private static BufferedImage argbCopy(@NotNull Image image) throws IOException {
        final int width = image.getWidth(null);
        final int height = image.getHeight(null);
        if (width <= 0 || height <= 0) {
            throw new IOException("The clipboard image could not be read.");
        }
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = result.createGraphics();
        try {
            graphics.drawImage(image, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return result;
    }

    /**
     * Returns {@code true} for `Ctrl+V` on every OS and for every key the terminal pastes on (its `Terminal.Paste`
     * shortcuts in the active keymap, else the IDE's `$Paste` ones).
     */
    static boolean isPasteKey(@NotNull KeyEvent event, @NotNull JBTerminalSystemSettingsProvider settings) {
        if (event.getKeyCode() == KeyEvent.VK_V && (event.getModifiersEx() & MODIFIERS) == InputEvent.CTRL_DOWN_MASK) {
            return true;
        }
        return settings.getPasteActionPresentation().getKeyStrokes().contains(KeyStroke.getKeyStrokeForEvent(event));
    }

    private static boolean isPasteChar(char c) {
        return c == CTRL_V || c == 'v' || c == 'V';
    }

    private static boolean isModifierKey(int keyCode) {
        return keyCode == KeyEvent.VK_SHIFT || keyCode == KeyEvent.VK_CONTROL || keyCode == KeyEvent.VK_ALT
            || keyCode == KeyEvent.VK_META || keyCode == KeyEvent.VK_ALT_GRAPH;
    }

    /**
     * The sandbox behind a terminal.
     */
    interface Target {

        /**
         * Returns the paste directory of the running process, {@code null} when it has none or has ended.
         */
        @Nullable
        Path pasteDirectory();

        /**
         * Returns why a live sandbox has no paste directory, {@code null} when it has one or has ended.
         */
        @Nullable
        String unavailableReason();

        /**
         * Returns {@code true} when the terminal runs a debug shell instead of the agent.
         */
        boolean debugShell();

        /**
         * Returns the target of a session's current process.
         */
        @NotNull
        static Target of(@NotNull SandboxSession session) {
            return new Target() {
                @Override
                public @Nullable Path pasteDirectory() {
                    return session.pasteDirectory();
                }

                @Override
                public @Nullable String unavailableReason() {
                    if (!session.snapshot().state().isAlive() || session.pasteDirectory() != null) {
                        return null;
                    }
                    return session.launch().config().imagePaste()
                        ? "The folder for pasted images could not be set up for this sandbox; see the IDE log."
                        : "Image paste is off for this sandbox. Turn on Image paste in New Sandbox or in the favourite "
                            + "and start a new sandbox.";
                }

                @Override
                public boolean debugShell() {
                    return session.launch().debug();
                }
            };
        }
    }
}
