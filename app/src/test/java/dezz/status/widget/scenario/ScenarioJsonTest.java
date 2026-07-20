/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;

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

    @Test public void crossConnectorScenarioRoundTripsDeterministically() throws Exception {
        ValueReference ha = new ValueReference("HOME_ASSISTANT", "Primary HA", "ha_main",
                "binary_sensor.parked", "state");
        Scenario original = new Scenario("parked_popup", true, ConditionMode.ALL,
                Collections.singletonList(new Condition("parked", ha, Input.FIELD_VALUE,
                        Operator.TRUE, "", "")), Arrays.asList(
                        new LocalAction(TargetScope.POPUP, "spruthub.popup", LocalField.VISIBLE,
                                true),
                        new LocalAction(TargetScope.POPUP, "spruthub.popup", LocalField.ICON,
                                "thermostat")));

        Scenario decoded = Scenario.fromJson(new JSONObject(original.toJson().toString()));

        assertEquals(original, decoded);
        assertEquals(original.toJson().toString(), decoded.toJson().toString());
    }
}
