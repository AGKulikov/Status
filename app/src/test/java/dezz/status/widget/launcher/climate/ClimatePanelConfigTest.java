/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

public final class ClimatePanelConfigTest {
    @Test
    public void defaultsExposeEveryClimateFunction() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        assertTrue(config.hasEnabledElements());
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            assertTrue(element.id, config.isElementEnabled(element.id));
        }
    }

    @Test
    public void panelIsEmptyOnlyWhenEveryControlIsDisabled() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            config.setElementEnabled(element.id, false);
        }
        assertFalse(config.hasEnabledElements());
        config.setElementEnabled(ClimatePanelConfig.POWER, true);
        assertTrue(config.hasEnabledElements());
    }

    @Test
    public void copyKeepsIndependentElementSelection() {
        ClimatePanelConfig original = new ClimatePanelConfig();
        original.setElementEnabled(ClimatePanelConfig.WHEEL_HEAT, false);
        ClimatePanelConfig copy = original.copy();
        copy.setElementEnabled(ClimatePanelConfig.WHEEL_HEAT, true);
        assertFalse(original.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
        assertTrue(copy.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
    }

    @Test
    public void normalizeClampsVisualValuesAndRepairsColors() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        config.backgroundAlpha = 900;
        config.cornerRadiusPx = -3;
        config.tileCornerRadiusPx = 900;
        config.tileSpacingPx = -4;
        config.activeTileAlpha = 999;
        config.inactiveTileAlpha = -2;
        config.scalePercent = 20;
        config.accentColor = "wrong";
        config.normalize();
        assertEquals(255, config.backgroundAlpha);
        assertEquals(0, config.cornerRadiusPx);
        assertEquals(64, config.tileCornerRadiusPx);
        assertEquals(0, config.tileSpacingPx);
        assertEquals(255, config.activeTileAlpha);
        assertEquals(0, config.inactiveTileAlpha);
        assertEquals(60, config.scalePercent);
        assertEquals("#59A9FF", config.accentColor);
    }

    @Test
    public void elementOrderAndScaleAreIndependentAndClamped() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        assertTrue(config.moveElement(ClimatePanelConfig.AC, 1));
        config.setElementScalePercent(ClimatePanelConfig.AC, 250);
        assertEquals(ClimatePanelConfig.AUTO, config.elementOrder().get(1));
        assertEquals(ClimatePanelConfig.AC, config.elementOrder().get(2));
        assertEquals(180, config.elementScalePercent(ClimatePanelConfig.AC));

        ClimatePanelConfig copy = config.copy();
        copy.moveElement(ClimatePanelConfig.AC, -1);
        copy.setElementScalePercent(ClimatePanelConfig.AC, 70);
        assertEquals(180, config.elementScalePercent(ClimatePanelConfig.AC));
        assertEquals(70, copy.elementScalePercent(ClimatePanelConfig.AC));
    }

    @Test
    public void dragOrderAndPhysicalDimensionsAreIndependent() {
        ClimatePanelConfig config = new ClimatePanelConfig();
        int target = config.elementOrder().indexOf(ClimatePanelConfig.WHEEL_HEAT);
        assertTrue(config.moveElementTo(ClimatePanelConfig.POWER, target));
        config.setElementWidthPercent(ClimatePanelConfig.POWER, 999);
        config.setElementHeightPercent(ClimatePanelConfig.POWER, 1);
        assertEquals(ClimatePanelConfig.WHEEL_HEAT,
                config.elementOrder().get(target - 1));
        assertEquals(ClimatePanelConfig.POWER, config.elementOrder().get(target));
        assertEquals(240, config.elementWidthPercent(ClimatePanelConfig.POWER));
        assertEquals(65, config.elementHeightPercent(ClimatePanelConfig.POWER));
    }

    @Test
    public void versionOneElementsMigrateWithDefaultOrderAndSizes() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("version", 1)
                .put("elements", new JSONObject()
                        .put(ClimatePanelConfig.POWER, true)
                        .put(ClimatePanelConfig.AC, false));
        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(legacy.toString());
        assertTrue(restored.isElementEnabled(ClimatePanelConfig.POWER));
        assertFalse(restored.isElementEnabled(ClimatePanelConfig.AC));
        assertEquals(ClimatePanelConfig.POWER, restored.elementOrder().get(0));
        assertEquals(100, restored.elementScalePercent(ClimatePanelConfig.POWER));
    }

    @Test
    public void enabledElementsArrayMigratesWithoutEnablingMissingItems() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("enabledElements", new JSONArray(Arrays.asList(
                        ClimatePanelConfig.POWER, ClimatePanelConfig.WHEEL_HEAT)));
        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(legacy.toString());
        assertTrue(restored.isElementEnabled(ClimatePanelConfig.POWER));
        assertTrue(restored.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
        assertFalse(restored.isElementEnabled(ClimatePanelConfig.AC));
    }

    @Test
    public void emptyLegacyEnabledElementsMigratesToAnActuallyEmptyPanel() throws Exception {
        JSONObject legacy = new JSONObject().put("enabledElements", new JSONArray());
        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(legacy.toString());
        assertFalse(restored.hasEnabledElements());
    }

    @Test
    public void legacyEnabledElementsObjectAndCsvAreAccepted() throws Exception {
        JSONObject objectLegacy = new JSONObject().put("enabledElements", new JSONObject()
                .put(ClimatePanelConfig.AC, true)
                .put(ClimatePanelConfig.POWER, false));
        ClimatePanelConfig fromObject = ClimatePanelConfigStore.decode(objectLegacy.toString());
        assertTrue(fromObject.isElementEnabled(ClimatePanelConfig.AC));
        assertFalse(fromObject.isElementEnabled(ClimatePanelConfig.POWER));

        JSONObject csvLegacy = new JSONObject().put("enabledElements",
                ClimatePanelConfig.POWER + ", " + ClimatePanelConfig.FAN);
        ClimatePanelConfig fromCsv = ClimatePanelConfigStore.decode(csvLegacy.toString());
        assertTrue(fromCsv.isElementEnabled(ClimatePanelConfig.POWER));
        assertTrue(fromCsv.isElementEnabled(ClimatePanelConfig.FAN));
        assertFalse(fromCsv.isElementEnabled(ClimatePanelConfig.AC));
    }

    @Test
    public void emptyTransitionalElementsObjectDoesNotMaskLegacySelection() throws Exception {
        JSONObject transitional = new JSONObject()
                .put("version", 1)
                .put("elements", new JSONObject())
                .put("enabledElements", new JSONArray()
                        .put(ClimatePanelConfig.WHEEL_HEAT));
        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(transitional.toString());
        assertTrue(restored.isElementEnabled(ClimatePanelConfig.WHEEL_HEAT));
        assertFalse(restored.isElementEnabled(ClimatePanelConfig.POWER));
    }

    @Test
    public void versionTwoRoundTripKeepsOrderScaleAndSelection() throws Exception {
        ClimatePanelConfig source = new ClimatePanelConfig();
        source.setElementEnabled(ClimatePanelConfig.FAN, false);
        source.setElementScalePercent(ClimatePanelConfig.WHEEL_HEAT, 145);
        source.setElementOrder(Arrays.asList(ClimatePanelConfig.WHEEL_HEAT,
                ClimatePanelConfig.FAN, ClimatePanelConfig.POWER));

        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(
                ClimatePanelConfigStore.encode(source).toString());
        assertEquals(ClimatePanelConfig.WHEEL_HEAT, restored.elementOrder().get(0));
        assertEquals(145, restored.elementScalePercent(ClimatePanelConfig.WHEEL_HEAT));
        assertFalse(restored.isElementEnabled(ClimatePanelConfig.FAN));
        assertEquals(ClimatePanelConfig.ELEMENTS.size(), restored.elementOrder().size());
    }

    @Test
    public void versionTwoSingleSizeMigratesToPhysicalWidthAndHeight() throws Exception {
        JSONObject legacy = new JSONObject()
                .put("version", 2)
                .put("elementScales", new JSONObject()
                        .put(ClimatePanelConfig.AC, 145));
        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(legacy.toString());
        assertEquals(145, restored.elementScalePercent(ClimatePanelConfig.AC));
        assertEquals(145, restored.elementWidthPercent(ClimatePanelConfig.AC));
        assertEquals(145, restored.elementHeightPercent(ClimatePanelConfig.AC));
    }

    @Test
    public void versionThreeRoundTripKeepsCarPlayGlassAndTileDimensions() throws Exception {
        ClimatePanelConfig source = new ClimatePanelConfig();
        source.tileCornerRadiusPx = 41;
        source.tileSpacingPx = 17;
        source.activeTileAlpha = 207;
        source.inactiveTileAlpha = 51;
        source.setElementWidthPercent(ClimatePanelConfig.TEMP_DRIVER, 175);
        source.setElementHeightPercent(ClimatePanelConfig.TEMP_DRIVER, 125);

        ClimatePanelConfig restored = ClimatePanelConfigStore.decode(
                ClimatePanelConfigStore.encode(source).toString());
        assertEquals(41, restored.tileCornerRadiusPx);
        assertEquals(17, restored.tileSpacingPx);
        assertEquals(207, restored.activeTileAlpha);
        assertEquals(51, restored.inactiveTileAlpha);
        assertEquals(175, restored.elementWidthPercent(ClimatePanelConfig.TEMP_DRIVER));
        assertEquals(125, restored.elementHeightPercent(ClimatePanelConfig.TEMP_DRIVER));
    }
}
