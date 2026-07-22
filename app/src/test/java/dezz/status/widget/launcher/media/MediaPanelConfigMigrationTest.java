package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class MediaPanelConfigMigrationTest {
    @Test public void richElementsAreEnabledForANewPanel() {
        MediaPanelConfig config = new MediaPanelConfig();
        assertTrue(config.element(MediaPanelConfig.ALBUM).enabled);
        assertTrue(config.element(MediaPanelConfig.PROGRESS).enabled);
        assertTrue(config.element(MediaPanelConfig.VOLUME).enabled);
    }

    @Test public void richElementsAppendDisabledWhenMigratingAnExistingPanel() {
        MediaPanelConfig config = new MediaPanelConfig();
        java.util.List<String> originalOrder = Arrays.asList(
                MediaPanelConfig.ARTWORK, MediaPanelConfig.TITLE, MediaPanelConfig.ARTIST,
                MediaPanelConfig.APPLICATION, MediaPanelConfig.PREVIOUS,
                MediaPanelConfig.PLAY_PAUSE, MediaPanelConfig.NEXT);
        Set<String> originalIds = new HashSet<>(originalOrder);
        for (int index = 0; index < originalOrder.size(); index++) {
            config.element(originalOrder.get(index)).order = index;
        }

        config.appendMissingDisabled(originalIds, 6);
        config.normalize();

        assertFalse(config.element(MediaPanelConfig.ALBUM).enabled);
        assertFalse(config.element(MediaPanelConfig.PROGRESS).enabled);
        assertFalse(config.element(MediaPanelConfig.VOLUME).enabled);
        assertTrue(config.element(MediaPanelConfig.ALBUM).order > 6);
        assertEquals(MediaPanelConfig.VOLUME,
                config.orderedElements().get(config.orderedElements().size() - 1).id);
    }
}
