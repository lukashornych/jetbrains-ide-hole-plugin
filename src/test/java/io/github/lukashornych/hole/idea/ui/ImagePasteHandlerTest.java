package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.imageio.ImageIO;
import javax.swing.KeyStroke;
import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BaseMultiResolutionImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The image paste against a real {@link JBTerminalWidget} with a recording connector: the handler is installed as in
 * the sandbox tab, the clipboard is the test application's, and key events are fed to the terminal panel like AWT
 * delivers them.
 */
public class ImagePasteHandlerTest extends BasePlatformTestCase {

    private static final String BRACKETED_PASTE_ON = "\u001b[?2004h";
    private static final String BRACKETED_PASTE_OFF = "\u001b[?2004l";
    private static final Pattern BRACKETED_PATH = Pattern.compile("\u001b\\[200~/tmp/hole-idea-paste/(\\d{8}-\\d{6}-1\\.png)\u001b\\[201~");
    private static final String OFF = "Image paste is off for this sandbox.";

    private Path directory;
    private RecordingConnector connector;
    private JBTerminalWidget widget;
    private ImagePasteHandler handler;
    private final List<String> failures = new CopyOnWriteArrayList<>();
    /** What the target reports; a test sets it to {@code null} for a sandbox without image paste. */
    private volatile Path pasteDirectory;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.directory = Files.createTempDirectory("hole-paste-test");
        this.pasteDirectory = this.directory;
        final JBTerminalSystemSettingsProvider settings = new JBTerminalSystemSettingsProvider();
        this.widget = new JBTerminalWidget(getProject(), settings, getTestRootDisposable());
        this.handler = new ImagePasteHandler(this.widget, settings, new ImagePasteHandler.Target() {
            @Override
            public @Nullable Path pasteDirectory() {
                return ImagePasteHandlerTest.this.pasteDirectory;
            }

            @Override
            public @Nullable String unavailableReason() {
                return ImagePasteHandlerTest.this.pasteDirectory == null ? OFF : null;
            }

            @Override
            public boolean debugShell() {
                return false;
            }
        }, this.failures::add);
        this.handler.install();
        this.connector = new RecordingConnector();
        this.widget.start(this.connector);
        assertTrue("the emulator did not start", this.connector.reading.await(20, TimeUnit.SECONDS));
        output(BRACKETED_PASTE_ON);
        settle();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (this.connector != null) {
                this.connector.close();
            }
            CopyPasteManager.getInstance().setContents(new StringSelection(""));
            if (this.directory != null) {
                try (Stream<Path> files = Files.walk(this.directory)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        } finally {
            super.tearDown();
        }
    }

    public void testImageWithIdePasteKeyTypesTheBracketedPath() throws IOException {
        assertImagePastedWith(pasteKey());
    }

    public void testImageWithCtrlVTypesTheBracketedPath() throws IOException {
        assertImagePastedWith(ctrlV());
    }

    public void testWithoutBracketedPasteModeThePathIsTypedPlain() {
        output(BRACKETED_PASTE_OFF);
        settle();
        CopyPasteManager.getInstance().setContents(new ImageSelection(image(3, 2)));

        press(ctrlV());

        waitOnEdt(() -> this.connector.written().endsWith(".png"), "the path");
        assertTrue(this.connector.written(), this.connector.written().matches("/tmp/hole-idea-paste/\\d{8}-\\d{6}-1\\.png"));
    }

    public void testTextOnTheClipboardLeavesThePasteKeyToTheTerminal() {
        CopyPasteManager.getInstance().setContents(new StringSelection("hello"));

        assertFalse(handle(pasteKey()).isConsumed());
        assertFalse(handle(ctrlV()).isConsumed());

        assertEquals(List.of(), this.failures);
        assertEquals(List.of(), files());
    }

    public void testSandboxWithoutPasteDirectoryLeavesTheKeyAndExplainsOnce() {
        this.pasteDirectory = null;
        CopyPasteManager.getInstance().setContents(new ImageSelection(image(3, 2)));

        assertFalse(handle(ctrlV()).isConsumed());
        assertFalse(handle(ctrlV()).isConsumed());

        assertEquals(List.of(OFF), this.failures);
    }

    public void testOtherKeysAreNotTouched() {
        CopyPasteManager.getInstance().setContents(new ImageSelection(image(3, 2)));

        press(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0));

        waitOnEdt(() -> !this.connector.written().isEmpty(), "the key");
        assertEquals("a", this.connector.written());
    }

    public void testTooLargeImageIsReportedAndNothingIsTyped() {
        // random pixels do not compress: about 16 MB as PNG
        final BufferedImage noise = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_ARGB);
        final Random random = new Random(1);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt());
            }
        }
        CopyPasteManager.getInstance().setContents(new ImageSelection(noise));

        final KeyEvent pressed = handle(ctrlV());

        assertTrue("an image paste is consumed", pressed.isConsumed());
        waitOnEdt(() -> !this.failures.isEmpty(), "the failure");
        assertTrue(this.failures.toString(), this.failures.get(0).contains("at most 10.0 MB"));
        settle();
        assertEquals("", this.connector.written());
        assertEquals(List.of(), files());
    }

    public void testSandboxEndingBeforeTheImageIsSavedTypesNothing() {
        CopyPasteManager.getInstance().setContents(new ImageSelection(image(3, 2)));

        handle(ctrlV());
        // the session ended before the EDT got the stored path back
        this.pasteDirectory = null;

        waitOnEdt(() -> !this.failures.isEmpty(), "the failure");
        assertEquals(List.of("The sandbox ended before the image was pasted."), this.failures);
        settle();
        assertEquals("", this.connector.written());
    }

    public void testPngEncodingRoundTrips() throws IOException {
        final BufferedImage source = image(5, 4);

        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(ImagePasteHandler.toPng(source)));

        assertEquals(5, decoded.getWidth());
        assertEquals(4, decoded.getHeight());
        assertEquals(source.getRGB(2, 1), decoded.getRGB(2, 1));
    }

    public void testMultiResolutionImageIsEncodedAtItsLargestResolution() throws IOException {
        final Image retina = new BaseMultiResolutionImage(image(5, 4), image(10, 8));

        final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(ImagePasteHandler.toPng(retina)));

        assertEquals(10, decoded.getWidth());
        assertEquals(8, decoded.getHeight());
    }

    private void assertImagePastedWith(@NotNull KeyStroke key) throws IOException {
        final BufferedImage image = image(3, 2);
        CopyPasteManager.getInstance().setContents(new ImageSelection(image));

        press(key);

        waitOnEdt(() -> this.connector.written().endsWith("\u001b[201~"), "the pasted path");
        final String written = this.connector.written();
        final java.util.regex.Matcher matcher = BRACKETED_PATH.matcher(written);
        assertTrue("exactly the bracketed path, no 0x16 or 'v': " + written.replace("\u001b", "ESC"), matcher.matches());
        final Path file = this.directory.resolve(matcher.group(1));
        assertEquals(List.of(matcher.group(1)), files());
        assertEquals(image.getRGB(1, 1), ImageIO.read(file.toFile()).getRGB(1, 1));
        assertEquals(List.of(), this.failures);
    }

    @NotNull
    private static BufferedImage image(int width, int height) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xff000000 | (x * 40) << 16 | (y * 60) << 8 | 0x80);
            }
        }
        return image;
    }

    @NotNull
    private List<String> files() {
        try (Stream<Path> list = Files.list(this.directory)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @NotNull
    private JBTerminalPanel panel() {
        return this.widget.getTerminalPanel();
    }

    /**
     * The first key stroke of the terminal's paste action in the active keymap (`Cmd+V` on macOS).
     */
    @NotNull
    private static KeyStroke pasteKey() {
        return new JBTerminalSystemSettingsProvider().getPasteActionPresentation().getKeyStrokes().get(0);
    }

    @NotNull
    private static KeyStroke ctrlV() {
        return KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK);
    }

    /**
     * Passes the pressed event of a key stroke to the handler alone.
     */
    @NotNull
    private KeyEvent handle(@NotNull KeyStroke stroke) {
        final KeyEvent event = new KeyEvent(panel(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke.getModifiers(),
            stroke.getKeyCode(), typedChar(stroke));
        this.handler.handle(event);
        return event;
    }

    /**
     * Feeds the pressed and the typed event of a key stroke to the panel, as AWT delivers them.
     */
    private void press(@NotNull KeyStroke stroke) {
        final int modifiers = stroke.getModifiers();
        final char typed = typedChar(stroke);
        final long now = System.currentTimeMillis();
        panel().handleKeyEvent(new KeyEvent(panel(), KeyEvent.KEY_PRESSED, now, modifiers, stroke.getKeyCode(), typed));
        panel().handleKeyEvent(new KeyEvent(panel(), KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, typed));
    }

    private static char typedChar(@NotNull KeyStroke stroke) {
        final int modifiers = stroke.getModifiers();
        return (modifiers & InputEvent.CTRL_DOWN_MASK) != 0 && (modifiers & InputEvent.META_DOWN_MASK) == 0
            ? (char) (stroke.getKeyCode() - KeyEvent.VK_A + 1)
            : Character.toLowerCase((char) stroke.getKeyCode());
    }

    private void output(@NotNull String text) {
        this.connector.output.add(text);
    }

    /**
     * Gives asynchronous work the chance to happen before asserting that none did.
     */
    private static void settle() {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            sleep();
        }
    }

    private static void waitOnEdt(@NotNull BooleanSupplier condition, @NotNull String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue();
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        fail("Timed out waiting for " + what);
    }

    private static void sleep() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /**
     * An image on the clipboard, as a screenshot tool puts it there.
     */
    private record ImageSelection(@NotNull Image image) implements Transferable {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public @NotNull Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return this.image;
        }
    }

    /**
     * A pty stand-in: serves queued program output and records everything the terminal writes.
     */
    private static final class RecordingConnector implements TtyConnector {

        private static final String CLOSED = "";

        final BlockingQueue<String> output = new LinkedBlockingQueue<>();
        final CountDownLatch reading = new CountDownLatch(1);
        private final StringBuilder written = new StringBuilder();
        private volatile boolean connected = true;

        @Override
        public int read(char[] buf, int offset, int length) {
            this.reading.countDown();
            final String next;
            try {
                next = this.output.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if (!this.connected) {
                return -1;
            }
            // test output is a few characters, always shorter than jediterm's buffer
            next.getChars(0, next.length(), buf, offset);
            return next.length();
        }

        @Override
        public synchronized void write(byte[] bytes) {
            this.written.append(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public synchronized void write(String string) {
            this.written.append(string);
        }

        synchronized String written() {
            return this.written.toString();
        }

        @Override
        public boolean isConnected() {
            return this.connected;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            return !this.output.isEmpty();
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public void close() {
            this.connected = false;
            this.output.add(CLOSED);
        }
    }
}
