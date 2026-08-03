/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneNotificationLayoutConfigTest {
    @Test public void iconPresetIsACompactEditableCarPlayHierarchy() {
        PhoneNotificationLayoutConfig value = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
        assertTrue(value.avatar.visible);
        assertTrue(value.badge.visible);
        assertTrue(value.title.visible);
        assertTrue(value.application.visible);
        assertTrue(value.chevron.visible);
        assertFalse(value.message.visible);
        assertEquals(48, PhoneNotificationLayoutConfig.GRID_COLUMNS);
        assertEquals(12, PhoneNotificationLayoutConfig.GRID_ROWS);
    }

    @Test public void noIconPresetUsesTheFreedLeftSideForText() {
        PhoneNotificationLayoutConfig value = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_ID);
        assertFalse(value.avatar.visible);
        assertFalse(value.badge.visible);
        assertEquals(2, value.title.column);
        assertTrue(value.title.columnSpan > 10);
    }

    @Test public void importedGeometryAndAppearanceAreClampedAndRoundTrip() throws Exception {
        PhoneNotificationLayoutConfig value = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
        value.title.column = 90;
        value.title.row = -20;
        value.title.columnSpan = 90;
        value.title.rowSpan = 0;
        value.title.textSizePx = 500;
        value.cornerRadiusPx = 900;
        value.backgroundAlpha = -1;
        PhoneNotificationLayoutConfig restored = PhoneNotificationLayoutConfig.fromJson(
                value.overlayId, value.toJson());
        assertEquals(0, restored.title.column);
        assertEquals(0, restored.title.row);
        assertEquals(48, restored.title.columnSpan);
        assertEquals(1, restored.title.rowSpan);
        assertEquals(160, restored.title.textSizePx);
        assertEquals(240, restored.cornerRadiusPx);
        assertEquals(0, restored.backgroundAlpha);
    }
}
