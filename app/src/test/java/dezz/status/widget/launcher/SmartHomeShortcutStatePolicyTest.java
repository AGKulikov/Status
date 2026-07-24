/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;

public final class SmartHomeShortcutStatePolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test public void homeAssistantStateChangesStatusActiveAppearanceAndSemanticIcon() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("light_rule");
        shortcut.stateBinding = source(ConnectorType.HOME_ASSISTANT, "light.kitchen");
        IntentActionRule rule = rule("light_rule", ConnectorType.HOME_ASSISTANT,
                "light.kitchen", "Кухня", "Свет");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("device_class", "light");

        SmartHomeShortcutStatePolicy.State on = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "light.kitchen", "on",
                        true, true, "light", "", attributes)));
        assertEquals("light", on.iconKey);
        assertEquals("Вкл", on.valueLabel);
        assertTrue(on.activeKnown);
        assertTrue(on.active);
        assertTrue(on.fresh);

        SmartHomeShortcutStatePolicy.State off = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "light.kitchen", "off",
                        true, true, "light", "", attributes)));
        assertEquals("Выкл", off.valueLabel);
        assertTrue(off.activeKnown);
        assertFalse(off.active);
    }

    @Test public void homeAssistantGateIsUnlitOnlyWhenFullyClosed() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("gate_rule");
        shortcut.stateBinding = source(ConnectorType.HOME_ASSISTANT, "cover.driveway_gate");
        IntentActionRule rule = rule("gate_rule", ConnectorType.HOME_ASSISTANT,
                "cover.driveway_gate", "Въезд", "Ворота");
        Map<String, Object> attributes =
                Collections.singletonMap("device_class", "garage_door");

        SmartHomeShortcutStatePolicy.State closed = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "cover.driveway_gate", "closed",
                        true, true, "String", "", attributes)));
        assertEquals("garage", closed.iconKey);
        assertEquals("Закрыто", closed.valueLabel);
        assertTrue(closed.activeKnown);
        assertFalse(closed.active);

        SmartHomeShortcutStatePolicy.State open = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "cover.driveway_gate", "open",
                        true, true, "String", "", attributes)));
        assertEquals("garage", open.iconKey);
        assertEquals("Открыто", open.valueLabel);
        assertTrue(open.activeKnown);
        assertTrue(open.active);

        SmartHomeShortcutStatePolicy.State opening = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "cover.driveway_gate", "opening",
                        true, true, "String", "", attributes)));
        SmartHomeShortcutStatePolicy.State closing = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "cover.driveway_gate", "closing",
                        true, true, "String", "", attributes)));
        SmartHomeShortcutStatePolicy.State stopped = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.HOME_ASSISTANT, "cover.driveway_gate", "stopped",
                        true, true, "String", "", attributes)));
        assertEquals("Открывается", opening.valueLabel);
        assertTrue(opening.activeKnown);
        assertTrue(opening.active);
        assertEquals("Закрывается", closing.valueLabel);
        assertTrue(closing.active);
        assertEquals("Остановлено", stopped.valueLabel);
        assertTrue(stopped.active);
    }

    @Test public void sprutCurrentDoorStateDoesNotUseReversedBooleanNumberMeaning() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("sprut_gate");
        shortcut.stateBinding = new SourceBinding(ConnectorType.SPRUTHUB, "default", "42/7/12",
                "", SourceBinding.PRESENTATION_COVER, "");
        IntentActionRule rule = rule("sprut_gate", ConnectorType.SPRUTHUB,
                "42/7/13", "Въезд", "GarageDoorOpener");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("service_type", "GarageDoorOpener");
        attributes.put("characteristic_type", "CurrentDoorState");

        SmartHomeShortcutStatePolicy.State open = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/12", 0,
                        true, true, "INTEGER", "", attributes)));
        SmartHomeShortcutStatePolicy.State closed = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/12", 1,
                        true, true, "INTEGER", "", attributes)));
        SmartHomeShortcutStatePolicy.State opening = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/12", 2,
                        true, true, "INTEGER", "", attributes)));
        SmartHomeShortcutStatePolicy.State closing = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/12", 3,
                        true, true, "INTEGER", "", attributes)));
        SmartHomeShortcutStatePolicy.State stopped = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/12", 4,
                        true, true, "INTEGER", "", attributes)));

        assertEquals("Открыто", open.valueLabel);
        assertTrue(open.activeKnown);
        assertTrue(open.active);
        assertEquals("Закрыто", closed.valueLabel);
        assertTrue(closed.activeKnown);
        assertFalse(closed.active);
        assertEquals("Открывается", opening.valueLabel);
        assertTrue(opening.active);
        assertEquals("Закрывается", closing.valueLabel);
        assertTrue(closing.active);
        assertEquals("Остановлено", stopped.valueLabel);
        assertTrue(stopped.active);
    }

    @Test public void legacySprutGateCommandFallbackAlsoUsesDoorEnumPolarity() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("legacy_sprut_gate");
        IntentActionRule rule = rule("legacy_sprut_gate", ConnectorType.SPRUTHUB,
                "42/7/13", "Въезд", "GarageDoorOpener");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("service_type", "GarageDoorOpener");
        attributes.put("characteristic_type", "TargetDoorState");

        SmartHomeShortcutStatePolicy.State open = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/13", 0,
                        true, true, "INTEGER", "", attributes)));
        SmartHomeShortcutStatePolicy.State closed = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(
                        ConnectorType.SPRUTHUB, "42/7/13", 1,
                        true, true, "INTEGER", "", attributes)));

        assertEquals("Открыто", open.valueLabel);
        assertTrue(open.active);
        assertEquals("Закрыто", closed.valueLabel);
        assertFalse(closed.active);
    }

    @Test public void explicitMqttStateBindingDoesNotInventACommandTopic() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("gate_rule");
        shortcut.stateBinding = source(ConnectorType.MQTT, "main/gate");
        IntentActionRule rule = rule("gate_rule", ConnectorType.MQTT,
                "toggle_gate", "Ворота", "");

        SmartHomeShortcutStatePolicy.State state = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.MQTT,
                        "main/gate", true, true, true, "boolean", "",
                        Collections.singletonMap("device_class", "garage_door"))));

        assertTrue(state.present);
        assertEquals("garage", state.iconKey);
        assertEquals("Вкл", state.valueLabel);
    }

    @Test public void oldRuleFallsBackToCommandAndCustomIconIsPreserved() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("lock_rule");
        shortcut.icon = "car";
        shortcut.iconCustomized = true;
        IntentActionRule rule = rule("lock_rule", ConnectorType.SPRUTHUB,
                "1/2/3", "Входной замок", "Lock");

        SmartHomeShortcutStatePolicy.State state = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.SPRUTHUB,
                        "1/2/3", "locked", true, true, "STRING", "",
                        Collections.emptyMap())));

        assertTrue(state.present);
        assertEquals("car", state.iconKey);
        assertEquals("Закрыт", state.valueLabel);
    }

    @Test public void staleAndUnavailableValuesAreNeverPresentedAsActive() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("switch_rule");
        shortcut.stateBinding = source(ConnectorType.MQTT, "main/switch");
        IntentActionRule rule = rule("switch_rule", ConnectorType.MQTT,
                "switch", "Реле", "");

        SmartHomeShortcutStatePolicy.State stale = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.MQTT,
                        "main/switch", true, false, true, "boolean", "",
                        Collections.emptyMap())));
        assertEquals("⟳ Вкл", stale.valueLabel);
        assertFalse(stale.fresh);

        SmartHomeShortcutStatePolicy.State unavailable = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.MQTT,
                        "main/switch", true, true, false, "boolean", "",
                        Collections.emptyMap())));
        assertEquals("Недоступно", unavailable.valueLabel);
        assertFalse(unavailable.available);
    }

    @Test public void numericMqttSwitchUsesResourceSemanticsForLiveActiveState() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("mqtt_switch");
        shortcut.stateBinding = source(ConnectorType.MQTT, "garage/light/switch");
        IntentActionRule rule = rule("mqtt_switch", ConnectorType.MQTT,
                "garage/light/set", "Гараж", "Свет");

        SmartHomeShortcutStatePolicy.State on = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.MQTT,
                        "garage/light/switch", 1, true, true, "Integer", "",
                        Collections.emptyMap())));
        SmartHomeShortcutStatePolicy.State off = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.MQTT,
                        "garage/light/switch", 0, true, true, "Integer", "",
                        Collections.emptyMap())));

        assertTrue(on.activeKnown);
        assertTrue(on.active);
        assertTrue(off.activeKnown);
        assertFalse(off.active);
    }

    @Test public void numericSprutCharacteristicUsesServiceAndCharacteristicTypes() {
        LauncherShortcutStore.Shortcut shortcut = shortcut("sprut_switch");
        shortcut.stateBinding = source(ConnectorType.SPRUTHUB, "1/2/3");
        IntentActionRule rule = rule("sprut_switch", ConnectorType.SPRUTHUB,
                "1/2/3", "Реле", "Switch");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("service_type", "Switch");
        attributes.put("characteristic_type", "On");

        SmartHomeShortcutStatePolicy.State state = SmartHomeShortcutStatePolicy.resolve(
                shortcut, rule, Collections.singletonList(value(ConnectorType.SPRUTHUB,
                        "1/2/3", 1, true, true, "Integer", "", attributes)));

        assertTrue(state.activeKnown);
        assertTrue(state.active);
    }

    private static LauncherShortcutStore.Shortcut shortcut(String ruleId) {
        LauncherShortcutStore.Shortcut shortcut = new LauncherShortcutStore.Shortcut();
        shortcut.kind = LauncherShortcutStore.Kind.RULE;
        shortcut.target = ruleId;
        shortcut.title = ruleId;
        shortcut.icon = "devices";
        return shortcut;
    }

    private static SourceBinding source(ConnectorType type, String resourceId) {
        return new SourceBinding(type, "default", resourceId, "",
                SourceBinding.PRESENTATION_AUTO, "");
    }

    private static ConnectorValue value(ConnectorType type, String resourceId, Object raw,
                                        boolean fresh, boolean available, String valueType,
                                        String unit, Map<String, ?> attributes) {
        return new ConnectorValue(type, "default", resourceId, raw, fresh, available,
                true, true, valueType, unit, attributes, 100L);
    }

    private static IntentActionRule rule(String id, ConnectorType type, String resourceId,
                                         String accessory, String service) {
        ActionBinding command = new ActionBinding(type, "default", resourceId,
                type == ConnectorType.MQTT ? ActionBinding.OPERATION_PUBLISH
                        : ActionBinding.OPERATION_TOGGLE, "{}");
        return new IntentActionRule(id, true,
                IntentActionRule.secureIntentAction("test.live." + id, TOKEN), TOKEN,
                command, accessory, service, resourceId);
    }
}
