/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class ScenarioJsonTest {
    @Test public void displayRuleSetRoundTripsWithExplicitSource() throws Exception {
        ValueReference source = new ValueReference("SPRUTHUB", "Car hub", "sprut_car",
                "accessory.climate.temperature", "value");
        RuleSet original = new RuleSet("climate", source, Arrays.asList(
                new Rule("cold", Input.FIELD_VALUE, Operator.LESS, "5", "",
                        new Output("Cold {value}", "#0000ff", "snow", null, true, false)),
                new Rule("raw", Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                        new Output("{value}", null, null, null, null, null))));

        RuleSet decoded = RuleSet.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(original, decoded);
        assertEquals(original.toJson().toString(), decoded.toJson().toString());
    }

    @Test public void customRangeAndTransparentPresentationRoundTrip() throws Exception {
        RuleSet original = new RuleSet("custom.temperature", Arrays.asList(
                new Rule("warm", Input.FIELD_VALUE, Operator.BETWEEN_EXCLUSIVE,
                        "40", "60", new Output(null, "#FF9C27B0", null, null,
                        null, null)),
                new Rule("fallback", Input.FIELD_VALUE, Operator.ALWAYS, "", "",
                        new Output("{value}", "transparent", null, null, null, null))));

        RuleSet decoded = RuleSet.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(original, decoded);
        assertNull(decoded.rules.get(0).output.textTemplate);
        assertEquals("transparent", decoded.rules.get(1).output.textColor);
    }

    @Test public void crossConnectorScenarioRoundTripsDeterministically() throws Exception {
        ValueReference ha = new ValueReference("HOME_ASSISTANT", "Primary HA", "ha_main",
                "binary_sensor.parked", "state");
        LocalAction hide = new LocalAction(TargetScope.OVERLAY, "home",
                LocalField.VISIBLE, false);
        Scenario original = new Scenario("parked_popup", true, ConditionMode.ALL,
                Collections.singletonList(new Condition("parked", ha, Input.FIELD_VALUE,
                        Operator.TRUE, "", "")), Arrays.asList(
                        new LocalAction(TargetScope.POPUP, "spruthub.popup", LocalField.VISIBLE,
                                true),
                        new LocalAction(TargetScope.POPUP, "spruthub.popup", LocalField.ICON,
                                "thermostat")), Collections.singletonList(hide));

        Scenario decoded = Scenario.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(original, decoded);
        assertEquals(original.toJson().toString(), decoded.toJson().toString());
    }

    @Test public void schemaLessLegacyScenarioKeepsFailClosedSemanticsOnRoundTrip()
            throws Exception {
        ValueReference source = new ValueReference("HOME_ASSISTANT", "default",
                "input_boolean.near_home", null);
        JSONObject legacy = new JSONObject()
                .put("id", "legacy_gate")
                .put("enabled", true)
                .put("mode", "ALL")
                .put("conditions", new JSONArray().put(new Condition("condition", source,
                        Input.FIELD_VALUE, Operator.TRUE, "", "").toJson()))
                .put("actions", new JSONArray().put(new LocalAction(TargetScope.OVERLAY,
                        "home", LocalField.VISIBLE, true).toJson()));

        Scenario decoded = Scenario.fromJson(legacy);
        JSONObject encoded = decoded.toJson();
        Scenario roundTrip = Scenario.fromJson(encoded);

        assertTrue(decoded.legacyFailClosed);
        assertEquals(1, encoded.getInt("schemaVersion"));
        assertFalse(encoded.has("elseActions"));
        assertEquals(decoded, roundTrip);
    }

    @Test public void scenarioConditionNormalizesOperandsForSelectedOperator() {
        ValueReference source = new ValueReference("MQTT", "default", "sensor/value", null);

        Condition booleanCondition = new Condition("boolean", source, Input.FIELD_VALUE,
                Operator.TRUE, "leftover", "also-leftover");
        Condition numberCondition = new Condition("number", source, Input.FIELD_VALUE,
                Operator.GREATER, " 40 ", "unused");
        Condition rangeCondition = new Condition("range", source, Input.FIELD_VALUE,
                Operator.BETWEEN, " 40 ", " 60 ");
        Condition textCondition = new Condition("text", source, Input.FIELD_VALUE,
                Operator.EQUALS, " open ", "unused");

        assertEquals("", booleanCondition.operand);
        assertEquals("", booleanCondition.secondOperand);
        assertEquals("40", numberCondition.operand);
        assertEquals("", numberCondition.secondOperand);
        assertEquals("40", rangeCondition.operand);
        assertEquals("60", rangeCondition.secondOperand);
        assertEquals(" open ", textCondition.operand);
        assertEquals("", textCondition.secondOperand);
    }
}
