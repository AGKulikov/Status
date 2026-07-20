/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.scenario.Condition;
import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.LocalAction;
import dezz.status.widget.scenario.LocalField;
import dezz.status.widget.scenario.Operator;
import dezz.status.widget.scenario.Scenario;
import dezz.status.widget.scenario.TargetScope;
import dezz.status.widget.scenario.ValueReference;

public final class LocalScenarioControllerTest {
    @Test public void malformedEntryDoesNotDisableValidSiblings() throws Exception {
        Scenario first = scenario("first", "sensor.one", "popup.one");
        Scenario second = scenario("second", "sensor.two", "popup.two");
        JSONObject invalid = new JSONObject(first.toJson().toString());
        invalid.put("id", "not a safe id");
        JSONArray input = new JSONArray()
                .put(first.toJson())
                .put("not an object")
                .put(invalid)
                .put(second.toJson());

        List<Scenario> parsed = LocalScenarioController.parse(input.toString());

        assertEquals(2, parsed.size());
        assertEquals("first", parsed.get(0).id);
        assertEquals("second", parsed.get(1).id);
    }

    @Test public void equalSemanticPatchesDoNotProduceChangedTargets() throws Exception {
        Map<String, JSONObject> previous = new LinkedHashMap<>();
        previous.put("popup|gate", new JSONObject()
                .put("visible", true).put("icon", "gate"));
        Map<String, JSONObject> reordered = new LinkedHashMap<>();
        reordered.put("popup|gate", new JSONObject()
                .put("icon", "gate").put("visible", true));

        assertTrue(LocalScenarioController.changedTargets(previous, reordered).isEmpty());

        reordered.get("popup|gate").put("visible", false);
        assertEquals(Collections.singleton("popup|gate"),
                LocalScenarioController.changedTargets(previous, reordered));
    }

    @Test public void addedAndRemovedTargetsAreBothReported() throws Exception {
        Map<String, JSONObject> previous = new LinkedHashMap<>();
        previous.put("main|old", new JSONObject().put("visible", true));
        Map<String, JSONObject> current = new LinkedHashMap<>();
        current.put("popup|new", new JSONObject().put("visible", true));

        assertEquals(new java.util.LinkedHashSet<>(
                        java.util.Arrays.asList("main|old", "popup|new")),
                LocalScenarioController.changedTargets(previous, current));
    }

    @Test public void positiveVisibilityIsFailClosedUntilConditionMatches() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        Scenario gate = scenario("gate", "input_boolean.near_home", "sprut_gate", true);

        registry.upsert(value("input_boolean.near_home", false));
        JSONObject unmatched = LocalScenarioController.buildOverrides(
                Collections.singletonList(gate), registry).get("popup|sprut_gate");
        assertFalse(unmatched.optBoolean("visible", true));

        registry.upsert(value("input_boolean.near_home", true));
        JSONObject matched = LocalScenarioController.buildOverrides(
                Collections.singletonList(gate), registry).get("popup|sprut_gate");
        assertTrue(matched.optBoolean("visible", false));
    }

    @Test public void falseVisibilityOnlyBlocksWhenConditionMatches() {
        ConnectorValueRegistry registry = new ConnectorValueRegistry();
        Scenario blocker = scenario("blocker", "input_boolean.away", "sprut_gate", false);

        registry.upsert(value("input_boolean.away", false));
        assertNull(LocalScenarioController.buildOverrides(Collections.singletonList(blocker),
                registry).get("popup|sprut_gate"));

        registry.upsert(value("input_boolean.away", true));
        JSONObject matched = LocalScenarioController.buildOverrides(
                Collections.singletonList(blocker), registry).get("popup|sprut_gate");
        assertFalse(matched.optBoolean("visible", true));
    }

    private static Scenario scenario(String id, String resourceId, String targetId) {
        return scenario(id, resourceId, targetId, true);
    }

    private static Scenario scenario(String id, String resourceId, String targetId,
                                     boolean visible) {
        ValueReference reference = new ValueReference("HOME_ASSISTANT", "default", resourceId,
                null);
        Condition condition = new Condition("condition", reference, Input.FIELD_VALUE,
                Operator.TRUE, "", "");
        LocalAction action = new LocalAction(TargetScope.POPUP, targetId, LocalField.VISIBLE,
                visible);
        return new Scenario(id, Collections.singletonList(condition),
                Collections.singletonList(action));
    }

    private static ConnectorValue value(String resourceId, boolean raw) {
        return ConnectorValue.current(ConnectorType.HOME_ASSISTANT, "default", resourceId, raw,
                true, true, false, "boolean", "", Collections.emptyMap());
    }
}
