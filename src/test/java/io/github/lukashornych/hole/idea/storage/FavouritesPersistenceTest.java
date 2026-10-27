package io.github.lukashornych.hole.idea.storage;

import com.intellij.openapi.components.State;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import io.github.lukashornych.hole.idea.model.Favourite;
import io.github.lukashornych.hole.idea.model.FavouriteScope;
import io.github.lukashornych.hole.idea.model.Library;
import io.github.lukashornych.hole.idea.model.SandboxConfig;
import org.jdom.Element;

import java.util.List;

/**
 * Favourites survive the XML round trip of both services and are split by scope.
 */
public class FavouritesPersistenceTest extends BasePlatformTestCase {

    private static final Favourite FULL = new Favourite("Research with Docker", FavouriteScope.PROJECT, new SandboxConfig(
        "claude", "research", "../acme-cms-worktree", true, true, true, true, true, true,
        List.of(new Library("~/libs/shared", true), new Library("/opt/data", false)), "--model opus \"two words\""
    ));
    private static final Favourite GLOBAL = new Favourite("Codex anywhere", FavouriteScope.GLOBAL, SandboxConfig.defaults().withAgent("codex"));

    public void testProjectFavouritesRoundTrip() {
        final ProjectFavouritesService service = ProjectFavouritesService.getInstance(getProject());
        service.setFavourites(List.of(FULL, FULL.withName("Second")));
        final Element xml = XmlSerializer.serialize(service.getState());

        final ProjectFavouritesService loaded = new ProjectFavouritesService();
        loaded.loadState(XmlSerializer.deserialize(xml, FavouritesState.class));
        assertEquals(List.of(FULL, FULL.withName("Second")), loaded.getFavourites());
        service.setFavourites(List.of());
    }

    public void testGlobalFavouritesRoundTripGetGlobalScope() {
        final GlobalFavouritesService service = GlobalFavouritesService.getInstance();
        service.setFavourites(List.of(GLOBAL.withScope(FavouriteScope.PROJECT)));
        final Element xml = XmlSerializer.serialize(service.getState());

        final GlobalFavouritesService loaded = new GlobalFavouritesService();
        loaded.loadState(XmlSerializer.deserialize(xml, FavouritesState.class));
        assertEquals("the scope follows from the storage", List.of(GLOBAL), loaded.getFavourites());
        service.setFavourites(List.of());
    }

    public void testSaveAllSplitsByScopeAndAllListsProjectFirst() {
        GlobalFavouritesService.saveAll(getProject(), List.of(GLOBAL, FULL));
        try {
            assertEquals(List.of(FULL), ProjectFavouritesService.getInstance(getProject()).getFavourites());
            assertEquals(List.of(GLOBAL), GlobalFavouritesService.getInstance().getFavourites());
            assertEquals(List.of(FULL, GLOBAL), GlobalFavouritesService.all(getProject()));
        } finally {
            GlobalFavouritesService.saveAll(getProject(), List.of());
        }
    }

    public void testSaveAllPublishesTheChange() {
        final java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
        com.intellij.openapi.application.ApplicationManager.getApplication().getMessageBus().connect(getTestRootDisposable())
            .subscribe(FavouritesListener.TOPIC, (FavouritesListener) changes::incrementAndGet);
        try {
            GlobalFavouritesService.saveAll(getProject(), List.of(FULL));
            assertEquals("also when only project favourites changed", 1, changes.get());
            GlobalFavouritesService.saveAll(getProject(), List.of(FULL, GLOBAL));
            assertEquals(2, changes.get());
        } finally {
            GlobalFavouritesService.saveAll(getProject(), List.of());
        }
    }

    public void testHandEditedNullsBecomeDefaults() {
        final FavouriteBean bean = new FavouriteBean();
        bean.setName(null);
        bean.setAgent(null);
        bean.setProfile(null);
        bean.setProjectPath(null);
        bean.setLibraries(null);
        bean.setAgentArgs(null);
        final Favourite favourite = bean.toFavourite(FavouriteScope.PROJECT);
        assertEquals("", favourite.name());
        assertEquals("", favourite.config().agent());
        assertEquals("", favourite.config().profile());
        assertEquals("", favourite.config().projectPath());
        assertEquals(List.of(), favourite.config().libraries());
        assertEquals("", favourite.config().agentArgs());

        final FavouriteBean.LibraryBean library = new FavouriteBean.LibraryBean();
        library.setPath(null);
        bean.setLibraries(new java.util.ArrayList<>(java.util.Arrays.asList(library, null)));
        assertEquals(List.of(new Library("", false)), bean.toFavourite(FavouriteScope.PROJECT).config().libraries());

        final FavouritesState state = new FavouritesState();
        state.setFavourites(null);
        assertEquals(List.of(), state.toFavourites(FavouriteScope.GLOBAL));
    }

    public void testStorageFiles() {
        assertEquals("hole.xml", ProjectFavouritesService.class.getAnnotation(State.class).storages()[0].value());
        assertEquals("holeFavourites.xml", GlobalFavouritesService.class.getAnnotation(State.class).storages()[0].value());
    }
}
