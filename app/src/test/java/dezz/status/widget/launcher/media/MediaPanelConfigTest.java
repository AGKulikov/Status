/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class MediaPanelConfigTest {
    @Test
    public void defaultsContainEveryElementInStableOrder() {
        MediaPanelConfig config = new MediaPanelConfig();
        List<MediaPanelConfig.Element> elements = config.orderedElements();
        assertEquals(MediaPanelConfig.SPECS.size(), elements.size());
        for (int index = 0; index < elements.size(); index++) {
            assertEquals(MediaPanelConfig.SPECS.get(index).id, elements.get(index).id);
            assertTrue(elements.get(index).enabled);
            assertEquals(100, elements.get(index).scalePercent);
        }
        assertNoEnabledOverlap(config);
    }

    @Test
    public void copyHasIndependentSelectionOrderAndScale() {
        MediaPanelConfig original = new MediaPanelConfig();
        MediaPanelConfig copy = original.copy();
        copy.setEnabled(MediaPanelConfig.ARTWORK, false);
        copy.setScale(MediaPanelConfig.TITLE, 175);
        copy.move(MediaPanelConfig.NEXT, -1);
        assertTrue(original.element(MediaPanelConfig.ARTWORK).enabled);
        assertFalse(copy.element(MediaPanelConfig.ARTWORK).enabled);
        assertEquals(100, original.element(MediaPanelConfig.TITLE).scalePercent);
        assertEquals(175, copy.element(MediaPanelConfig.TITLE).scalePercent);
        List<MediaPanelConfig.Element> moved = copy.orderedElements();
        int nextIndex = indexOf(moved, MediaPanelConfig.NEXT);
        int playPauseIndex = indexOf(moved, MediaPanelConfig.PLAY_PAUSE);
        assertEquals(playPauseIndex - 1, nextIndex);
    }

    @Test
    public void normalizeClampsMetricsAndRepairsColors() {
        MediaPanelConfig config = new MediaPanelConfig();
        config.backgroundAlpha = 999;
        config.spacingPx = -4;
        config.contentPaddingPx = 1000;
        config.titleColor = "invalid";
        config.setScale(MediaPanelConfig.PLAY_PAUSE, 5);
        config.normalize();
        assertEquals(255, config.backgroundAlpha);
        assertEquals(0, config.spacingPx);
        assertEquals(64, config.contentPaddingPx);
        assertEquals("#FFFFFF", config.titleColor);
        assertEquals(45, config.element(MediaPanelConfig.PLAY_PAUSE).scalePercent);
    }

    @Test public void directPlacementDisplacesCollisionAndNeverStacks() {
        MediaPanelConfig config = new MediaPanelConfig();
        MediaPanelConfig.Element artwork = config.element(MediaPanelConfig.ARTWORK);
        MediaPanelConfig.Element titleBefore = config.element(MediaPanelConfig.TITLE);
        int titleColumn = titleBefore.column;
        int titleRow = titleBefore.row;

        assertTrue(config.setPosition(MediaPanelConfig.ARTWORK,
                titleColumn, titleRow));

        MediaPanelConfig.Element moved = config.element(MediaPanelConfig.ARTWORK);
        assertEquals(titleColumn, moved.column);
        assertEquals(titleRow, moved.row);
        assertNoEnabledOverlap(config);
        MediaPanelConfig.Element displacedTitle = config.element(MediaPanelConfig.TITLE);
        assertFalse(displacedTitle.column == titleColumn && displacedTitle.row == titleRow);
        assertTrue(artwork.column + artwork.columnSpan <= MediaPanelConfig.GRID_COLUMNS);
    }

    @Test public void independentSpansAndContentScaleRemainIndependent() {
        MediaPanelConfig config = new MediaPanelConfig();
        assertTrue(config.setSpan(MediaPanelConfig.PLAY_PAUSE, 2, 2));
        config.setScale(MediaPanelConfig.PLAY_PAUSE, 175);
        MediaPanelConfig.Element element = config.element(MediaPanelConfig.PLAY_PAUSE);
        assertEquals(2, element.columnSpan);
        assertEquals(2, element.rowSpan);
        assertEquals(175, element.scalePercent);
        assertNoEnabledOverlap(config);
    }

    @Test public void enablingElementIsRejectedWhenGridIsCompletelyOccupied() {
        MediaPanelConfig config = new MediaPanelConfig();
        for (MediaPanelConfig.Spec spec : MediaPanelConfig.SPECS) {
            config.setEnabled(spec.id, false);
        }
        assertTrue(config.setEnabled(MediaPanelConfig.ARTWORK, true));
        assertTrue(config.setSpan(MediaPanelConfig.ARTWORK,
                MediaPanelConfig.GRID_COLUMNS, MediaPanelConfig.GRID_ROWS));

        assertFalse(config.setEnabled(MediaPanelConfig.TITLE, true));
        assertFalse(config.element(MediaPanelConfig.TITLE).enabled);
        assertNoEnabledOverlap(config);
    }

    private static int indexOf(List<MediaPanelConfig.Element> elements, String id) {
        for (int index = 0; index < elements.size(); index++) {
            if (id.equals(elements.get(index).id)) return index;
        }
        return -1;
    }

    private static void assertNoEnabledOverlap(MediaPanelConfig config) {
        List<MediaPanelConfig.Element> elements = config.orderedElements();
        for (int first = 0; first < elements.size(); first++) {
            MediaPanelConfig.Element a = elements.get(first);
            if (!a.enabled) continue;
            for (int second = first + 1; second < elements.size(); second++) {
                MediaPanelConfig.Element b = elements.get(second);
                if (b.enabled) assertFalse(a.id + " overlaps " + b.id,
                        MediaPanelConfig.overlaps(a, b));
            }
        }
    }
}
