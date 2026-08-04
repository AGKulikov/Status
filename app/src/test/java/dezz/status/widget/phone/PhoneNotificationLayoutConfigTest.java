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
        value.borderWidthPx = 900;
        value.iconPreserveAspectRatio = false;
        value.title.maxLines = 40;
        value.title.overflowMode = PhoneNotificationLayoutConfig.OVERFLOW_SCROLL;
        PhoneNotificationLayoutConfig restored = PhoneNotificationLayoutConfig.fromJson(
                value.overlayId, value.toJson());
        assertEquals(0, restored.title.column);
        assertEquals(0, restored.title.row);
        assertEquals(48, restored.title.columnSpan);
        assertEquals(1, restored.title.rowSpan);
        assertEquals(160, restored.title.textSizePx);
        assertEquals(240, restored.cornerRadiusPx);
        assertEquals(0, restored.backgroundAlpha);
        assertEquals(40, restored.borderWidthPx);
        assertFalse(restored.iconPreserveAspectRatio);
        assertEquals(8, restored.title.maxLines);
        assertEquals(PhoneNotificationLayoutConfig.OVERFLOW_SCROLL,
                restored.title.overflowMode);
    }

    @Test public void versionTwoLayoutMigratesWithoutLosingExistingGeometry() throws Exception {
        PhoneNotificationLayoutConfig value = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
        value.badge.column = 11;
        value.badge.columnSpan = 7;
        org.json.JSONObject legacy = value.toJson().put("schemaVersion", 2);
        legacy.remove("iconPreserveAspectRatio");

        PhoneNotificationLayoutConfig restored = PhoneNotificationLayoutConfig.fromJson(
                value.overlayId, legacy);

        assertEquals(11, restored.badge.column);
        assertEquals(7, restored.badge.columnSpan);
        assertTrue(restored.iconPreserveAspectRatio);
    }

    @Test public void copyStyleKeepsNoIconGeometryAndVisibility() {
        PhoneNotificationLayoutConfig source = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
        source.borderWidthPx = 6;
        source.title.textSizePx = 41;
        source.title.maxLines = 3;
        source.title.overflowMode = PhoneNotificationLayoutConfig.OVERFLOW_SCROLL;
        source.title.column = 19;
        PhoneNotificationLayoutConfig target = PhoneNotificationLayoutConfig.carPlay(
                PhoneNotificationAutomation.OVERLAY_ID);
        int originalColumn = target.title.column;

        target.copyStyleFrom(source);

        assertFalse(target.avatar.visible);
        assertFalse(target.badge.visible);
        assertEquals(originalColumn, target.title.column);
        assertEquals(6, target.borderWidthPx);
        assertEquals(41, target.title.textSizePx);
        assertEquals(3, target.title.maxLines);
        assertEquals(PhoneNotificationLayoutConfig.OVERFLOW_SCROLL,
                target.title.overflowMode);
    }
}
