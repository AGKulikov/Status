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
        assertEquals(MediaPanelConfig.NEXT,
                copy.orderedElements().get(copy.orderedElements().size() - 2).id);
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
}
