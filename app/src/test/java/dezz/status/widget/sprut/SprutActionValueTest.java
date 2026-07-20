/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;

public final class SprutActionValueTest {
    @Test public void booleanSetAcceptsZeroAndOneAsTypedBoolean() throws Exception {
        SprutCatalog.Characteristic relay = catalog().find(new SprutPath(103, 203, 303));

        Object off = SprutActionValue.resolve(binding(relay, "0"), relay);
        Object on = SprutActionValue.resolve(binding(relay, "1"), relay);

        assertEquals(Boolean.FALSE, off);
        assertEquals(Boolean.TRUE, on);
        assertEquals("0 (выключено)", SprutActionValue.displayValue(off));
        assertEquals("1 (включено)", SprutActionValue.displayValue(on));
    }

    @Test public void enumSetAndToggleUseExactValidValues() throws Exception {
        SprutCatalog.Characteristic door = catalog().find(new SprutPath(101, 201, 301));

        assertEquals(0, SprutActionValue.resolve(binding(door, "0"), door));
        assertEquals(0, SprutActionValue.resolve(toggle(door), door));
        assertTrue(SprutActionValue.supportsToggle(door));
    }

    @Test public void enumRejectsValueOutsideCatalog() throws Exception {
        SprutCatalog.Characteristic door = catalog().find(new SprutPath(101, 201, 301));
        try {
            SprutActionValue.resolve(binding(door, "2"), door);
            fail("Expected validValues validation");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("validValues"));
        }
    }

    @Test public void numericRangeAndStepAreChecked() throws Exception {
        SprutCatalog.Characteristic target = numericTarget();

        assertEquals(42.5d, ((Number) SprutActionValue.resolve(
                binding(target, "42.5"), target)).doubleValue(), 0.00001d);
        rejects(target, "42.25", "minStep");
        rejects(target, "61", "maxValue");
        assertFalse(SprutActionValue.supportsToggle(target));
    }

    @Test public void legacyIntegerOnIsPresentedAsBooleanAndTogglesSafely() throws Exception {
        SprutCatalog.Characteristic target = legacyIntegerOn();

        assertTrue(SprutActionValue.isBooleanLike(target));
        assertTrue(SprutActionValue.supportsToggle(target));
        assertEquals(1, SprutActionValue.resolve(toggle(target), target));
    }

    @Test public void declaredBooleanWinsOverLegacyIntegerWrapperOnWire() throws Exception {
        SprutCatalog.Characteristic target = specialTarget("bool", "intValue", "0");

        Object resolved = SprutActionValue.resolve(binding(target, "1"), target);
        assertEquals(Boolean.TRUE, resolved);
        assertTrue(SprutProtocolAdapter.buildCharacteristicUpdateParams(target.path(), resolved)
                .getJSONObject("characteristic").getJSONObject("update")
                .getJSONObject("control").getJSONObject("value").has("boolValue"));
    }

    @Test public void uint32UsesLongWithoutIntegerOverflow() throws Exception {
        SprutCatalog.Characteristic target = specialTarget(
                "uint32", "longValue", "4000000000");

        Object resolved = SprutActionValue.resolve(binding(target, "4000000000"), target);
        assertEquals(4_000_000_000L, resolved);
        assertTrue(SprutProtocolAdapter.buildCharacteristicUpdateParams(target.path(), resolved)
                .getJSONObject("characteristic").getJSONObject("update")
                .getJSONObject("control").getJSONObject("value").has("longValue"));
    }

    @Test public void signedFormatsEnforceIntrinsicRangesWithoutMetadata() throws Exception {
        assertRange("int8", "intValue", "0", "-128", "127", "-129", "128");
        assertRange("int16", "intValue", "0", "-32768", "32767", "-32769", "32768");
        assertRange("int32", "intValue", "0", "-2147483648", "2147483647",
                "-2147483649", "2147483648");
        assertRange("int64", "longValue", "0", "-9223372036854775808",
                "9223372036854775807", "-9223372036854775809", "9223372036854775808");
    }

    @Test public void unsignedFormatsEnforceIntrinsicRangesWithoutMetadata() throws Exception {
        assertRange("uint8", "intValue", "0", "0", "255", "-1", "256");
        assertRange("uint16", "intValue", "0", "0", "65535", "-1", "65536");
        assertRange("uint32", "longValue", "0", "0", "4294967295", "-1",
                "4294967296");
        // The protocol exposes uint64 through its signed longValue wrapper.
        assertRange("uint64", "longValue", "0", "0", "9223372036854775807", "-1",
                "9223372036854775808");
    }

    @Test public void payloadEncoderKeepsPrimitiveTypes() {
        assertEquals("true", SprutActionValue.encodePrimitive(true));
        assertEquals("17.5", SprutActionValue.encodePrimitive(17.5d));
        assertEquals("\"heat\"", SprutActionValue.encodePrimitive("heat"));
    }

    private static void rejects(SprutCatalog.Characteristic target, String payload,
                                String expectedMessage) {
        try {
            SprutActionValue.resolve(binding(target, payload), target);
            fail("Expected rejection");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(expectedMessage));
        }
    }

    private static void assertRange(String format, String wrapper, String current,
                                    String minimum, String maximum,
                                    String below, String above) throws Exception {
        SprutCatalog.Characteristic target = specialTarget(format, wrapper, current);
        assertEquals(format, target.format());
        assertEquals(Long.parseLong(minimum), ((Number) SprutActionValue.resolve(
                binding(target, minimum), target)).longValue());
        assertEquals(Long.parseLong(maximum), ((Number) SprutActionValue.resolve(
                binding(target, maximum), target)).longValue());
        rejects(target, below, "format range");
        rejects(target, above, "format range");
    }

    private static ActionBinding binding(SprutCatalog.Characteristic characteristic,
                                         String payload) {
        return new ActionBinding(ConnectorType.SPRUTHUB, "default",
                characteristic.path().toString(), ActionBinding.OPERATION_SET, payload);
    }

    private static ActionBinding toggle(SprutCatalog.Characteristic characteristic) {
        return new ActionBinding(ConnectorType.SPRUTHUB, "default",
                characteristic.path().toString(), ActionBinding.OPERATION_TOGGLE, "");
    }

    private static SprutCatalog catalog() throws Exception {
        return SprutProtocolAdapter.parseCatalog(SprutFixtures.ROOMS, SprutFixtures.ACCESSORIES);
    }

    private static SprutCatalog.Characteristic numericTarget() throws Exception {
        String accessories = """
                {"result":{"accessory":{"list":{"accessories":[{
                  "id":9,"name":"Climate","services":[{
                    "sId":8,"name":"Thermostat","type":"Thermostat","characteristics":[{
                      "cId":7,"name":"Target temperature","type":"TargetTemperature",
                      "format":"float","write":true,"read":true,
                      "minValue":10,"maxValue":60,"minStep":0.5,
                      "value":{"doubleValue":20.0}
                    }]
                  }]
                }]}}}}
                """;
        SprutCatalog parsed = SprutProtocolAdapter.parseCatalog(SprutFixtures.ROOMS, accessories);
        return parsed.find(new SprutPath(9, 8, 7));
    }

    private static SprutCatalog.Characteristic legacyIntegerOn() throws Exception {
        String accessories = """
                {"result":{"accessory":{"list":{"accessories":[{
                  "id":6,"name":"Relay","services":[{
                    "sId":5,"name":"Switch","type":"Switch","characteristics":[{
                      "cId":4,"name":"Power","type":"On","format":"uint8",
                      "write":true,"read":true,"minValue":0,"maxValue":1,
                      "value":{"intValue":0}
                    }]
                  }]
                }]}}}}
                """;
        SprutCatalog parsed = SprutProtocolAdapter.parseCatalog(SprutFixtures.ROOMS, accessories);
        return parsed.find(new SprutPath(6, 5, 4));
    }

    private static SprutCatalog.Characteristic specialTarget(
            String format, String wrapper, String current) throws Exception {
        String accessories = "{\"result\":{\"accessory\":{\"list\":{\"accessories\":[{"
                + "\"id\":3,\"name\":\"Target\",\"services\":[{\"sId\":2,"
                + "\"name\":\"Service\",\"characteristics\":[{\"cId\":1,"
                + "\"name\":\"Value\",\"format\":\"" + format + "\","
                + "\"write\":true,\"read\":true,\"value\":{\"" + wrapper + "\":"
                + current + "}}]}]}]}}}}";
        SprutCatalog parsed = SprutProtocolAdapter.parseCatalog(SprutFixtures.ROOMS, accessories);
        return parsed.find(new SprutPath(3, 2, 1));
    }
}
