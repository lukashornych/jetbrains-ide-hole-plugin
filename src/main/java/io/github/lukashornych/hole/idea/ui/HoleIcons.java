package io.github.lukashornych.hole.idea.ui;

import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.RowIcon;
import io.github.lukashornych.hole.idea.session.SessionState;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Icons of the plugin; each SVG has a `_dark` variant.
 */
public final class HoleIcons {

    /** Tool window stripe icon. */
    public static final Icon TOOL_WINDOW = load("holeToolWindow");
    /** Amber status dot. */
    public static final Icon STARTING = load("statusStarting");
    /** Green status dot. */
    public static final Icon RUNNING = load("statusRunning");
    /** Red status dot. */
    public static final Icon STOPPING = load("statusStopping");
    /** Grey status dot of an exited sandbox. */
    public static final Icon EXITED = load("statusExited");
    /** Bug of debug shell runs. */
    public static final Icon DEBUG = load("debug");

    private HoleIcons() {
    }

    /**
     * Returns the tab icon: the status dot, followed by the bug for a debug run.
     */
    @NotNull
    public static Icon tabIcon(@NotNull SessionState state, boolean debug) {
        final Icon dot = switch (state) {
            case STARTING -> STARTING;
            case RUNNING -> RUNNING;
            case STOPPING -> STOPPING;
            case EXITED, CLOSED -> EXITED;
        };
        if (!debug) {
            return dot;
        }
        return new RowIcon(dot, DEBUG);
    }

    @NotNull
    private static Icon load(@NotNull String name) {
        return IconLoader.getIcon("/icons/" + name + ".svg", HoleIcons.class);
    }
}
