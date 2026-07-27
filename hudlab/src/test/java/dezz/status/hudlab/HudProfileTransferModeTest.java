/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.hudlab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HudProfileTransferModeTest {
    @Test
    public void sdkAcceptsOnlyDocumentedModes() {
        for (int mode = 0; mode <= 3; mode++) {
            assertTrue(HudProfileTransferMode.isSdkMode(mode));
            assertEquals(mode, HudProfileTransferMode.requireSdkMode(mode));
        }
    }

    @Test
    public void rawMinusOneCannotAccidentallyEnterSdkPath() {
        assertFalse(HudProfileTransferMode.isSdkMode(-1));
        assertThrows(IllegalArgumentException.class,
                () -> HudProfileTransferMode.requireSdkMode(-1));
        assertThrows(IllegalArgumentException.class,
                () -> HudProfileTransferMode.requireSdkMode(4));
    }
}
