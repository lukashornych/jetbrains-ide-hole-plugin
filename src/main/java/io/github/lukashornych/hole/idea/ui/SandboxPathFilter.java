package io.github.lukashornych.hole.idea.ui;

import com.intellij.execution.filters.AbstractFileHyperlinkFilter;
import com.intellij.execution.filters.FileHyperlinkRawData;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private final Path projectDirectory;

    /**
     * @param project          project whose editor opens the files
     * @param projectDirectory sandbox working directory, against which relative paths are resolved
     */
    SandboxPathFilter(@NotNull Project project, @NotNull Path projectDirectory) {
        // no base directory: parse() returns absolute paths only
        super(project, (VirtualFile) null);
        this.projectDirectory = projectDirectory;
    }

    @NotNull
    @Override
    public List<FileHyperlinkRawData> parse(@NotNull String line) {
        final List<FileHyperlinkRawData> links = new ArrayList<>();
        if (line.length() > MAX_LINE_LENGTH) {
            return links;
        }
        final Matcher matcher = PATH.matcher(line);
        while (matcher.find()) {
            final String path = matcher.group(1);
            // a bare word is not worth a file lookup
            if (path.indexOf('/') < 0 && path.indexOf('.') < 0 || NUMBER.matcher(path).matches()) {
                continue;
            }
            final String absolute;
            try {
                absolute = this.projectDirectory.resolve(path).normalize().toString();
            } catch (InvalidPathException e) {
                // not encodable in the file system's charset, so no such file
                continue;
            }
            links.add(new FileHyperlinkRawData(
                absolute, toZeroBased(matcher.group(2)), toZeroBased(matcher.group(3)), matcher.start(), matcher.end()
            ));
        }
        return links;
    }

    /**
     * Links files the VFS does not know yet too: the agent prints paths of files it has just created.
     */
    @Override
    protected boolean supportVfsRefresh() {
        return true;
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
}
