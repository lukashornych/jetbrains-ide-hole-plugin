package io.github.lukashornych.hole.idea.ui.settings;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsEntries;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import io.github.lukashornych.hole.idea.model.NetworkSetting;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Settings files are changed through their IDE documents.
 */
public class SettingsDocumentsTest extends BasePlatformTestCase {

    private Path temp;
    private Path global;
    private SettingsDocuments documents;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.temp = Files.createTempDirectory("hole-documents").toRealPath();
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake"));
        this.global = this.temp.resolve("config/hole/settings.json");
        hole.environment().put("HOLE_SETTINGS_FILE", this.global.toString());
        this.documents = new SettingsDocuments(getProject(), new SettingsFileResolver(
            new HoleSettingsClient(hole.executable().toString(), new CliRunner(hole::environment))
        ), Files.createDirectories(this.temp.resolve("project")));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            NioFiles.deleteRecursively(this.temp);
        } finally {
            super.tearDown();
        }
    }

    public void testUnsavedEditorChangesAreKept() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{}");
        final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(this.global);
        assertNotNull(file);
        final Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("{\"container\": {\"memory\": 4}}"));

        assertNull(this.documents.write(file, text -> SettingsEntries.withEntries(text, NetworkSetting.ALLOW, List.of("a.example.com"))));

        final String saved = Files.readString(this.global);
        assertTrue(saved, saved.contains("\"memory\": 4"));
        assertTrue(saved, saved.contains("\"a.example.com\""));
    }

    public void testRefusedChangeLeavesTheFileAlone() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{\"network\": true}");
        final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(this.global);
        assertNotNull(file);
        final String failure = this.documents.write(file, text -> {
            throw new IllegalArgumentException("refused");
        });
        assertNotNull(failure);
        assertTrue(failure, failure.endsWith("was not changed: refused"));
        assertEquals("{\"network\": true}", Files.readString(this.global));
    }
}
