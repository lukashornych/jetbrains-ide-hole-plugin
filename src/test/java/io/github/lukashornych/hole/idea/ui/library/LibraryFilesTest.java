package io.github.lukashornych.hole.idea.ui.library;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.openapi.util.io.NioFiles;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.github.lukashornych.hole.idea.hole.HoleSettingsClient;
import io.github.lukashornych.hole.idea.hole.SettingsFileResolver;
import io.github.lukashornych.hole.idea.hole.SettingsLibraries;
import io.github.lukashornych.hole.idea.process.CliRunner;
import io.github.lukashornych.hole.idea.testutil.FakeHole;
import io.github.lukashornych.hole.idea.ui.settings.SettingsDocuments;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 * The Libraries window's reads and edits of the settings files named by the fake `hole`.
 */
public class LibraryFilesTest extends BasePlatformTestCase {

    private Path temp;
    private Path global;
    private Path projectDir;
    private Path projectFile;
    private LibraryFiles files;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        this.temp = Files.createTempDirectory("hole-libraries").toRealPath();
        final FakeHole hole = FakeHole.install(this.temp.resolve("fake"));
        this.global = this.temp.resolve("config/hole/settings.json");
        this.projectDir = Files.createDirectories(this.temp.resolve("project"));
        this.projectFile = this.projectDir.resolve(".hole/settings.json");
        Files.createDirectories(this.temp.resolve("home/lib"));
        hole.environment().put("HOLE_SETTINGS_FILE", this.global.toString());
        this.files = new LibraryFiles(new SettingsDocuments(getProject(), new SettingsFileResolver(
            new HoleSettingsClient(hole.executable().toString(), new CliRunner(hole::environment))
        ), this.projectDir), this.temp.resolve("home"));
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            NioFiles.deleteRecursively(this.temp);
        } finally {
            super.tearDown();
        }
    }

    public void testMissingFilesReadAsNoLibraries() throws Exception {
        final LibraryFiles.Loaded loaded = run(this.files::load);
        assertEquals(List.of(), loaded.libraries());
        assertEquals(List.of(), loaded.problems());
    }

    public void testBothFilesAreMergedAndAnInvalidOneIsReported() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{\"libraries\": {\"~/lib\": {\"path\": \"/libs/lib\", \"readwrite\": true}}}");
        Files.createDirectories(this.projectFile.getParent());
        Files.writeString(this.projectFile, "{\"libraries\": {\"~/lib\": {\"path\": \"/libs/other\"}}}");
        assertEquals(List.of(new SettingsLibraries.Library("~/lib", "/libs/other", true, true, SettingsLibraries.Origin.PROJECT_OVERRIDES_GLOBAL)),
            run(this.files::load).libraries());

        Files.writeString(this.projectFile, "{\"libraries\": [\"~/lib\"]}");
        // as the IDE does on frame activation; the files are read through their documents
        LocalFileSystem.getInstance().refreshNioFiles(List.of(this.projectFile));
        final LibraryFiles.Loaded loaded = run(this.files::load);
        assertEquals(List.of(new SettingsLibraries.Library("~/lib", "/libs/lib", true, true, SettingsLibraries.Origin.GLOBAL)),
            loaded.libraries());
        assertEquals(List.of("The project settings file was not read: `libraries` is not an object"), loaded.problems());
    }

    public void testAddCreatesTheMissingGlobalFile() throws Exception {
        assertEquals(Optional.empty(), add(HoleSettingsClient.Scope.GLOBAL, "~/lib", "/libs/lib", false));
        final String saved = Files.readString(this.global);
        assertTrue(saved, saved.contains("\"$schema\""));
        assertTrue(saved, saved.contains("\"libraries\": {\n    \"~/lib\": \"/libs/lib\"\n  }"));
    }

    public void testAddToTheProjectFileOfTheSandboxAndRefusals() throws Exception {
        assertEquals(Optional.empty(), add(HoleSettingsClient.Scope.PROJECT, this.temp.resolve("home/lib").toString(), "/libs/lib", true));
        assertTrue(Files.readString(this.projectFile).contains("\"readwrite\": true"));

        final Optional<String> again = add(HoleSettingsClient.Scope.PROJECT, "~/lib/", "/x", false);
        assertTrue(again.toString(), again.orElseThrow().endsWith("is already in this file"));
        assertEquals(Optional.of("~/missing is not a directory"), add(HoleSettingsClient.Scope.PROJECT, "~/missing", "/x", false));
        // Hole expands `$VAR` in its own environment before it looks for the directory
        assertEquals(Optional.empty(), add(HoleSettingsClient.Scope.PROJECT, "$WORKSPACE/lib", "/libs/ws", false));
        assertTrue(Files.readString(this.projectFile).contains("\"$WORKSPACE/lib\": \"/libs/ws\""));
    }

    public void testChangeAndRemove() throws Exception {
        Files.createDirectories(this.global.getParent());
        Files.writeString(this.global, "{\"libraries\": {\"~/lib\": \"/libs/lib\", \"~/keep\": \"/libs/keep\"}}");
        assertEquals(Optional.empty(), run(() -> this.files.change(
            HoleSettingsClient.Scope.GLOBAL, "~/lib", "/libs/lib", true, ModalityState.nonModal())));
        assertTrue(Files.readString(this.global).contains("\"~/lib\": {\n      \"path\": \"/libs/lib\",\n      \"readwrite\": true\n    }"));

        assertEquals(Optional.empty(), run(() -> this.files.remove(HoleSettingsClient.Scope.GLOBAL, "~/lib", ModalityState.nonModal())));
        assertFalse(Files.readString(this.global).contains("~/lib"));
        assertTrue(Files.readString(this.global).contains("\"~/keep\": \"/libs/keep\""));

        assertEquals(Optional.empty(), run(() -> this.files.remove(HoleSettingsClient.Scope.PROJECT, "~/lib", ModalityState.nonModal())));
        assertFalse(Files.exists(this.projectFile));
    }

    /**
     * The Add input is modal: the save has to run in its modality (see the Network window's regression).
     */
    public void testAddFinishesWhileTheModalInputIsOpen() throws Exception {
        final Object input = new Object();
        LaterInvocator.enterModal(input);
        try {
            final ModalityState modality = ModalityState.current();
            final Future<Optional<String>> future = ApplicationManager.getApplication().executeOnPooledThread(
                () -> this.files.add(HoleSettingsClient.Scope.GLOBAL, "~/lib", "/libs/lib", false, modality));
            assertEquals(Optional.empty(), PlatformTestUtil.waitForFuture(future, 30_000));
        } finally {
            LaterInvocator.leaveModal(input);
        }
    }

    private Optional<String> add(HoleSettingsClient.Scope scope, String host, String mount, boolean readWrite) throws Exception {
        return run(() -> this.files.add(scope, host, mount, readWrite, ModalityState.nonModal()));
    }

    private <T> T run(Callable<T> call) throws Exception {
        final Future<T> future = ApplicationManager.getApplication().executeOnPooledThread(call);
        return PlatformTestUtil.waitForFuture(future, 30_000);
    }
}
