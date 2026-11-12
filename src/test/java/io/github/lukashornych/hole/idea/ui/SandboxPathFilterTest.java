package io.github.lukashornych.hole.idea.ui;

import com.intellij.execution.filters.FileHyperlinkInfo;
import com.intellij.execution.filters.Filter;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File paths in sandbox output become links to existing files (regression: the sandbox tab linked no paths, unlike
 * the IDE's Terminal).
 */
public class SandboxPathFilterTest extends BasePlatformTestCase {

    private static final int COLUMNS = 60;

    private Path projectDir;
    private Path otherDir;
    private SandboxPathFilter filter;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.projectDir = Files.createTempDirectory("acme-shop").toRealPath();
        this.otherDir = Files.createTempDirectory("acme-lib").toRealPath();
        createFile(this.projectDir.resolve("src/main/Shop.java"));
        createFile(this.projectDir.resolve("README.md"));
        createFile(this.otherDir.resolve("lib/Cart.java"));
        VfsUtil.markDirtyAndRefresh(false, true, true, this.projectDir.toFile(), this.otherDir.toFile());
        this.filter = new SandboxPathFilter(getProject(), this.projectDir, () -> COLUMNS);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            NioFiles.deleteRecursively(this.projectDir);
            NioFiles.deleteRecursively(this.otherDir);
        } finally {
            super.tearDown();
        }
    }

    public void testRelativePathWithLineAndColumn() {
        final String line = "Edited src/main/Shop.java:12:5 and done.";
        final Filter.ResultItem item = single(line);

        assertEquals("src/main/Shop.java:12:5", line.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset()));
        final OpenFileDescriptor descriptor = descriptor(item);
        assertEquals(this.projectDir.resolve("src/main/Shop.java").toString(), descriptor.getFile().getPath());
        assertEquals("11:4", position(descriptor));
    }

    public void testAbsolutePathOutsideProjectAndDotRelativePath() {
        final String absolute = this.otherDir.resolve("lib/Cart.java").toString();
        final String line = "Compare " + absolute + ":3 with ./README.md.";
        final List<Filter.ResultItem> items = items(line);

        assertEquals(2, items.size());
        assertEquals(absolute + ":3", text(line, items.get(0)));
        assertEquals("2:0", position(descriptor(items.get(0))));
        assertEquals("./README.md", text(line, items.get(1)));
        assertEquals(this.projectDir.resolve("README.md").toString(), descriptor(items.get(1)).getFile().getPath());
    }

    public void testFileCreatedAfterTheVfsScanIsLinked() throws IOException {
        createFile(this.projectDir.resolve("src/main/Order.java"));
        final String line = "Created src/main/Order.java";

        assertEquals("src/main/Order.java", text(line, single(line)));
    }

    public void testRelativePathInProjectDirectoryUnknownToTheVfsIsLinked() throws IOException {
        final Path unseen = this.otherDir.resolve("unseen");
        createFile(unseen.resolve("src/Invoice.java"));
        assertNull(LocalFileSystem.getInstance().findFileByNioFile(unseen));
        final SandboxPathFilter unseenFilter = new SandboxPathFilter(getProject(), unseen, () -> COLUMNS);
        final String line = "Edited src/Invoice.java:2";

        final Filter.Result result = unseenFilter.applyFilter(line, line.length());
        assertNotNull(result);
        assertEquals("src/Invoice.java:2", text(line, result.getResultItems().get(0)));
    }

    public void testNonAsciiPathIsLinkedWhole() throws IOException {
        createFile(this.projectDir.resolve("src/čeština/Šablona.java"));
        createFile(this.projectDir.resolve("src/čeština/ablona.java"));
        VfsUtil.markDirtyAndRefresh(false, true, true, this.projectDir.toFile());
        final String line = "See src/čeština/Šablona.java:3";

        final Filter.ResultItem item = single(line);
        assertEquals("src/čeština/Šablona.java:3", text(line, item));
        assertEquals(this.projectDir.resolve("src/čeština/Šablona.java").toString(), descriptor(item).getFile().getPath());
    }

    public void testHomeRelativePathIsNotTakenForAnAbsoluteOne() throws IOException {
        createFile(this.otherDir.resolve("lib/Tax.java"));
        VfsUtil.markDirtyAndRefresh(false, true, true, this.otherDir.toFile());
        final String tail = this.otherDir.resolve("lib/Tax.java").toString();

        assertNull(this.filter.applyFilter("~" + tail, tail.length() + 1));
        assertNull(this.filter.applyFilter("$HOME" + tail, tail.length() + 5));
    }

    public void testOffsetsAreRelativeToTheWholeOutput() {
        final String line = "see README.md";
        final Filter.Result result = this.filter.applyFilter(line, 100 + line.length());

        assertNotNull(result);
        assertEquals(104, result.getResultItems().get(0).getHighlightStartOffset());
        assertEquals(113, result.getResultItems().get(0).getHighlightEndOffset());
    }

    public void testTextThatIsNotAnExistingPathIsNotLinked() {
        assertNull(this.filter.applyFilter("Hole 2.6.0 started at 12:30, e.g. src/main/Missing.java\n", 57));
        assertNull(this.filter.applyFilter("Open https://example.com/src/main/Shop.java\n", 45));
        assertNull(this.filter.applyFilter("Done. All src tests passed\n", 27));
    }

    public void testPathHardWrappedByTheAgentLinksItsContinuation() throws IOException {
        createFile(this.projectDir.resolve("src/main/PublishedCampaignService.java"));
        final String first = edgeLine("  - src/main/PublishedCampaignSer");
        final String second = "    vice.java:3 (published)";

        assertNull(this.filter.applyFilter(first, first.length()));
        final Filter.ResultItem item = single(second);
        assertEquals("vice.java:3", text(second, item));
        assertEquals(
            this.projectDir.resolve("src/main/PublishedCampaignService.java").toString(),
            descriptor(item).getFile().getPath()
        );
        assertEquals("2:0", position(descriptor(item)));
    }

    public void testPathHardWrappedOverThreeLinesLinksTheLastPart() throws IOException {
        createFile(this.projectDir.resolve("src/main/campaign/service/PublishedCampaignService.java"));
        final String first = edgeLine("  - src/main/camp");
        final String middle = edgeLine("    aign/service/Publish");
        final String last = "    edCampaignService.java";

        assertNull(this.filter.applyFilter(first, first.length()));
        assertNull(this.filter.applyFilter(middle, middle.length()));
        assertEquals("edCampaignService.java", text(last, single(last)));
    }

    public void testPathHardWrappedAfterASlashLinksItsContinuation() throws IOException {
        createFile(this.projectDir.resolve("src/main/campaign/PublishedCampaignService.java"));
        final String first = edgeLine("  - src/main/campaign/");
        final String second = "    PublishedCampaignService.java";

        // the directory itself is linked as any other existing path
        this.filter.applyFilter(first, first.length());
        assertEquals("PublishedCampaignService.java", text(second, single(second)));
    }

    public void testTreeLineIsNoTableRule() throws IOException {
        createFile(this.projectDir.resolve("src/main/PublishedCampaignService.java"));
        final String first = edgeLine("├── src/main/PublishedCampaignSer");
        final String second = "    vice.java";

        assertNull(this.filter.applyFilter(first, first.length()));
        assertEquals("vice.java", text(second, single(second)));
    }

    public void testLineEndingBeforeTheRightEdgeIsNotContinued() throws IOException {
        createFile(this.projectDir.resolve("src/main/PublishedCampaignService.java"));
        final String first = "  - src/main/PublishedCampaignSer";

        assertNull(this.filter.applyFilter(first, first.length()));
        assertNull(this.filter.applyFilter("    vice.java", 13));
    }

    public void testFragmentExpiresAfterUnrelatedLines() throws IOException {
        createFile(this.projectDir.resolve("src/main/PublishedCampaignService.java"));
        final String first = edgeLine("  - src/main/PublishedCampaignSer");

        assertNull(this.filter.applyFilter(first, first.length()));
        assertNull(this.filter.applyFilter("unrelated", 9));
        assertNull(this.filter.applyFilter("unrelated", 9));
        assertNull(this.filter.applyFilter("unrelated", 9));
        assertNull(this.filter.applyFilter("    vice.java", 13));
    }

    public void testPathWrappedInTableCellLinksItsContinuation() throws IOException {
        createFile(this.projectDir.resolve("src/main/acme/FunctionScoreBenchmark.java"));
        final String first = tableRow("1", "2025-01", "a1b2c3d4", "src/main/acme/FunctionSco");
        final String second = tableRow("", "-15", "e5f", "reBenchmark.java:7");

        assertNull(this.filter.applyFilter(first, first.length()));
        final Filter.ResultItem item = single(second);
        assertEquals("reBenchmark.java:7", text(second, item));
        assertEquals(
            this.projectDir.resolve("src/main/acme/FunctionScoreBenchmark.java").toString(),
            descriptor(item).getFile().getPath()
        );
        assertEquals("6:0", position(descriptor(item)));
    }

    public void testTableRuleEndsTheWrappedCells() throws IOException {
        createFile(this.projectDir.resolve("src/main/acme/FunctionScoreBenchmark.java"));
        final String first = tableRow("1", "2025-01", "a1b2c3d4", "src/main/acme/FunctionSco");
        final String rule = "  ├─────┼─────────┼──────────┼───────────────────────────┤";
        final String second = tableRow("", "-15", "e5f", "reBenchmark.java");

        assertNull(this.filter.applyFilter(first, first.length()));
        assertNull(this.filter.applyFilter(rule, rule.length()));
        assertNull(this.filter.applyFilter(second, second.length()));
    }

    /**
     * Draws a table line the way the agent does, the last cell 25 characters wide.
     */
    private static String tableRow(String number, String date, String commit, String path) {
        return "  │ %-3s │ %-7s │ %-8s │ %-25s │".formatted(number, date, commit, path);
    }

    /**
     * Pads the text on the left so that it ends in the last column, the way the agent breaks a long path.
     */
    private static String edgeLine(String text) {
        return " ".repeat(COLUMNS - text.length()) + text;
    }

    private Filter.ResultItem single(String line) {
        final List<Filter.ResultItem> items = items(line);
        assertEquals(1, items.size());
        return items.get(0);
    }

    private List<Filter.ResultItem> items(String line) {
        final Filter.Result result = this.filter.applyFilter(line, line.length());
        assertNotNull("no link in: " + line, result);
        return result.getResultItems();
    }

    private static String text(String line, Filter.ResultItem item) {
        return line.substring(item.getHighlightStartOffset(), item.getHighlightEndOffset());
    }

    private static OpenFileDescriptor descriptor(Filter.ResultItem item) {
        assertInstanceOf(item.getHyperlinkInfo(), FileHyperlinkInfo.class);
        return ((FileHyperlinkInfo) item.getHyperlinkInfo()).getDescriptor();
    }

    /**
     * Returns the zero-based `line:column` the descriptor navigates to; a loaded document turns it into an offset.
     */
    private static String position(OpenFileDescriptor descriptor) {
        if (descriptor.getLine() >= 0) {
            return descriptor.getLine() + ":" + Math.max(descriptor.getColumn(), 0);
        }
        final Document document = FileDocumentManager.getInstance().getDocument(descriptor.getFile());
        final int line = document.getLineNumber(descriptor.getOffset());
        return line + ":" + (descriptor.getOffset() - document.getLineStartOffset(line));
    }

    private static void createFile(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line\n".repeat(20));
    }
}
