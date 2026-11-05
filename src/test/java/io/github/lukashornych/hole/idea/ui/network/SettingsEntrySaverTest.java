package io.github.lukashornych.hole.idea.ui.network;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * Saving entries from the Network window to a settings file named by the fake `hole`.
 */
public class SettingsEntrySaverTest extends BasePlatformTestCase {

    private Path temp;
    private Path global;
    private Path projectDir;
    private SettingsEntrySaver saver;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.temp = Files.createTempDirectory("hole-saver").toRealPath();
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake"));
        this.global = this.temp.resolve("config/hole/settings.json");
        this.projectDir = Files.createDirectories(this.temp.resolve("project"));
        hole.environment().put("HOLE_SETTINGS_FILE", this.global.toString());
        this.saver = new SettingsEntrySaver(getProject(), new SettingsFileResolver(
            new HoleSettingsClient(hole.executable().toString(), new CliRunner(hole::environment))
        ), this.projectDir);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            NioFiles.deleteRecursively(this.temp);
        } finally {
            super.tearDown();
        }
    }

    public void testMissingGlobalFileIsCreatedAndGetsTheEntry() throws Exception {
        assertEquals(Optional.empty(), save(HoleSettingsClient.Scope.GLOBAL, NetworkSetting.ALLOW, List.of("api.example.com:443,80")));
        final String saved = Files.readString(this.global);
        assertTrue(saved, saved.contains("\"$schema\""));
        assertTrue(saved, saved.contains("\"allow\": [\n      \"api.example.com:443,80\"\n    ]"));
    }

    public void testProjectFileOfTheSandboxGetsTheEntry() throws Exception {
        final Path file = this.projectDir.resolve(".hole/settings.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"network\": {\"hostGatewayDomains\": [\"a.example.com\"]}}");
        assertEquals(Optional.empty(), save(HoleSettingsClient.Scope.PROJECT, NetworkSetting.HOST_GATEWAY_DOMAINS, List.of("b.example.com")));
        assertTrue(Files.readString(file).contains("\"a.example.com\",\n      \"b.example.com\""));
    }

    public void testInvalidFileIsLeftAloneWithAReason() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{\"network\": true}");
        final Optional<String> failure = save(HoleSettingsClient.Scope.GLOBAL, NetworkSetting.ALLOW, List.of("a.example.com"));
        assertTrue(failure.toString(), failure.orElseThrow().endsWith("was not changed: `network` is not an object"));
        assertEquals("{\"network\": true}", Files.readString(this.global));
    }

    public void testUnsavedEditorChangesAreKept() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{}");
        final VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(this.global);
        assertNotNull(file);
        final Document document = FileDocumentManager.getInstance().getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("{\"container\": {\"memory\": 4}}"));

        assertNull(this.saver.write(file, text -> SettingsEntries.withEntries(text, NetworkSetting.ALLOW, List.of("a.example.com"))));

        final String saved = Files.readString(this.global);
        assertTrue(saved, saved.contains("\"memory\": 4"));
        assertTrue(saved, saved.contains("\"a.example.com\""));
    }

    /**
     * Regression: the Add input is modal, and a save waiting for the EDT in the non-modal state only ran after the
     * input was closed by hand, so the input never closed by itself.
     */
    public void testSaveFinishesWhileTheModalInputIsOpen() throws Exception {
        final Object input = new Object();
        LaterInvocator.enterModal(input);
        try {
            final ModalityState modality = ModalityState.current();
            final Future<Optional<String>> future = ApplicationManager.getApplication().executeOnPooledThread(
                () -> this.saver.save(HoleSettingsClient.Scope.GLOBAL, NetworkSetting.ALLOW, List.of("a.example.com"), modality));
            assertEquals(Optional.empty(), PlatformTestUtil.waitForFuture(future, 30_000));
            assertTrue(Files.readString(this.global).contains("\"a.example.com\""));
        } finally {
            LaterInvocator.leaveModal(input);
        }
    }

    public void testSavedEntriesOfBothFilesAndRemoval() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{\"network\": {\"allow\": [\"A.example.com\", \"b.example.com:5432\"]}}");
        final Path project = this.projectDir.resolve(".hole/settings.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, "{\"network\": {\"allow\": [\"a.example.com:443,80\"]}}");

        final Map<HoleSettingsClient.Scope, Set<String>> saved = run(() -> this.saver.savedEntries(NetworkSetting.ALLOW));
        assertEquals(Set.of("a.example.com:80,443", "b.example.com:5432"), saved.get(HoleSettingsClient.Scope.GLOBAL));
        assertEquals(Set.of("a.example.com:80,443"), saved.get(HoleSettingsClient.Scope.PROJECT));

        assertEquals(Optional.empty(), run(() -> this.saver.remove(
            HoleSettingsClient.Scope.GLOBAL, NetworkSetting.ALLOW, "a.example.com:80,443", ModalityState.nonModal())));
        assertFalse(Files.readString(this.global).contains("A.example.com"));
        assertTrue(Files.readString(this.global).contains("b.example.com:5432"));
    }

    public void testRemovalFromAMissingFileChangesNothing() throws Exception {
        assertEquals(Optional.empty(), run(() -> this.saver.remove(
            HoleSettingsClient.Scope.GLOBAL, NetworkSetting.ALLOW, "a.example.com:80,443", ModalityState.nonModal())));
        assertFalse(Files.exists(this.global));
    }

    private Optional<String> save(HoleSettingsClient.Scope scope, NetworkSetting setting, List<String> values) throws Exception {
        return run(() -> this.saver.save(scope, setting, values, ModalityState.nonModal()));
    }

    private <T> T run(Callable<T> call) throws Exception {
        final Future<T> future = ApplicationManager.getApplication().executeOnPooledThread(call);
        return PlatformTestUtil.waitForFuture(future, 30_000);
    }
}
