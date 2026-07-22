/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dezz.status.widget.popup.PopupIconCatalog;
import dezz.status.widget.sprut.preset.SprutPopupPreset;
import dezz.status.widget.sprut.preset.SprutPopupPresetEngine;

public final class SprutPopupPresetEngineTest {
    private final SprutPopupPresetEngine engine = new SprutPopupPresetEngine();

    @Test public void garagePresetUsesCurrentStateAndWritableTarget() {
        SprutCatalog.Characteristic current = characteristic(12, "CurrentDoorState",
                SprutCatalog.ValueType.INTEGER, 2, true, false, "uint8", "", 0, 4,
                Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(13, "TargetDoorState",
                SprutCatalog.ValueType.INTEGER, 1, true, true, "uint8", "", 0, 1,
                Arrays.asList(valid(0, "open", "Open"), valid(1, "closed", "Closed")));
        SprutCatalog.Service service = service("GarageDoorOpener", "Ворота", current, target);

        SprutPopupPreset preset = engine.recommend(accessory("Въезд", service), service);

        assertEquals("garage", preset.iconId());
        assertEquals("Ворота", preset.title());
        assertEquals(SprutPopupPreset.Presentation.COVER, preset.presentation());
        assertEquals("42/7/12", preset.primaryCharacteristicPath().get().stableId());
        assertEquals("42/7/13", preset.actionCharacteristicPath().get().stableId());
        assertEquals(SprutPopupPreset.ActionOperation.SET, preset.actionOperation().get());
        assertEquals(0, preset.defaultActionPayload().get());
        assertEquals("Открывается", preset.statusFor(2).get().label());
        assertEquals("#FFFF9800", preset.statusFor(2).get().argbColor());
        assertEquals(1, preset.columnSpan());
        assertEquals(1, preset.rowSpan());
    }

    @Test public void garagePresetDoesNotUseUnrelatedWritableSiblingAsCommand() {
        SprutCatalog.Characteristic current = characteristic(12, "CurrentDoorState",
                SprutCatalog.ValueType.INTEGER, 1, true, false, "uint8", "", 0, 4,
                Collections.emptyList());
        SprutCatalog.Characteristic vendorSetting = characteristic(99, "CalibrationMode",
                SprutCatalog.ValueType.BOOLEAN, false, true, true, "bool", "", 0, 1,
                Collections.emptyList());
        SprutCatalog.Service service = service("GarageDoorOpener", "Ворота", current,
                vendorSetting);

        SprutPopupPreset preset = engine.recommend(accessory("Въезд", service), service);

        assertEquals("42/7/12", preset.primaryCharacteristicPath().get().stableId());
        assertFalse(preset.actionCharacteristicPath().isPresent());
        assertFalse(preset.actionOperation().isPresent());
    }

    @Test public void vendorServiceWithDoorStatePairStillGetsSafeGarageTarget() {
        SprutCatalog.Characteristic current = characteristic(15, "CurrentDoorState",
                SprutCatalog.ValueType.INTEGER, 1, true, false, "uint8", "", 0, 4,
                Collections.emptyList());
        SprutCatalog.Characteristic target = characteristic(16, "TargetDoorState",
                SprutCatalog.ValueType.INTEGER, 1, true, true, "uint8", "", 0, 1,
                Arrays.asList(valid(0, "open", "Open"), valid(1, "closed", "Closed")));
        SprutCatalog.Service service = service("C_1275_13", "Въезд", current, target);

        SprutPopupPreset preset = engine.recommend(accessory("Ворота", service), service);

        assertEquals(SprutPopupPreset.Presentation.COVER, preset.presentation());
        assertEquals("42/7/15", preset.primaryCharacteristicPath().get().stableId());
        assertEquals("42/7/16", preset.actionCharacteristicPath().get().stableId());
        assertTrue(target.writable());
    }

    @Test public void lightPresetTogglesSameBooleanCharacteristic() {
        SprutCatalog.Characteristic on = characteristic(20, "On",
                SprutCatalog.ValueType.BOOLEAN, false, true, true, "bool", "", 0, 1,
                Collections.emptyList());
        SprutCatalog.Service service = service("Lightbulb", "Свет", on);

        SprutPopupPreset preset = engine.recommend(accessory("Кухня", service), service);

        assertEquals("light", preset.iconId());
        assertEquals(SprutPopupPreset.Presentation.BOOLEAN, preset.presentation());
        assertEquals(preset.primaryCharacteristicPath(), preset.actionCharacteristicPath());
        assertEquals(SprutPopupPreset.ActionOperation.TOGGLE, preset.actionOperation().get());
        assertFalse(preset.defaultActionPayload().isPresent());
        assertEquals("Вкл.", preset.statusFor(true).get().label());
        assertEquals("Выкл.", preset.statusFor(0).get().label());
    }

    @Test public void temperatureSensorHasNoAction() {
        SprutCatalog.Characteristic temperature = characteristic(30, "CurrentTemperature",
                SprutCatalog.ValueType.DOUBLE, 47.5d, true, false, "float", "celsius",
                -50, 150, Collections.emptyList());
        SprutCatalog.Service service = service("TemperatureSensor", "Вода", temperature);

        SprutPopupPreset preset = engine.recommend(accessory("Бойлер", service), service);

        assertEquals("temperature", preset.iconId());
        assertEquals(SprutPopupPreset.Presentation.TEMPERATURE, preset.presentation());
        assertTrue(preset.primaryCharacteristicPath().isPresent());
        assertFalse(preset.actionCharacteristicPath().isPresent());
        assertFalse(preset.actionOperation().isPresent());
    }

    @Test public void unknownServiceInfersBooleanAndIgnoresHiddenCandidate() {
        SprutCatalog.Characteristic hidden = characteristic(40, "State",
                SprutCatalog.ValueType.STRING, "diagnostic", true, false, "string", "",
                null, null, Collections.emptyList(), true, false);
        SprutCatalog.Characteristic enabled = characteristic(41, "Enabled",
                SprutCatalog.ValueType.BOOLEAN, true, true, true, "bool", "",
                0, 1, Collections.emptyList());
        SprutCatalog.Service service = service("C_CustomThing", "Моё устройство", hidden, enabled);

        SprutPopupPreset preset = engine.recommend(accessory("Контроллер", service), service);

        assertEquals("power", preset.iconId());
        assertEquals("42/7/41", preset.primaryCharacteristicPath().get().stableId());
        assertEquals(SprutPopupPreset.Presentation.BOOLEAN, preset.presentation());
        assertEquals(SprutPopupPreset.ActionOperation.TOGGLE, preset.actionOperation().get());
    }

    @Test public void userNameDoesNotOverrideStandardServiceType() {
        SprutCatalog.Characteristic on = characteristic(45, "On",
                SprutCatalog.ValueType.BOOLEAN, false, true, true, "bool", "", 0, 1,
                Collections.emptyList());
        SprutCatalog.Service service = service("Switch", "Garage gate", on);

        SprutPopupPreset preset = engine.recommend(accessory("Controller", service), service);

        assertEquals("power", preset.iconId());
        assertEquals(SprutPopupPreset.Presentation.BOOLEAN, preset.presentation());
        assertEquals(SprutPopupPreset.ActionOperation.TOGGLE, preset.actionOperation().get());
    }

    @Test public void commonServiceFamiliesMapToAllowedIconsAndPresentations() {
        assertFamily("Switch", "On", SprutCatalog.ValueType.BOOLEAN,
                "power", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("Outlet", "On", SprutCatalog.ValueType.BOOLEAN,
                "power", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("WindowCovering", "CurrentPosition", SprutCatalog.ValueType.INTEGER,
                "gate", SprutPopupPreset.Presentation.COVER);
        assertFamily("Door", "CurrentPosition", SprutCatalog.ValueType.INTEGER,
                "gate", SprutPopupPreset.Presentation.COVER);
        assertFamily("Cover", "CurrentPosition", SprutCatalog.ValueType.INTEGER,
                "gate", SprutPopupPreset.Presentation.COVER);
        assertFamily("LockMechanism", "LockCurrentState", SprutCatalog.ValueType.INTEGER,
                "lock", SprutPopupPreset.Presentation.RAW);
        assertFamily("Thermostat", "CurrentTemperature", SprutCatalog.ValueType.DOUBLE,
                "temperature", SprutPopupPreset.Presentation.TEMPERATURE);
        assertFamily("HeaterCooler", "CurrentTemperature", SprutCatalog.ValueType.DOUBLE,
                "temperature", SprutPopupPreset.Presentation.TEMPERATURE);
        assertFamily("HumiditySensor", "CurrentRelativeHumidity", SprutCatalog.ValueType.DOUBLE,
                "water", SprutPopupPreset.Presentation.RAW);
        assertFamily("MotionSensor", "MotionDetected", SprutCatalog.ValueType.BOOLEAN,
                "door", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("OccupancySensor", "OccupancyDetected", SprutCatalog.ValueType.BOOLEAN,
                "door", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("ContactSensor", "ContactSensorState", SprutCatalog.ValueType.INTEGER,
                "door", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("LeakSensor", "LeakDetected", SprutCatalog.ValueType.INTEGER,
                "water", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("SmokeSensor", "SmokeDetected", SprutCatalog.ValueType.INTEGER,
                "power", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("CarbonMonoxideSensor", "CarbonMonoxideDetected",
                SprutCatalog.ValueType.INTEGER, "power", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("FanV2", "Active", SprutCatalog.ValueType.BOOLEAN,
                "power", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("Valve", "Active", SprutCatalog.ValueType.BOOLEAN,
                "water", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("Faucet", "Active", SprutCatalog.ValueType.BOOLEAN,
                "water", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("IrrigationSystem", "Active", SprutCatalog.ValueType.BOOLEAN,
                "water", SprutPopupPreset.Presentation.BOOLEAN);
        assertFamily("SecuritySystem", "SecuritySystemCurrentState",
                SprutCatalog.ValueType.INTEGER, "lock", SprutPopupPreset.Presentation.RAW);
        assertFamily("AirQualitySensor", "AirQuality", SprutCatalog.ValueType.INTEGER,
                "power", SprutPopupPreset.Presentation.RAW);
        assertFamily("BatteryService", "BatteryLevel", SprutCatalog.ValueType.INTEGER,
                "power", SprutPopupPreset.Presentation.RAW);
    }

    @Test public void batteryRulesUseRangesAcrossNumberTypes() {
        SprutCatalog.Characteristic level = characteristic(50, "BatteryLevel",
                SprutCatalog.ValueType.INTEGER, 50, true, false, "uint8", "percentage",
                0, 100, Collections.emptyList());
        SprutCatalog.Service service = service("BatteryService", "Батарея", level);
        SprutPopupPreset preset = engine.recommend(accessory("Датчик", service), service);

        assertEquals("Критический заряд", preset.statusFor(10L).get().label());
        assertEquals("Низкий заряд", preset.statusFor(25.0d).get().label());
        assertEquals("Заряд", preset.statusFor(80).get().label());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsServiceFromAnotherAccessory() {
        SprutCatalog.Service service = service("Switch", "Реле",
                characteristic(60, "On", SprutCatalog.ValueType.BOOLEAN, false,
                        true, true, "bool", "", 0, 1, Collections.emptyList()));
        SprutCatalog.Accessory wrong = new SprutCatalog.Accessory(99, null, "Другое", "", "",
                "", "", true, false, Collections.singletonList(service));

        engine.recommend(wrong, service);
    }

    private void assertFamily(String serviceType, String characteristicType,
                              SprutCatalog.ValueType valueType, String icon,
                              SprutPopupPreset.Presentation presentation) {
        Object value = valueType == SprutCatalog.ValueType.BOOLEAN ? false : 0;
        SprutCatalog.Characteristic characteristic = characteristic(70, characteristicType,
                valueType, value, true, false, "", "", 0, 100,
                Collections.emptyList());
        SprutCatalog.Service service = service(serviceType, serviceType, characteristic);
        SprutPopupPreset preset = engine.recommend(accessory("Device", service), service);
        assertEquals(serviceType, icon, preset.iconId());
        assertTrue("Icon must come from PopupIconCatalog: " + preset.iconId(),
                PopupIconCatalog.IDS.contains(preset.iconId()));
        assertEquals(serviceType, presentation, preset.presentation());
    }

    private static SprutCatalog.Accessory accessory(String name, SprutCatalog.Service service) {
        return new SprutCatalog.Accessory(42, 1L, name, "", "", "", "", true, false,
                Collections.singletonList(service));
    }

    private static SprutCatalog.Service service(String type, String name,
                                                SprutCatalog.Characteristic... characteristics) {
        return new SprutCatalog.Service(42, 7, name, type, 0, true, false,
                Arrays.asList(characteristics));
    }

    private static SprutCatalog.Characteristic characteristic(
            long id, String type, SprutCatalog.ValueType valueType, Object currentValue,
            boolean readable, boolean writable, String format, String unit, Number min,
            Number max, List<SprutCatalog.ValidValue> validValues) {
        return characteristic(id, type, valueType, currentValue, readable, writable, format,
                unit, min, max, validValues, false, true);
    }

    private static SprutCatalog.Characteristic characteristic(
            long id, String type, SprutCatalog.ValueType valueType, Object currentValue,
            boolean readable, boolean writable, String format, String unit, Number min,
            Number max, List<SprutCatalog.ValidValue> validValues, boolean hidden,
            boolean visible) {
        return new SprutCatalog.Characteristic(new SprutPath(42, 7, id), "", type, type,
                format, unit, readable, writable, true, visible, hidden, min, max, 1,
                validValues, currentValue, valueType);
    }

    private static SprutCatalog.ValidValue valid(Object value, String key, String name) {
        return new SprutCatalog.ValidValue(value, key, name);
    }
}
