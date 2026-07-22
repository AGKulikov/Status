/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import dezz.status.widget.sprut.SprutPath;

public final class CarTelemetryBindingTest {
    @Test public void floatSampleKeepsSdkDecimalValue() {
        CarTelemetryValue value = new CarTelemetryValue(
                "ISensor.indoor_temp", 20.4f, 123L, "°C");

        assertEquals(20.4d, value.value, 0d);
    }

    @Test public void integrationSampleKeepsLargeIntegerPrecisionAndFloatDecimal() {
        CarIntegration.TelemetryValue integer = new CarIntegration.TelemetryValue(
                "odometer", "Odometer", 20_000_001d, "raw", 1L);
        CarIntegration.TelemetryValue decimal = new CarIntegration.TelemetryValue(
                "temperature", "Temperature", 20.4f, "°C", 1L);

        assertEquals(20_000_001d, integer.value, 0d);
        assertEquals(20.4d, decimal.value, 0d);
    }

    @Test public void telemetryValueKeepsStableTypedIdentity() {
        CarTelemetryValue value = new CarTelemetryValue(
                "ISensor.fuel_level", 31.5d, 1234L, "raw");

        assertEquals("ISensor.fuel_level", value.metricId);
        assertEquals(31.5d, value.value, 0d);
        assertEquals(1234L, value.observedAtMillis);
        assertEquals("raw", value.unit);
    }

    @Test(expected = IllegalArgumentException.class)
    public void telemetryRejectsUnstableMetricId() {
        new CarTelemetryValue("fuel/level", 1d, 0L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void telemetryRejectsNonFiniteNumber() {
        new CarTelemetryValue("fuel_level", Double.NaN, 0L);
    }

    @Test public void bindingSchemaOneRoundTripsAllSafetyMetadata() throws Exception {
        CarSprutBinding source = new CarSprutBinding(
                "ISensor.fuel_level", true, new SprutPath(11, 22, 33), "abc123",
                "Fuel device", "c_option", "Current value", 0.75d, -1.25d,
                CarSprutBinding.IntegerPolicy.ROUND_HALF_UP, 2_500L);

        JSONObject json = source.toJson();
        CarSprutBinding decoded = CarSprutBinding.fromJson(json);

        assertEquals(1, json.getInt("schema"));
        assertEquals(source.metricId, decoded.metricId);
        assertTrue(decoded.enabled);
        assertEquals(source.targetPath, decoded.targetPath);
        assertEquals("abc123", decoded.targetSnapshotSignature);
        assertEquals("Fuel device", decoded.targetAccessoryName);
        assertEquals("c_option", decoded.targetServiceName);
        assertEquals("Current value", decoded.targetCharacteristicName);
        assertEquals(0.75d, decoded.scale, 0d);
        assertEquals(-1.25d, decoded.offset, 0d);
        assertEquals(CarSprutBinding.IntegerPolicy.ROUND_HALF_UP, decoded.integerPolicy);
        assertEquals(2_500L, decoded.minIntervalMs);
    }

    @Test public void bindingDefaultsChooseExactIntegerPolicyExplicitly() throws Exception {
        CarSprutBinding binding = CarSprutBinding.fromJson(new JSONObject()
                .put("schema", 1)
                .put("metricId", "speed")
                .put("targetPath", "1/2/3"));

        assertEquals(CarSprutBinding.IntegerPolicy.EXACT, binding.integerPolicy);
        assertEquals(1d, binding.scale, 0d);
        assertEquals(0d, binding.offset, 0d);
        assertEquals(1_000L, binding.minIntervalMs);
    }

    @Test(expected = IllegalArgumentException.class)
    public void bindingRejectsUnknownFutureSchema() throws Exception {
        CarSprutBinding.fromJson(new JSONObject()
                .put("schema", 2)
                .put("metricId", "speed")
                .put("targetPath", "1/2/3"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void bindingRejectsFractionalMinimumInterval() throws Exception {
        CarSprutBinding.fromJson(new JSONObject()
                .put("schema", 1)
                .put("metricId", "speed")
                .put("targetPath", "1/2/3")
                .put("minIntervalMs", 12.5d));
    }

    @Test(expected = IllegalArgumentException.class)
    public void bindingRejectsUnboundedMinimumInterval() throws Exception {
        CarSprutBinding.fromJson(new JSONObject()
                .put("schema", 1)
                .put("metricId", "speed")
                .put("targetPath", "1/2/3")
                .put("minIntervalMs", CarSprutBinding.MAX_MIN_INTERVAL_MS + 1L));
    }

    @Test public void storeRoundTripsAndPreservesOrder() throws Exception {
        CarSprutBinding fuel = CarSprutBinding.create("fuel", new SprutPath(1, 2, 3));
        CarSprutBinding speed = CarSprutBinding.create("speed", new SprutPath(4, 5, 6));

        List<CarSprutBinding> decoded = CarSprutBindingStore.decode(
                CarSprutBindingStore.encode(Arrays.asList(fuel, speed)));

        assertEquals(2, decoded.size());
        assertEquals("fuel", decoded.get(0).metricId);
        assertEquals("speed", decoded.get(1).metricId);
    }

    @Test public void storeIsolatesMalformedAndDuplicateItems() throws Exception {
        JSONObject valid = CarSprutBinding.create("fuel", new SprutPath(1, 2, 3)).toJson();
        JSONObject duplicate = CarSprutBinding.create("fuel", new SprutPath(8, 8, 8)).toJson();
        JSONObject malformed = new JSONObject().put("metricId", "bad/path")
                .put("targetPath", "1/2/3");
        JSONArray array = new JSONArray().put(valid).put(malformed).put(duplicate);

        List<CarSprutBinding> decoded = CarSprutBindingStore.decode(array.toString());

        assertEquals(1, decoded.size());
        assertEquals(new SprutPath(1, 2, 3), decoded.get(0).targetPath);
        assertTrue(CarSprutBindingStore.decode("not json").isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void storeRejectsDuplicateMetricsOnWrite() throws Exception {
        CarSprutBinding one = CarSprutBinding.create("fuel", new SprutPath(1, 2, 3));
        CarSprutBinding two = CarSprutBinding.create("fuel", new SprutPath(4, 5, 6));
        CarSprutBindingStore.encode(Arrays.asList(one, two));
    }

    @Test(expected = IllegalArgumentException.class)
    public void storeRejectsDuplicateTargetsOnWrite() throws Exception {
        CarSprutBinding first = CarSprutBinding.create("fuel", new SprutPath(1, 2, 3));
        CarSprutBinding second = CarSprutBinding.create("range", new SprutPath(1, 2, 3));

        CarSprutBindingStore.encode(Arrays.asList(first, second));
    }

    @Test public void storeKeepsOnlyFirstOwnerOfImportedTarget() throws Exception {
        JSONArray imported = new JSONArray()
                .put(CarSprutBinding.create("fuel", new SprutPath(1, 2, 3)).toJson())
                .put(CarSprutBinding.create("range", new SprutPath(1, 2, 3)).toJson());

        List<CarSprutBinding> decoded = CarSprutBindingStore.decode(imported.toString());

        assertEquals(1, decoded.size());
        assertEquals("fuel", decoded.get(0).metricId);
    }

    @Test public void disabledBindingDoesNotHideEnabledImportedTarget() throws Exception {
        CarSprutBinding disabled = new CarSprutBinding("old", false,
                new SprutPath(1, 2, 3), "", "", "", "", 1d, 0d,
                CarSprutBinding.IntegerPolicy.EXACT, 0L);
        CarSprutBinding enabled = CarSprutBinding.create("fuel", new SprutPath(1, 2, 3));
        JSONArray imported = new JSONArray().put(disabled.toJson()).put(enabled.toJson());

        List<CarSprutBinding> decoded = CarSprutBindingStore.decode(imported.toString());

        assertEquals(2, decoded.size());
        assertFalse(decoded.get(0).enabled);
        assertTrue(decoded.get(1).enabled);
        // Saving the repaired import must also allow the disabled historical mapping.
        CarSprutBindingStore.encode(decoded);
    }

    @Test public void disabledFlagSurvivesJson() throws Exception {
        CarSprutBinding disabled = new CarSprutBinding("fuel", false,
                new SprutPath(1, 2, 3), "", "", "", "", 1d, 0d,
                CarSprutBinding.IntegerPolicy.EXACT, 0L);

        assertFalse(CarSprutBinding.fromJson(disabled.toJson()).enabled);
    }
}
