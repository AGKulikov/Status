/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutProtocolAdapter;

public final class SmartHomeShortcutStateBindingPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test public void legacyGarageTargetBindingIsReconciledToCurrentDoorState() throws Exception {
        SprutCatalog catalog = garageCatalog(true);
        IntentActionRule rule = rule("42/7/13");
        LauncherShortcutStore.Shortcut shortcut = shortcut(rule.id);
        shortcut.stateBinding = source("42/7/13", SourceBinding.PRESENTATION_AUTO);

        SourceBinding resolved =
                SmartHomeShortcutStateBindingPolicy.resolve(shortcut, rule, catalog);

        assertEquals("42/7/12", resolved.resourceId);
        assertEquals(SourceBinding.PRESENTATION_COVER, resolved.presentation);
    }

    @Test public void movingGarageUsesCurrentStateRatherThanAlreadyChangedTarget()
            throws Exception {
        SprutCatalog catalog = garageCatalog(true);
        IntentActionRule rule = rule("42/7/13");
        LauncherShortcutStore.Shortcut shortcut = shortcut(rule.id);
        shortcut.stateBinding = source("42/7/13", SourceBinding.PRESENTATION_AUTO);
        SourceBinding resolved =
                SmartHomeShortcutStateBindingPolicy.resolve(shortcut, rule, catalog);

        SmartHomeShortcutStatePolicy.State opening = SmartHomeShortcutStatePolicy.resolveValue(
                shortcut, rule, resolved, currentDoorValue(2));
        SmartHomeShortcutStatePolicy.State closing = SmartHomeShortcutStatePolicy.resolveValue(
                shortcut, rule, resolved, currentDoorValue(3));
        SmartHomeShortcutStatePolicy.State open = SmartHomeShortcutStatePolicy.resolveValue(
                shortcut, rule, resolved, currentDoorValue(0));
        SmartHomeShortcutStatePolicy.State closed = SmartHomeShortcutStatePolicy.resolveValue(
                shortcut, rule, resolved, currentDoorValue(1));

        assertEquals("Открывается", opening.valueLabel);
        assertEquals("Закрывается", closing.valueLabel);
        assertEquals("Открыто", open.valueLabel);
        assertEquals("Закрыто", closed.valueLabel);
        assertTrue(opening.active);
        assertTrue(closing.active);
        assertTrue(open.active);
        assertFalse(closed.active);
    }

    @Test public void explicitlySelectedDifferentStateSourceIsPreserved() throws Exception {
        SprutCatalog catalog = garageCatalog(true);
        ActionBinding command = command("42/7/13");
        SourceBinding explicit = source("42/7/12", SourceBinding.PRESENTATION_RAW);

        SourceBinding resolved = SmartHomeShortcutStateBindingPolicy.preferSprutPrimary(
                command, explicit, catalog);

        assertSame(explicit, resolved);
    }

    @Test public void targetOnlyCoverIsNotPresentedAsConfirmedState() throws Exception {
        SprutCatalog catalog = garageCatalog(false);
        ActionBinding command = command("42/7/13");

        SourceBinding resolved = SmartHomeShortcutStateBindingPolicy.preferSprutPrimary(
                command, source("42/7/13", SourceBinding.PRESENTATION_AUTO), catalog);

        assertNull(resolved);
    }

    private static SprutCatalog garageCatalog(boolean includeCurrent) throws Exception {
        String current = includeCurrent ? """
                ,{
                  "cId":12,
                  "control":{
                    "name":"Current state",
                    "type":"CurrentDoorState",
                    "format":"uint8",
                    "read":true,
                    "write":false,
                    "events":true,
                    "value":{"intValue":2}
                  }
                }
                """ : "";
        JSONObject rooms = new JSONObject("""
                {"result":{"room":{"list":{"rooms":[{"id":1,"name":"Entry"}]}}}}
                """);
        JSONObject accessories = new JSONObject("""
                {
                  "result":{"accessory":{"list":{"accessories":[{
                    "id":42,
                    "roomId":1,
                    "name":"Gate",
                    "online":true,
                    "services":[{
                      "sId":7,
                      "name":"Garage",
                      "type":"GarageDoorOpener",
                      "characteristics":[{
                        "cId":13,
                        "control":{
                          "name":"Target state",
                          "type":"TargetDoorState",
                          "format":"uint8",
                          "read":true,
                          "write":true,
                          "events":true,
                          "value":{"intValue":0},
                          "validValues":[
                            {"value":{"intValue":0},"key":"open","name":"Open"},
                            {"value":{"intValue":1},"key":"closed","name":"Closed"}
                          ]
                        }
                      }%s]
                    }]
                  }]}}}
                }
                """.formatted(current));
        return SprutProtocolAdapter.parseCatalog(rooms, accessories);
    }

    private static ConnectorValue currentDoorValue(int state) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("service_type", "GarageDoorOpener");
        attributes.put("characteristic_type", "CurrentDoorState");
        return new ConnectorValue(ConnectorType.SPRUTHUB, "default", "42/7/12", state,
                true, true, true, false, "INTEGER", "", attributes, 100L);
    }

    private static LauncherShortcutStore.Shortcut shortcut(String id) {
        LauncherShortcutStore.Shortcut shortcut = new LauncherShortcutStore.Shortcut();
        shortcut.kind = LauncherShortcutStore.Kind.RULE;
        shortcut.target = id;
        shortcut.title = "Gate";
        shortcut.icon = "garage";
        return shortcut;
    }

    private static IntentActionRule rule(String resourceId) {
        return new IntentActionRule("gate_rule", true,
                IntentActionRule.secureIntentAction("test.gate", TOKEN), TOKEN,
                command(resourceId), "Gate", "GarageDoorOpener", resourceId);
    }

    private static ActionBinding command(String resourceId) {
        return new ActionBinding(ConnectorType.SPRUTHUB, "default", resourceId,
                ActionBinding.OPERATION_TOGGLE, "{}");
    }

    private static SourceBinding source(String resourceId, String presentation) {
        return new SourceBinding(ConnectorType.SPRUTHUB, "default", resourceId, "",
                presentation, "");
    }
}
