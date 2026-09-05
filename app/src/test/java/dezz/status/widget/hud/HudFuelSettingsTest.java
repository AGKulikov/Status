/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class HudFuelSettingsTest {
    @Test public void screenshotPresetUsesLitresAndStrictBelowThresholds() {
        HudFuelSettings settings = new HudFuelSettings();
        assertEquals(64d, settings.capacityLitres(), 0d);
        assertEquals(0xFFFF453A, settings.levelColor(9.9, 123));
        assertEquals(0xFFFFCC00, settings.levelColor(10, 123));
        assertEquals(0xFFFFCC00, settings.levelColor(14.9, 123));
        assertEquals(123, settings.levelColor(15, 123));
        assertTrue(settings.showLevel(15));
        assertFalse(settings.showLevel(15.1));
        settings.hideAboveThreshold = false;
        assertTrue(settings.showLevel(64));
    }

    @Test public void customTankChangesRealRefillAndSurvivesDefaultMode() {
        HudFuelSettings settings = new HudFuelSettings();
        settings.customCapacityLitres = 70;
        assertEquals(50, settings.refillLitres(14), 0);
        settings.useDefaultCapacity = false;
        assertEquals(56, settings.refillLitres(14), 0);
        settings.useDefaultCapacity = true;
        assertEquals(50, settings.refillLitres(14), 0);
        assertEquals(70, settings.customCapacityLitres, 0);
        settings.useDefaultCapacity = false;
        assertEquals(56, settings.refillLitres(14), 0);
        assertEquals(0, settings.refillLitres(71), 0);
    }

    @Test public void missingFuelNeverBecomesFullTankRefillOrRedWarning() {
        HudFuelSettings settings = new HudFuelSettings();
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1}) {
            assertTrue(Double.isNaN(settings.refillLitres(value)));
            assertEquals(123, settings.levelColor(value, 123));
        }
        assertTrue(settings.showLevel(Double.NaN)); // The renderer can show its unavailable dash.
        assertEquals(64, settings.refillLitres(0), 0);
    }

    @Test public void parkRestrictionCanBeEnabledAndDisabled() {
        HudFuelSettings settings = new HudFuelSettings();
        assertTrue(settings.showRefill(false));
        settings.refillOnlyInPark = true;
        assertFalse(settings.showRefill(false));
        assertTrue(settings.showRefill(true));
        settings.refillOnlyInPark = false;
        assertTrue(settings.showRefill(false));
    }

    @Test public void editingDraftAndCancelLeavesLiveExportUnchanged() throws Exception {
        HudPanelConfig panel = HudPanelConfig.defaults();
        String before = panel.toJson().toString();
        HudFuelSettings draft = panel.fuelSettings.copy();
        draft.yellowBelowLitres = 18;
        draft.redBelowLitres = 7;
        draft.customCapacityLitres = 70;
        draft.useDefaultCapacity = false;
        draft.hideAboveThreshold = false;
        draft.refillOnlyInPark = true;
        draft.validate();
        assertEquals(before, panel.toJson().toString());
        panel.fuelSettings = draft;
        HudPanelConfig restored = HudPanelConfig.fromJson(panel.toJson().toString());
        assertEquals(draft.toJson().toString(), restored.fuelSettings.toJson().toString());
        assertEquals(70, restored.fuelSettings.capacityLitres(), 0);
    }

    @Test public void sharedFuelOptionsSurviveAlongsideSchemaSixDirectMap() throws Exception {
        HudPanelConfig panel = HudPanelConfig.defaults();
        HudElementConfig map = HudElementConfig.create(HudElementType.NAV_MAP, 1, 44, 18);
        panel.elements.add(map);
        panel.fuelSettings.redBelowLitres = 8;
        HudPanelConfig restored = HudPanelConfig.fromJson(panel.toJson().toString());
        assertTrue(restored.elements.stream().anyMatch(item -> item.type == HudElementType.NAV_MAP));
        assertEquals(8, restored.fuelSettings.redBelowLitres, 0);
    }

    @Test public void legacyWidgetsKeepIndependentChoicesUntilSharedApply() throws Exception {
        HudPanelConfig panel = HudPanelConfig.defaults();
        HudElementConfig first = HudElementConfig.create(HudElementType.FUEL_LEVEL, 1, 44, 18);
        HudElementConfig second = HudElementConfig.create(HudElementType.FUEL_LEVEL, 2, 44, 18);
        first.options.put("yellowThreshold", 18);
        second.options.put("yellowThreshold", 23);
        HudElementConfig refill = HudElementConfig.create(HudElementType.FUEL_REFILL, 1, 44, 18);
        refill.options.put("tankCapacityLitres", 70).put("automaticCapacity", false)
                .put("onlyInPark", false);
        panel.elements.add(first);
        panel.elements.add(second);
        panel.elements.add(refill);
        JSONObject legacy = panel.toJson();
        legacy.remove("fuelSettings");
        HudPanelConfig restored = HudPanelConfig.fromJson(legacy.toString());
        assertNull(restored.fuelSettings);
        assertEquals(18, restored.fuelSettingsFor(first).yellowBelowLitres, 0);
        assertEquals(23, restored.fuelSettingsFor(second).yellowBelowLitres, 0);
        assertEquals(56, restored.fuelSettingsFor(refill).refillLitres(14), 0);
        assertFalse(restored.fuelSettingsFor(refill).refillOnlyInPark);
        assertFalse(restored.toJson().has("fuelSettings"));
        restored.fuelSettings = new HudFuelSettings();
        assertEquals(15, restored.fuelSettingsFor(first).yellowBelowLitres, 0);
        assertEquals(15, restored.fuelSettingsFor(second).yellowBelowLitres, 0);
    }

    @Test public void invalidDraftIsRejectedWithoutChangingLiveSettings() {
        HudFuelSettings live = new HudFuelSettings();
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            HudFuelSettings draft = live.copy();
            draft.yellowBelowLitres = invalid;
            assertThrows(IllegalArgumentException.class, draft::validate);
        }
        HudFuelSettings draft = live.copy();
        draft.redBelowLitres = 16;
        assertThrows(IllegalArgumentException.class, draft::validate);
        draft = live.copy();
        draft.customCapacityLitres = 0;
        assertThrows(IllegalArgumentException.class, draft::validate);
        assertEquals(15, live.yellowBelowLitres, 0);
        assertEquals(64, live.capacityLitres(), 0);
    }
}
