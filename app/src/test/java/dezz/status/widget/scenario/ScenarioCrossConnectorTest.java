/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;

public final class ScenarioCrossConnectorTest {
    @Test public void haConditionEmitsSprutHubPopupVisibilityAction() {
        ValueReference haParked = new ValueReference("HOME_ASSISTANT", "default",
                "binary_sensor.car_parked", "state");
        ValueReference sprutTemperature = new ValueReference("SPRUTHUB", "default",
                "accessory.climate.temperature", "value");
        Condition condition = new Condition("car_parked", haParked, Input.FIELD_VALUE,
                Operator.TRUE, "", "");
        LocalAction showSprutPopup = new LocalAction(TargetScope.POPUP,
                "spruthub.climate_popup", LocalField.VISIBLE, true);
        Scenario scenario = new Scenario("show_climate_when_parked",
                Collections.singletonList(condition), Collections.singletonList(showSprutPopup));
        ValueResolverRegistry registry = new ValueResolverRegistry()
                .register("HOME_ASSISTANT", "default",
                        reference -> Input.value("on", true, true))
                .register("SPRUTHUB", "default",
                        reference -> Input.value(22.5, true, true));
        RuleSet sprutPopupDisplay = new RuleSet("spruthub.climate_popup", sprutTemperature,
                Collections.singletonList(new Rule("temperature", Input.FIELD_VALUE,
                        Operator.ALWAYS, "", "", new Output("{value}", null, null, null,
                        null, null))));

        ScenarioResult result = scenario.evaluate(registry);

        assertEquals("22.5", sprutPopupDisplay.evaluate(null, registry).renderedText);
        assertTrue(result.matched);
        assertEquals(Collections.singletonList("car_parked"), result.matchedConditionIds);
        assertEquals(1, result.actions.size());
        assertEquals(TargetScope.POPUP, result.actions.get(0).targetScope);
        assertEquals("spruthub.climate_popup", result.actions.get(0).targetId);
        assertEquals(LocalField.VISIBLE, result.actions.get(0).field);
        assertEquals(Boolean.TRUE, result.actions.get(0).value);

        ScenarioResult notParked = scenario.evaluate(
                ignored -> Input.value("off", true, true));
        assertFalse(notParked.matched);
        assertEquals("car_parked", notParked.failedConditionId);
        assertTrue(notParked.actions.isEmpty());
    }

    @Test public void ordinaryConditionCannotMatchExpiredReferencedValue() {
        ValueReference reference = new ValueReference("HOME_ASSISTANT", "ha", "sensor.mode", null);
        ValueResolver staleResolver = ignored -> Input.value("away", false, true);
        ValueResolver missingResolver = ignored -> Input.unavailable();

        assertFalse(new Condition("equals", reference, Input.FIELD_VALUE, Operator.EQUALS,
                "away", "").matches(staleResolver));
        assertTrue(new Condition("stale", reference, Input.FIELD_VALUE, Operator.STALE,
                "", "").matches(staleResolver));
        assertFalse(new Condition("not_equals", reference, Input.FIELD_VALUE, Operator.NOT_EQUALS,
                "home", "").matches(missingResolver));
        assertTrue(new Condition("unavailable", reference, Input.FIELD_VALUE,
                Operator.UNAVAILABLE, "", "").matches(missingResolver));
    }

    @Test public void resolverFailuresAreFailClosed() {
        ValueReference reference = new ValueReference("MQTT", "broker", "car/mode", null);
        Condition unavailable = new Condition("offline", reference, Input.FIELD_VALUE,
                Operator.UNAVAILABLE, "", "");
        Scenario scenario = new Scenario("offline_scenario",
                Collections.singletonList(unavailable), Collections.singletonList(
                        new LocalAction(TargetScope.OVERLAY, "mqtt.mode", LocalField.ICON,
                                "offline")));

        assertTrue(scenario.evaluate(ignored -> { throw new IllegalStateException("offline"); })
                .matched);
    }

    @Test public void anyModeStopsAtFirstMatchingCondition() {
        ValueReference reference = new ValueReference("MQTT", "broker", "car/door", null);
        Scenario scenario = new Scenario("door_warning", true, ConditionMode.ANY,
                java.util.Arrays.asList(
                        new Condition("closed", reference, Input.FIELD_VALUE, Operator.EQUALS,
                                "closed", ""),
                        new Condition("open", reference, Input.FIELD_VALUE, Operator.EQUALS,
                                "open", "")),
                Collections.singletonList(new LocalAction(TargetScope.MAIN, "main.door",
                        LocalField.TEXT_COLOR, "#ff0000")));

        ScenarioResult result = scenario.evaluate(ignored -> Input.value("open", true, true));
        assertTrue(result.matched);
        assertEquals(Collections.singletonList("open"), result.matchedConditionIds);
    }
}
