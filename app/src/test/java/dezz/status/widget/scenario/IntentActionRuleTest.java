/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;

public final class IntentActionRuleTest {
    static final String ACTION_TOKEN = "fedcba9876543210fedcba9876543210";
    static final String TRIGGER_TOKEN = "0123456789abcdef0123456789abcdef";

    @Test public void sprutSetRuleRoundTripsWithLabels() throws Exception {
        IntentActionRule original = rule("parking", true, "sh.car.parkovka",
                ActionBinding.OPERATION_SET, "true");

        IntentActionRule decoded = IntentActionRule.fromJson(
                new JSONObject(original.toJson().toString()));

        assertEquals(original, decoded);
        assertEquals(original.toJson().toString(), decoded.toJson().toString());
        assertEquals(1, decoded.schema);
        assertEquals(TRIGGER_TOKEN, decoded.triggerToken);
        assertEquals("sh.car.parkovka.x" + ACTION_TOKEN, decoded.intentAction);
        assertEquals(original.executionFingerprint(), decoded.executionFingerprint());
        assertTrue(decoded.matchesExecutionFingerprint(decoded.executionFingerprint()));
        assertEquals("Ворота", decoded.accessoryLabel);
        assertEquals("Целевое состояние", decoded.characteristicLabel);
    }

    @Test public void actionIsTrimmedButRemainsCaseSensitive() {
        String upper = secureAction("sh.car.Parkovka");
        String lower = secureAction("sh.car.parkovka");
        assertEquals(upper, IntentActionRule.validateIntentAction("  " + upper + "  "));
        assertFalse(IntentActionRule.validateIntentAction(upper)
                .equals(IntentActionRule.validateIntentAction(lower)));
        assertEquals("sh.car.Parkovka", IntentActionRule.intentActionPrefix(upper));
    }

    @Test(expected = IllegalArgumentException.class)
    public void humanPrefixWithoutGeneratedSuffixIsRejected() {
        IntentActionRule.validateIntentAction("sh.car.parkovka");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unqualifiedActionIsRejected() {
        IntentActionRule.validateIntentAction("parkovka.x" + ACTION_TOKEN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void frameworkActionIsRejected() {
        IntentActionRule.validateIntentAction(
                "android.intent.action.BOOT_COMPLETED.x" + ACTION_TOKEN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void platformPackageActionIsRejected() {
        IntentActionRule.validateIntentAction(
                "com.android.systemui.CUSTOM_ACTION.x" + ACTION_TOKEN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedHaEndpointCannotBeShadowed() {
        IntentActionRule.validateIntentAction(
                "ru.natro.statuswidget.HA_UPDATE.x" + ACTION_TOKEN);
    }

    @Test(expected = IllegalArgumentException.class)
    public void fixedScenarioEndpointCannotBeShadowed() {
        IntentActionRule.validateIntentAction(
                "ru.natro.statuswidget.SCENARIO_TRIGGER.x" + ACTION_TOKEN);
    }

    @Test public void generatedTriggerTokenHasFullRandomHexShape() {
        String token = IntentActionRule.newTriggerToken();
        assertEquals(32, token.length());
        assertTrue(token.matches("[a-f0-9]{32}"));
    }

    @Test public void commandEditChangesExecutionFingerprint() {
        IntentActionRule before = rule("parking", true, "sh.car.parkovka",
                ActionBinding.OPERATION_SET, "true");
        IntentActionRule after = rule("parking", true, "sh.car.parkovka",
                ActionBinding.OPERATION_SET, "false");

        assertFalse(before.executionFingerprint().equals(after.executionFingerprint()));
        assertFalse(after.matchesExecutionFingerprint(before.executionFingerprint()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingTriggerTokenIsRejected() {
        new IntentActionRule("parking", true, secureAction("sh.car.parkovka"), "",
                new ActionBinding(ConnectorType.SPRUTHUB, "default", "13/15/16",
                        ActionBinding.OPERATION_SET, "true"), "", "", "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownSchemaIsRejected() throws Exception {
        JSONObject object = rule("parking", true, "sh.car.parkovka",
                ActionBinding.OPERATION_TOGGLE, "{}").toJson();
        object.put("schema", 2);
        IntentActionRule.fromJson(object);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unboundCommandIsRejected() {
        new IntentActionRule("parking", true, secureAction("sh.car.parkovka"), TRIGGER_TOKEN,
                ActionBinding.unbound(), "", "", "");
    }

    @Test public void disabledRuleIsStillAValidDurableConfiguration() {
        IntentActionRule disabled = rule("parking", false, "sh.car.parkovka",
                ActionBinding.OPERATION_TOGGLE, "{}");
        assertFalse(disabled.enabled);
        assertTrue(disabled.command.isBound());
    }

    static IntentActionRule rule(String id, boolean enabled, String action, String operation,
                                 String payload) {
        return new IntentActionRule(id, enabled, secureAction(action), TRIGGER_TOKEN,
                new ActionBinding(ConnectorType.SPRUTHUB, "default", "13/15/16",
                        operation, payload),
                "Ворота", "Управление воротами", "Целевое состояние");
    }

    static String secureAction(String prefix) {
        return IntentActionRule.secureIntentAction(prefix, ACTION_TOKEN);
    }
}
