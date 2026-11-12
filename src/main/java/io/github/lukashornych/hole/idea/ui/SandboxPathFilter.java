package io.github.lukashornych.hole.idea.ui;

import com.intellij.execution.filters.AbstractFileHyperlinkFilter;
import com.intellij.execution.filters.FileHyperlinkRawData;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns file paths printed in a sandbox tab into links that open the file in the editor, at the line and column of
 * a `path:line` or `path:line:column` suffix.
 *
 * <p>The Terminal plugin hands its own path filter only to its reworked terminal (the filter requires the plugin's
 * filter scope), so the plain {@link com.intellij.terminal.JBTerminalWidget} of a sandbox tab brings its own. Hole
 * mounts the project, libraries and worktrees at their host paths, so an absolute path printed in the sandbox exists
 * on the host as it is; a relative path is resolved against the project directory, the sandbox's working directory.
 * Every path-like token of a line is a candidate; {@link AbstractFileHyperlinkFilter} links only those that exist.
 * Relative paths are made absolute here rather than through the base class's base directory, which is looked up
 * once, without a refresh, and so is missing for a project directory the VFS has not seen yet.</p>
 *
 * <p>Claude Code wraps its output itself, so a long path breaks mid-word into two real lines and jediterm, which joins
 * only soft-wrapped lines, filters each part alone. A path token that ends the line at the right edge is therefore
 * remembered as a fragment, and the first token of a following line is linked to the fragment joined with it when
 * that file exists. The cells of a table drawn with box characters wrap the same way inside the cell, so each cell
 * is treated as a line of its own, its right border being the edge, and a fragment continues in the cell of the same
 * position on a following table line. This relies on the platform running the filters of one terminal sequentially,
 * in output order.</p>
 */
final class SandboxPathFilter extends AbstractFileHyperlinkFilter implements DumbAware {

    /**
     * A path (optionally absolute or starting with `./` or `../`) not preceded by a path character, so a token is
     * never split and neither the host part of a URL nor a `~/` or `$HOME/` path is taken for a path, then an
     * optional `:line` and `:column`. The last character is never a dot, so a path that ends a sentence keeps out the
     * full stop. `\\w` covers non-ASCII letters, so a segment like `čeština` is not split.
     */
    private static final Pattern PATH = Pattern.compile(
        "(?<![\\w.@+/~$-])((?:/|\\.\\.?/)?(?:[\\w.@+-]+/)*[\\w.@+-]*[\\w@+-])(?::(\\d+)(?::(\\d+))?)?",
        Pattern.UNICODE_CHARACTER_CLASS
    );
    /** A version, IP address or number, never a file name worth a lookup. */
    private static final Pattern NUMBER = Pattern.compile("[\\d.]+");
    /** Longer lines are not real terminal output lines; the path pattern would recurse too deep on them. */
    private static final int MAX_LINE_LENGTH = 4_096;
    /** How far from the right edge a line may end and still count as wrapped (the agent's right margin, padding). */
    private static final int EDGE_SLACK = 8;
    /** How many further lines may come before the line continuing a fragment (redraws reorder lines a little). */
    private static final int FRAGMENT_LIFETIME = 2;
    /** The spaces the agent leaves between a cell's text and its right border. */
    private static final int CELL_PADDING = 1;
    /** How far from the padding of its right border a cell's text may end and still count as wrapped. */
    private static final int CELL_SLACK = 1;
    /** The vertical border between table cells. */
    private static final char CELL_BORDER = '│';
    /** The corners and joints of a horizontal rule, which ends a table row. */
    private static final String TABLE_RULE = "┌┐├┤└┘┬┼┴";
    /** The key of the whole line, as opposed to the cells of a table line, numbered from 0. */
    private static final int LINE = -1;

    private final Path projectDirectory;
    private final IntSupplier columns;
    /** The path tokens that ended the last wrapped lines or cells, as printed, by the key of their box. */
    private final Map<Integer, Fragment> fragments = new HashMap<>();

    /**
     * @param project          project whose editor opens the files
     * @param projectDirectory sandbox working directory, against which relative paths are resolved
     * @param columns          current terminal width in columns, 0 when unknown
     */
    SandboxPathFilter(@NotNull Project project, @NotNull Path projectDirectory, @NotNull IntSupplier columns) {
        // no base directory: parse() returns absolute paths only
        super(project, (VirtualFile) null);
        this.projectDirectory = projectDirectory;
        this.columns = columns;
    }

    @NotNull
    @Override
    public synchronized List<FileHyperlinkRawData> parse(@NotNull String line) {
        final List<FileHyperlinkRawData> links = new ArrayList<>();
        if (line.length() > MAX_LINE_LENGTH) {
            return links;
        }
        final String text = line.stripTrailing();
        final List<Box> boxes = boxes(text);
        if (boxes.isEmpty()) {
            // a table rule: a new table row starts, cells of the previous one cannot continue
            this.fragments.keySet().removeIf(key -> key != LINE);
        }
        final Map<Integer, Fragment> nextFragments = new HashMap<>();
        final Matcher matcher = PATH.matcher(text);
        while (matcher.find()) {
            final String path = matcher.group(1);
            final Box box = boxOf(boxes, matcher.start());
            if (box != null) {
                // only the first token of a box continues the fragment of the box before it
                final Fragment pending = text.substring(box.start(), matcher.start()).isBlank()
                    ? this.fragments.get(box.key())
                    : null;
                final Path joined = pending == null ? null : existingFile(pending.text + path);
                if (joined != null) {
                    links.add(link(joined, matcher));
                    this.fragments.remove(box.key());
                    continue;
                }
                final String fragment = fragmentEndingAt(text, box, matcher, pending);
                if (fragment != null) {
                    nextFragments.put(box.key(), new Fragment(fragment));
                }
            }
            // a bare word is not worth a file lookup
            if (path.indexOf('/') < 0 && path.indexOf('.') < 0 || NUMBER.matcher(path).matches()) {
                continue;
            }
            final Path absolute = resolve(path);
            if (absolute != null) {
                links.add(link(absolute, matcher));
            }
        }
        this.fragments.values().removeIf(fragment -> ++fragment.age > FRAGMENT_LIFETIME);
        this.fragments.putAll(nextFragments);
        return links;
    }

    /**
     * Links files the VFS does not know yet too: the agent prints paths of files it has just created.
     */
    @Override
    protected boolean supportVfsRefresh() {
        return true;
    }

    /**
     * Returns the cells of a table line, the whole line when it is no table line, or nothing for a table rule.
     */
    private static List<Box> boxes(String text) {
        final List<Box> cells = new ArrayList<>();
        int left = text.indexOf(CELL_BORDER);
        while (left >= 0) {
            final int right = text.indexOf(CELL_BORDER, left + 1);
            if (right < 0) {
                break;
            }
            cells.add(new Box(cells.size(), left + 1, right));
            left = right;
        }
        if (!cells.isEmpty()) {
            return cells;
        }
        return isTableRule(text) ? List.of() : List.of(new Box(LINE, 0, text.length()));
    }

    /**
     * Whether the line is a horizontal rule of a table, not text drawn with the same characters such as a tree.
     */
    private static boolean isTableRule(String text) {
        boolean joint = false;
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
            joint |= TABLE_RULE.indexOf(c) >= 0;
        }
        return joint;
    }

    private static Box boxOf(List<Box> boxes, int offset) {
        for (Box box : boxes) {
            if (offset >= box.start() && offset < box.end()) {
                return box;
            }
        }
        return null;
    }

    /**
     * Returns the path token the matcher stands at, as printed and after the fragment it continues, when it ends
     * the box at its right edge and so may continue in a following box; {@code null} otherwise.
     */
    private String fragmentEndingAt(String text, Box box, Matcher matcher, Fragment pending) {
        final int textEnd = textEnd(text, box);
        // a line number ends the path
        if (matcher.group(2) != null || !endsAtEdge(text, matcher.end(), textEnd, box)) {
            return null;
        }
        // a token continuing a fragment may be the middle part of a path wrapped over more than two lines
        final String prefix = pending == null ? "" : pending.text;
        final String fragment = prefix + text.substring(matcher.start(1), textEnd);
        // the last word of wrapped prose is no fragment worth a file lookup
        return fragment.indexOf('/') >= 0 ? fragment : null;
    }

    /**
     * Returns the end of the box's text, without the padding before its border.
     */
    private static int textEnd(String text, Box box) {
        int end = box.end();
        while (end > box.start() && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    /**
     * Whether a path token ending at {@code end} ends the box's text close to its right edge; the text may break
     * after the token's last character the pattern leaves out, a dot or a slash.
     */
    private boolean endsAtEdge(String text, int end, int textEnd, Box box) {
        for (int i = end; i < textEnd; i++) {
            final char c = text.charAt(i);
            if (c != '.' && c != '/') {
                return false;
            }
        }
        if (box.cell()) {
            return textEnd >= box.end() - CELL_PADDING - CELL_SLACK;
        }
        final int width = this.columns.getAsInt();
        if (width <= 0) {
            return false;
        }
        // the text of soft-wrapped lines is joined, so the column is the one in the last screen line
        final int column = (textEnd - 1) % width + 1;
        return column >= width - EDGE_SLACK;
    }

    private static FileHyperlinkRawData link(Path file, Matcher matcher) {
        return new FileHyperlinkRawData(
            file.toString(), toZeroBased(matcher.group(2)), toZeroBased(matcher.group(3)), matcher.start(),
            matcher.end()
        );
    }

    private Path existingFile(String path) {
        final Path file = resolve(path);
        return file != null && Files.isRegularFile(file) ? file : null;
    }

    private Path resolve(String path) {
        try {
            return this.projectDirectory.resolve(path).normalize();
        } catch (InvalidPathException e) {
            // not encodable in the file system's charset, so no such file
            return null;
        }
    }

    private static int toZeroBased(String number) {
        if (number == null) {
            return -1;
        }
        try {
            return Math.max(Integer.parseInt(number) - 1, 0);
        } catch (NumberFormatException e) {
            // too many digits to be a line or column
            return -1;
        }
    }

    /**
     * A part of a line that wraps on its own: the whole line, or a table cell between its borders.
     *
     * @param key   {@link #LINE}, or the position of the cell in the table line
     * @param start offset of the first character
     * @param end   offset after the last character, the right border of a cell
     */
    private record Box(int key, int start, int end) {

        boolean cell() {
            return this.key != LINE;
        }
    }

    /**
     * The beginning of a wrapped path, and the number of lines filtered since it was printed.
     */
    private static final class Fragment {

        private final String text;
        private int age;

        private Fragment(String text) {
            this.text = text;
        }
    }
}
