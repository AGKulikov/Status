/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.scenario.IntentActionRule;

public final class ScenarioTriggerReceiverTest {
    @Test public void fixedEndpointSelectsOnlyEnabledSavedRuleId() {
        IntentActionRule enabled = rule("gate", true, "sh.car.gate");
        IntentActionRule disabled = rule("light", false, "sh.car.light");

        assertEquals(enabled, ScenarioTriggerReceiver.enabledByIdAndToken(
                Arrays.asList(enabled, disabled), " gate ", enabled.triggerToken));
        assertNull(ScenarioTriggerReceiver.enabledByIdAndToken(
                Arrays.asList(enabled, disabled), "light", disabled.triggerToken));
        assertNull(ScenarioTriggerReceiver.enabledByIdAndToken(
                Arrays.asList(enabled, disabled), "gate", null));
        assertNull(ScenarioTriggerReceiver.enabledByIdAndToken(
                Arrays.asList(enabled, disabled), "gate",
                "ffffffffffffffffffffffffffffffff"));
        assertNull(ScenarioTriggerReceiver.enabledByIdAndToken(
                Arrays.asList(enabled, disabled), enabled.intentAction, enabled.triggerToken));
    }

    private static IntentActionRule rule(String id, boolean enabled, String action) {
        return new IntentActionRule(id, enabled,
                IntentActionRule.secureIntentAction(action,
                        "fedcba9876543210fedcba9876543210"),
                "0123456789abcdef0123456789abcdef",
                new ActionBinding(ConnectorType.SPRUTHUB, "default", "1/2/3",
                        ActionBinding.OPERATION_SET, "true"), "", "", "");
    }
}
