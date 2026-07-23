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

    @Test public void fixedGridSchemaMigratesToOriginalTwelveBySixGrid() {
        String versionTwo = "{\"version\":2,\"elements\":["
                + "{\"id\":\"media.next\",\"enabled\":true,\"order\":0,"
                + "\"scalePercent\":135,\"column\":5,\"row\":4,"
                + "\"columnSpan\":1,\"rowSpan\":1}]}";
        MediaPanelConfig config = MediaPanelConfigStore.decode(versionTwo);
        assertEquals(MediaPanelConfig.DEFAULT_GRID_COLUMNS, config.gridColumns);
        assertEquals(MediaPanelConfig.DEFAULT_GRID_ROWS, config.gridRows);
        assertEquals(5, config.element(MediaPanelConfig.NEXT).column);
        assertEquals(4, config.element(MediaPanelConfig.NEXT).row);
        assertEquals(135, config.element(MediaPanelConfig.NEXT).scalePercent);
    }

    @Test public void customGridSurvivesCurrentSchemaRoundTrip() {
        MediaPanelConfig source = new MediaPanelConfig();
        assertTrue(source.setGridSize(16, 8));
        source.setMarqueeEnabled(MediaPanelConfig.TITLE, false);
        MediaPanelConfig restored = MediaPanelConfigStore.decode(
                MediaPanelConfigStore.encode(source).toString());
        assertEquals(16, restored.gridColumns);
        assertEquals(8, restored.gridRows);
        assertEquals(source.element(MediaPanelConfig.PLAY_PAUSE).column,
                restored.element(MediaPanelConfig.PLAY_PAUSE).column);
        assertEquals(source.element(MediaPanelConfig.PLAY_PAUSE).row,
                restored.element(MediaPanelConfig.PLAY_PAUSE).row);
        assertFalse(restored.element(MediaPanelConfig.TITLE).marqueeEnabled);
    }

    @Test public void versionThreeDefaultsMarqueeOnForOverflowingText() {
        String versionThree = "{\"version\":3,\"gridColumns\":12,\"gridRows\":6,"
                + "\"elements\":[{\"id\":\"media.title\",\"enabled\":true,\"order\":0,"
                + "\"scalePercent\":100,\"column\":3,\"row\":0,"
                + "\"columnSpan\":5,\"rowSpan\":1}]}";
        MediaPanelConfig restored = MediaPanelConfigStore.decode(versionThree);
        assertTrue(restored.element(MediaPanelConfig.TITLE).marqueeEnabled);
    }
}
