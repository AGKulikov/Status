/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.apps;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public final class FavoriteAppConfigTest {
    @Test
    public void normalizeClampsSizes() {
        FavoriteAppConfig value = new FavoriteAppConfig("com.example.app");
        value.iconSizePx = Integer.MAX_VALUE;
        value.labelSizeSp = Integer.MIN_VALUE;

        value.normalize();

        assertEquals(FavoriteAppConfig.MAX_ICON_SIZE_PX, value.iconSizePx);
        assertEquals(FavoriteAppConfig.MIN_LABEL_SIZE_SP, value.labelSizeSp);
    }

    @Test
    public void copyIsIndependentAndNormalizesPackage() {
        FavoriteAppConfig original = new FavoriteAppConfig("  com.example.app  ");
        original.iconSizePx = 74;
        original.labelSizeSp = 17;
        original.showLabel = false;

        FavoriteAppConfig copy = original.copy();
        copy.iconSizePx = 120;

        assertEquals("com.example.app", original.packageName);
        assertEquals(74, original.iconSizePx);
        assertEquals(17, copy.labelSizeSp);
        assertFalse(copy.showLabel);
    }
}
