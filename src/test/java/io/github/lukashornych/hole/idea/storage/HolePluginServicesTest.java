package io.github.lukashornych.hole.idea.storage;

import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import io.github.lukashornych.hole.idea.hole.HoleExecutable;
import io.github.lukashornych.hole.idea.hole.MetadataPoller;
import org.jdom.Element;

/**
 * Services are created by the platform (constructor injection works) and the settings state survives an XML
 * round trip.
 */
public class HolePluginServicesTest extends BasePlatformTestCase {

    public void testSettingsDefaultsAndUpdate() {
        final HolePluginSettings settings = HolePluginSettings.getInstance();
        final String previous = settings.getHoleExecutablePath();
        try {
            settings.setHoleExecutablePath("  /opt/hole/bin/hole  ");
            assertEquals("/opt/hole/bin/hole", settings.getHoleExecutablePath());
            settings.setHoleExecutablePath(null);
            assertEquals("", settings.getHoleExecutablePath());
        } finally {
            settings.setHoleExecutablePath(previous);
        }
    }

    public void testSettingsStateSerializationRoundTrip() {
        final HolePluginSettings.SettingsState state = new HolePluginSettings.SettingsState();
        state.setHoleExecutablePath("/Users/me/.local/bin/hole");
        final Element element = XmlSerializer.serialize(state);

        final HolePluginSettings.SettingsState loaded = XmlSerializer.deserialize(element, HolePluginSettings.SettingsState.class);
        assertEquals("/Users/me/.local/bin/hole", loaded.getHoleExecutablePath());

        final HolePluginSettings settings = new HolePluginSettings();
        settings.loadState(loaded);
        assertEquals("/Users/me/.local/bin/hole", settings.getHoleExecutablePath());
    }

    public void testExecutablePathValidation() {
        assertNull(HolePluginSettings.validateExecutablePath(""));
        assertNull(HolePluginSettings.validateExecutablePath("  "));
        assertNull(HolePluginSettings.validateExecutablePath("/opt/hole/bin/hole"));
        assertNull(HolePluginSettings.validateExecutablePath("~/.local/bin/hole"));
        assertNotNull(HolePluginSettings.validateExecutablePath("hole"));
        assertNotNull(HolePluginSettings.validateExecutablePath("bin/hole"));
        assertNotNull(HolePluginSettings.validateExecutablePath("~hole"));
    }

    public void testSettingsAreNotRoamed() {
        final com.intellij.openapi.components.State state = HolePluginSettings.class.getAnnotation(com.intellij.openapi.components.State.class);
        assertEquals(com.intellij.openapi.components.RoamingType.LOCAL, state.storages()[0].roamingType());
        assertEquals("hole.xml", state.storages()[0].value());
    }

    public void testProjectServicesAreAvailable() {
        final io.github.lukashornych.hole.idea.session.SandboxSessionManager manager =
            io.github.lukashornych.hole.idea.session.SandboxSessionManager.getInstance(getProject());
        assertNotNull(manager);
        assertTrue(manager.sessions().isEmpty());
        assertNotNull(manager.projectRoot());
        assertNotNull(ProjectFavouritesService.getInstance(getProject()));
        assertNotNull(GlobalFavouritesService.getInstance());
    }

    public void testApplicationServicesAreAvailable() {
        assertNotNull(HoleExecutable.getInstance());
        assertNotNull(HoleExecutable.getInstance().runner());
        final MetadataPoller poller = MetadataPoller.getInstance();
        assertNotNull(poller);
        assertFalse(poller.isActive());
    }
}
