/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Locale;

import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutPath;
import dezz.status.widget.sprut.SprutProtocolAdapter;

public final class SprutCharacteristicConverterTest {
    private static final SprutPath PATH = new SprutPath(101, 201, 301);

    @Test public void appliesScaleAndOffsetToDouble() throws Exception {
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                null, null, null, null, true, "Value");
        CarSprutBinding binding = binding(2d, 1d,
                CarSprutBinding.IntegerPolicy.EXACT, "");

        Object converted = SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 3d, 1L), binding, target);

        assertEquals(7d, (Double) converted, 0d);
    }

    @Test public void usesFormatWhenCurrentValueTypeIsUnknown() throws Exception {
        SprutCatalog.Characteristic target = target("uint16", null, null,
                0, 65_535, 1, null, true, "Value");

        Object converted = SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 42d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);

        assertEquals(42, converted);
    }

    @Test public void knownFormatWinsOverConflictingRuntimeWrapper() throws Exception {
        SprutCatalog.Characteristic target = target("double", "intValue", 7,
                null, null, null, null, true, "Value");

        assertEquals(SprutCatalog.ValueType.DOUBLE,
                SprutCharacteristicConverter.validateNumericTarget(target));
        Object converted = SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 12.5d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);

        assertEquals(12.5d, (Double) converted, 0d);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedFormatWithoutRuntimeTypeIsRejected() throws Exception {
        SprutCatalog.Characteristic target = target("tlv8", null, null,
                null, null, null, null, true, "Value");

        SprutCharacteristicConverter.validateNumericTarget(target);
    }

    @Test public void unknownFormatFallsBackToRuntimeNumericType() throws Exception {
        SprutCatalog.Characteristic target = target("vendor-number", "floatValue", 0f,
                null, null, null, null, true, "Value");

        assertEquals(SprutCatalog.ValueType.FLOAT,
                SprutCharacteristicConverter.validateNumericTarget(target));
    }

    @Test(expected = IllegalArgumentException.class)
    public void exactIntegerPolicyRejectsFraction() throws Exception {
        SprutCatalog.Characteristic target = target("int32", "intValue", 0,
                null, null, null, null, true, "Value");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 1.5d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test public void explicitHalfUpPolicyRoundsPositiveAndNegativeHalves() throws Exception {
        SprutCatalog.Characteristic target = target("int32", "intValue", 0,
                null, null, null, null, true, "Value");
        CarSprutBinding binding = binding(1d, 0d,
                CarSprutBinding.IntegerPolicy.ROUND_HALF_UP, "");

        assertEquals(2, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 1.5d, 1L), binding, target));
        assertEquals(-2, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", -1.5d, 1L), binding, target));
    }

    @Test public void numericTelemetryUsesZeroAndNonZeroBooleanConvention() throws Exception {
        SprutCatalog.Characteristic target = target("bool", "boolValue", false,
                null, null, null, null, true, "Power");

        assertEquals(SprutCatalog.ValueType.BOOLEAN,
                SprutCharacteristicConverter.validateNumericTarget(target));
        assertEquals(Boolean.FALSE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 0d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
        assertEquals(Boolean.TRUE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 1d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
        assertEquals(Boolean.TRUE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", -0.25d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
    }

    @Test public void scaleAndOffsetAreAppliedBeforeBooleanConversion() throws Exception {
        SprutCatalog.Characteristic target = target("bool", "boolValue", false,
                null, null, null, null, true, "Power");
        CarSprutBinding binding = binding(2d, -8d,
                CarSprutBinding.IntegerPolicy.EXACT, "");

        assertEquals(Boolean.FALSE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 4d, 1L), binding, target));
        assertEquals(Boolean.TRUE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 4.5d, 1L), binding, target));
    }

    @Test public void booleanFormatWinsOverConflictingNumericWrapper() throws Exception {
        SprutCatalog.Characteristic target = target("bool", "doubleValue", 1d,
                null, null, null, null, true, "Power");

        assertEquals(SprutCatalog.ValueType.BOOLEAN,
                SprutCharacteristicConverter.validateNumericTarget(target));
        assertEquals(Boolean.TRUE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 1d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
    }

    @Test public void booleanConversionAcceptsBooleanAndNumericValidValues() throws Exception {
        JSONArray booleanValues = new JSONArray()
                .put(new JSONObject().put("value",
                        new JSONObject().put("boolValue", false)))
                .put(new JSONObject().put("value",
                        new JSONObject().put("boolValue", true)));
        SprutCatalog.Characteristic booleanTarget = target("bool", "boolValue", false,
                null, null, null, booleanValues, true, "Power");
        SprutCatalog.Characteristic numericTarget = target("bool", "boolValue", false,
                null, null, null, validNumbers("intValue", 0, 1), true, "Power");

        assertEquals(Boolean.FALSE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 0d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), booleanTarget));
        assertEquals(Boolean.TRUE, SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 5d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), numericTarget));
    }

    @Test public void stringFormatAllowsNumericToStringDespiteConflictingWrapper()
            throws Exception {
        SprutCatalog.Characteristic target = target("string", "boolValue", false,
                null, null, null, null, true, "Text");

        assertEquals(SprutCatalog.ValueType.STRING,
                SprutCharacteristicConverter.validateNumericTarget(target));
        assertEquals("3.25", SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 3.25d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
    }

    @Test public void numericStringIsLocaleIndependentAndNeverUsesComma() throws Exception {
        SprutCatalog.Characteristic target = target("string", "stringValue", "old",
                null, null, null, null, true, "Text");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals("12.5", SprutCharacteristicConverter.convert(
                    new CarTelemetryValue("fuel", 12.5d, 1L),
                    binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test public void acceptsRangeStepAndValidValue() throws Exception {
        JSONArray valid = validNumbers("doubleValue", 1.5d, 2d);
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                0d, 10d, 0.5d, valid, true, "Value");

        assertEquals(1.5d, (Double) SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 1.5d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target), 0d);
    }

    @Test public void sdkFloatDecimalAlignsToCharacteristicStep() throws Exception {
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                0d, 100d, 0.1d, null, true, "Temperature");

        Object converted = SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", 20.4f, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);

        assertEquals(20.4d, (Double) converted, 0d);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsValueNotAlignedToMinimumStep() throws Exception {
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                0d, 10d, 0.5d, null, true, "Value");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 1.25d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsValueOutsideMetadataRange() throws Exception {
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                0d, 10d, null, null, true, "Value");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 11d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsValueOutsideValidValues() throws Exception {
        SprutCatalog.Characteristic target = target("int32", "intValue", 0,
                null, null, null, validNumbers("intValue", 1, 2), true, "Option");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 3d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsignedUnderflowFromFormat() throws Exception {
        SprutCatalog.Characteristic target = target("uint8", null, null,
                null, null, null, null, true, "Value");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", -1d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void integerOverflowIsReportedAsValidationFailure() throws Exception {
        SprutCatalog.Characteristic target = target("int", "intValue", 0,
                null, null, null, null, true, "Value");
        SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", (double) Integer.MAX_VALUE + 1d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFloatOverflow() throws Exception {
        SprutCatalog.Characteristic target = target("float", "floatValue", 0f,
                null, null, null, null, true, "Value");
        SprutCharacteristicConverter.convert(
                new CarTelemetryValue("fuel", Double.MAX_VALUE, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsReadOnlyTarget() throws Exception {
        SprutCatalog.Characteristic target = target("double", "doubleValue", 0d,
                null, null, null, null, false, "Value");
        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 1d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, ""), target);
    }

    @Test(expected = IllegalArgumentException.class)
    public void validatorRejectsReadOnlyTargetBeforeAnyCarValueExists() throws Exception {
        SprutCatalog.Characteristic target = target("double", null, null,
                null, null, null, null, false, "Value");

        SprutCharacteristicConverter.validateNumericTarget(target);
    }

    @Test public void snapshotSignatureIgnoresDisplayRenameButTracksConstraints() throws Exception {
        SprutCatalog.Characteristic original = target("double", "doubleValue", 0d,
                0d, 10d, 0.5d, null, true, "Original name");
        SprutCatalog.Characteristic renamed = target("double", "doubleValue", 0d,
                0d, 10d, 0.5d, null, true, "Renamed");
        SprutCatalog.Characteristic changed = target("double", "doubleValue", 0d,
                0d, 20d, 0.5d, null, true, "Original name");

        assertEquals(SprutCharacteristicConverter.snapshotSignature(original),
                SprutCharacteristicConverter.snapshotSignature(renamed));
        assertNotEquals(SprutCharacteristicConverter.snapshotSignature(original),
                SprutCharacteristicConverter.snapshotSignature(changed));
    }

    @Test public void snapshotSignatureDoesNotChangeWhenFormattedValueFirstArrives()
            throws Exception {
        SprutCatalog.Characteristic pending = target("double", null, null,
                0d, 10d, null, null, true, "Value");
        SprutCatalog.Characteristic populated = target("double", "doubleValue", 1d,
                0d, 10d, null, null, true, "Value");

        assertEquals(SprutCharacteristicConverter.snapshotSignature(pending),
                SprutCharacteristicConverter.snapshotSignature(populated));
    }

    @Test public void formatFirstSignatureIgnoresConflictingRuntimeWrapper() throws Exception {
        SprutCatalog.Characteristic integerWrapped = target("double", "intValue", 1,
                0d, 10d, null, null, true, "Value");
        SprutCatalog.Characteristic doubleWrapped = target("double", "doubleValue", 1d,
                0d, 10d, null, null, true, "Value");

        assertEquals(SprutCharacteristicConverter.snapshotSignature(integerWrapped),
                SprutCharacteristicConverter.snapshotSignature(doubleWrapped));
    }

    @Test(expected = IllegalArgumentException.class)
    public void snapshotMismatchStopsWrite() throws Exception {
        SprutCatalog.Characteristic selected = target("double", "doubleValue", 0d,
                0d, 10d, null, null, true, "Value");
        SprutCatalog.Characteristic current = target("double", "doubleValue", 0d,
                0d, 20d, null, null, true, "Value");
        String signature = SprutCharacteristicConverter.snapshotSignature(selected);

        SprutCharacteristicConverter.convert(new CarTelemetryValue("fuel", 5d, 1L),
                binding(1d, 0d, CarSprutBinding.IntegerPolicy.EXACT, signature), current);
    }

    private static CarSprutBinding binding(double scale, double offset,
                                           CarSprutBinding.IntegerPolicy policy,
                                           String signature) {
        return new CarSprutBinding("fuel", true, PATH, signature,
                "Device", "Service", "Value", scale, offset, policy, 1_000L);
    }

    private static JSONArray validNumbers(String wrapper, Number... numbers) throws Exception {
        JSONArray result = new JSONArray();
        for (Number number : numbers) {
            result.put(new JSONObject().put("value", new JSONObject().put(wrapper, number)));
        }
        return result;
    }

    private static SprutCatalog.Characteristic target(
            String format, String wrapper, Object currentValue, Number minimum, Number maximum,
            Number step, JSONArray validValues, boolean writable, String name) throws Exception {
        JSONObject control = new JSONObject()
                .put("name", name)
                .put("type", "C_OPTION")
                .put("format", format)
                .put("unit", "none")
                .put("read", true)
                .put("write", writable);
        if (wrapper != null) {
            control.put("value", new JSONObject().put(wrapper, currentValue));
        }
        if (minimum != null) control.put("minValue", minimum);
        if (maximum != null) control.put("maxValue", maximum);
        if (step != null) control.put("minStep", step);
        if (validValues != null) control.put("validValues", validValues);

        JSONObject characteristic = new JSONObject().put("cId", PATH.characteristicId())
                .put("control", control);
        JSONObject service = new JSONObject().put("sId", PATH.serviceId())
                .put("name", "Service")
                .put("type", "Virtual")
                .put("characteristics", new JSONArray().put(characteristic));
        JSONObject accessory = new JSONObject().put("id", PATH.accessoryId())
                .put("name", "Device")
                .put("services", new JSONArray().put(service));
        JSONObject accessories = new JSONObject().put("result", new JSONObject()
                .put("accessory", new JSONObject().put("list", new JSONObject()
                        .put("accessories", new JSONArray().put(accessory)))));
        JSONObject rooms = new JSONObject().put("result", new JSONObject()
                .put("room", new JSONObject().put("list", new JSONObject()
                        .put("rooms", new JSONArray()))));
        return SprutProtocolAdapter.parseCatalog(rooms, accessories).find(PATH);
    }
}
