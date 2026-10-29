package io.github.lukashornych.hole.idea.ui;

import com.intellij.ide.impl.HeadlessDataManager;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataSink;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.UiDataProvider;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.wm.RegisterToolWindowTask;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowAnchor;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalPanel;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.jediterm.terminal.TerminalMode;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.JediTerminal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.terminal.JBTerminalSystemSettingsProvider;

import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Pins down the platform behaviour the image paste relies on, against a real {@link JBTerminalWidget}: a handler
 * added through {@link JBTerminalPanel#addPreKeyEventHandler} sees the paste keys before the terminal, consuming
 * the key suppresses the terminal's own paste and control character, and the bracketed paste mode can be read
 * from {@link JediTerminal}; inside a tool window other than Terminal the platform takes `Escape` for moving the focus
 * to the editor, unless {@link EscapeKeyHandler} sends it to the program first. Run it against newer IDEs with
 * {@code -PplatformVersion=<version>}.
 *
 * <p>Key events are fed to {@link JBTerminalPanel#handleKeyEvent}; the panel's {@code IdeEventQueue} dispatcher,
 * which hands keys to the panel before the IDE keymap, needs a focused window and is not covered here. The tool window
 * context needs the production data manager ({@link HeadlessDataManager#fallbackToProductionDataManager}), as the
 * headless one does not read the context of a component.</p>
 */
public class TerminalKeyDispatchTest extends BasePlatformTestCase {

    private static final String BRACKETED_PASTE_ON = "\u001b[?2004h";
    private static final String BRACKETED_PASTE_OFF = "\u001b[?2004l";

    private RecordingConnector connector;
    private JBTerminalWidget widget;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.widget = new JBTerminalWidget(getProject(), new JBTerminalSystemSettingsProvider(), getTestRootDisposable());
        this.connector = new RecordingConnector();
        this.widget.start(this.connector);
        // jediterm adds its own key handler right before it starts reading the connector
        assertTrue("the emulator did not start", this.connector.reading.await(20, TimeUnit.SECONDS));
        output(BRACKETED_PASTE_ON);
        waitOnEdt(this::bracketedPasteMode, "bracketed paste mode on");
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (this.connector != null) {
                this.connector.close();
            }
        } finally {
            super.tearDown();
        }
    }

    public void testKeyNotConsumedByHandlerReachesTheTerminal() {
        final int[] seen = new int[1];
        panel().addPreKeyEventHandler(event -> seen[0]++);

        press(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0));

        waitOnEdt(() -> !this.connector.written().isEmpty(), "the key to reach the terminal");
        assertEquals("pressed and typed", 2, seen[0]);
        assertEquals("a", this.connector.written());
    }

    public void testPasteKeyNotConsumedByHandlerRunsTheTerminalPaste() {
        // the terminal's own paste reads the AWT system clipboard, which a headless JVM does not have: the
        // exception is the proof that the key reached the terminal's Paste action (Gradle runs tests headless)
        assertTrue("needs a headless JVM", GraphicsEnvironment.isHeadless());
        assertThrows(HeadlessException.class, () -> press(pasteKey()));
        assertEquals("", this.connector.written());
    }

    public void testPreKeyHandlerConsumingPasteKeySuppressesTerminalPaste() {
        CopyPasteManager.getInstance().setContents(new StringSelection("hello"));
        final KeyStroke paste = pasteKey();
        final int[] seen = new int[1];
        panel().addPreKeyEventHandler(event -> {
            if (event.getKeyCode() == paste.getKeyCode() || event.getKeyChar() == 'v' || event.getKeyChar() == 0x16) {
                seen[0]++;
                event.consume();
            }
        });

        press(paste);
        press(ctrlV());

        settle();
        assertEquals("pressed and typed events of both keys", 4, seen[0]);
        assertEquals("", this.connector.written());
    }

    public void testEscapeInToolWindowIsTakenByThePlatform() {
        placeInToolWindow();

        press(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));

        settle();
        assertEquals("", this.connector.written());
    }

    public void testEscapeKeyHandlerSendsEscapeInToolWindow() {
        placeInToolWindow();
        new EscapeKeyHandler(this.widget).install();

        press(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        waitOnEdt(() -> !this.connector.written().isEmpty(), "the escape to reach the terminal");
        press(KeyStroke.getKeyStroke(KeyEvent.VK_A, 0));

        waitOnEdt(() -> this.connector.written().endsWith("a"), "the next key to reach the terminal");
        settle();
        assertEquals("\u001ba", this.connector.written());
    }

    public void testEscapeKeyHandlerLeavesModifiedEscapeAlone() {
        final int[] consumed = new int[1];
        new EscapeKeyHandler(this.widget).install();
        panel().addPreKeyEventHandler(event -> {
            if (event.isConsumed()) {
                consumed[0]++;
            }
        });

        press(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, InputEvent.SHIFT_DOWN_MASK));

        assertEquals(0, consumed[0]);
    }

    public void testBracketedPasteModeFollowsTheProgram() {
        assertTrue(bracketedPasteMode());

        output(BRACKETED_PASTE_OFF);

        waitOnEdt(() -> !bracketedPasteMode(), "bracketed paste mode off");
    }

    /**
     * Puts the terminal into a tool window as the Hole tool window does: its data context names the tool window. The
     * headless tool window manager overwrites a registration of the same id, so nothing is unregistered.
     */
    private void placeInToolWindow() {
        // the headless data manager does not read the context of a component
        HeadlessDataManager.fallbackToProductionDataManager(getTestRootDisposable());
        final ToolWindow toolWindow = ToolWindowManager.getInstance(getProject())
            .registerToolWindow(RegisterToolWindowTask.notClosable("Hole", ToolWindowAnchor.BOTTOM));
        final JPanel content = new ToolWindowContent(toolWindow);
        content.add(this.widget.getComponent(), BorderLayout.CENTER);
    }

    @NotNull
    private JBTerminalPanel panel() {
        return this.widget.getTerminalPanel();
    }

    private boolean bracketedPasteMode() {
        return this.widget.getTerminal() instanceof JediTerminal terminal
            && terminal.isModelEnabled(TerminalMode.BracketedPasteMode);
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
     * Feeds the pressed and the typed event of a key stroke to the panel, as AWT delivers them.
     */
    private void press(@NotNull KeyStroke stroke) {
        final int modifiers = stroke.getModifiers();
        final char typed = (modifiers & InputEvent.CTRL_DOWN_MASK) != 0 && (modifiers & InputEvent.META_DOWN_MASK) == 0
            ? (char) (stroke.getKeyCode() - KeyEvent.VK_A + 1)
            : Character.toLowerCase((char) stroke.getKeyCode());
        final long now = System.currentTimeMillis();
        panel().handleKeyEvent(new KeyEvent(panel(), KeyEvent.KEY_PRESSED, now, modifiers, stroke.getKeyCode(), typed));
        panel().handleKeyEvent(new KeyEvent(panel(), KeyEvent.KEY_TYPED, now, modifiers, KeyEvent.VK_UNDEFINED, typed));
    }

    private void output(@NotNull String text) {
        this.connector.output.add(text);
    }

    /**
     * Gives an asynchronous write the chance to happen before asserting that none did.
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

    private static final class ToolWindowContent extends JPanel implements UiDataProvider {

        private final ToolWindow toolWindow;

        ToolWindowContent(@NotNull ToolWindow toolWindow) {
            super(new BorderLayout());
            this.toolWindow = toolWindow;
        }

        @Override
        public void uiDataSnapshot(@NotNull DataSink sink) {
            sink.set(PlatformDataKeys.TOOL_WINDOW, this.toolWindow);
            sink.set(CommonDataKeys.PROJECT, this.toolWindow.getProject());
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
        public int read(char[] buf, int offset, int length) throws java.io.IOException {
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
