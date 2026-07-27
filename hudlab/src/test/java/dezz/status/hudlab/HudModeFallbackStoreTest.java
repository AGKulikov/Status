/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudModeFallbackStoreTest {
    @Test
    public void fallbackAcceptsOnlyConfirmedSdkModes() {
        for (int mode = 0; mode <= 3; mode++) {
            assertTrue(HudModeFallbackStore.isTargetMode(mode));
        }
        assertFalse(HudModeFallbackStore.isTargetMode(
                HudProfileTransferMode.RAW_INVALID_SENTINEL));
        assertFalse(HudModeFallbackStore.isTargetMode(4));
        assertFalse(HudModeFallbackStore.isTargetMode(HudModeFallbackStore.NO_MODE));
        assertEquals("не выбран",
                HudModeFallbackStore.modeLabel(HudModeFallbackStore.NO_MODE));
    }
}
