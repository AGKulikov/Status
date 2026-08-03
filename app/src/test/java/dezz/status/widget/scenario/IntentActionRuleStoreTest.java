/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dezz.status.widget.integration.ActionBinding;

public final class IntentActionRuleStoreTest {
    @Test public void deterministicRoundTripPreservesDeclarationOrder() {
        List<IntentActionRule> original = Arrays.asList(
                IntentActionRuleTest.rule("parking", true, "sh.car.parkovka",
                        ActionBinding.OPERATION_TOGGLE, "{}"),
                IntentActionRuleTest.rule("door", false, "sh.car.door",
                        ActionBinding.OPERATION_SET, "false"));

        String encoded = IntentActionRuleStore.encode(original);
        List<IntentActionRule> decoded = IntentActionRuleStore.decode(encoded);

        assertEquals(original, decoded);
        assertEquals(encoded, IntentActionRuleStore.encode(decoded));
        assertEquals("parking", decoded.get(0).id);
        assertEquals("door", decoded.get(1).id);
    }

    @Test public void blankConfigurationIsEmpty() {
        assertTrue(IntentActionRuleStore.decode("  ").isEmpty());
    }

    @Test public void enabledLookupIgnoresDisabledRuleAndIsCaseSensitive() {
        IntentActionRule upper = IntentActionRuleTest.rule("upper", true, "sh.car.Parking",
                ActionBinding.OPERATION_TOGGLE, "{}");
        IntentActionRule lower = IntentActionRuleTest.rule("lower", true, "sh.car.parking",
                ActionBinding.OPERATION_SET, "true");
        IntentActionRule disabled = IntentActionRuleTest.rule("disabled", false, "sh.car.door",
                ActionBinding.OPERATION_SET, "false");
        List<IntentActionRule> rules = Arrays.asList(upper, lower, disabled);

        Map<String, IntentActionRule> byAction =
                IntentActionRuleStore.enabledByAction(rules);

        assertEquals(2, byAction.size());
        assertEquals("upper", byAction.get(upper.intentAction).id);
        assertEquals("lower", byAction.get(lower.intentAction).id);
        assertFalse(byAction.containsKey(disabled.intentAction));
    }

    @Test public void disabledRulesMayShareActionForAlternativeConfigurations() {
        List<IntentActionRule> rules = Arrays.asList(
                IntentActionRuleTest.rule("first", false, "sh.car.parking",
                        ActionBinding.OPERATION_SET, "true"),
                IntentActionRuleTest.rule("second", false, "sh.car.parking",
                        ActionBinding.OPERATION_SET, "false"));
        assertEquals(2, IntentActionRuleStore.decode(
                IntentActionRuleStore.encode(rules)).size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateEnabledActionIsRejected() {
        IntentActionRuleStore.encode(Arrays.asList(
                IntentActionRuleTest.rule("first", true, "sh.car.parking",
                        ActionBinding.OPERATION_SET, "true"),
                IntentActionRuleTest.rule("second", true, "sh.car.parking",
                        ActionBinding.OPERATION_SET, "false")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateIdIsRejectedEvenWhenDisabled() {
        IntentActionRuleStore.encode(Arrays.asList(
                IntentActionRuleTest.rule("same", false, "sh.car.first",
                        ActionBinding.OPERATION_SET, "true"),
                IntentActionRuleTest.rule("same", false, "sh.car.second",
                        ActionBinding.OPERATION_SET, "false")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void moreThanMaximumRulesIsRejected() {
        List<IntentActionRule> rules = new ArrayList<>();
        for (int index = 0; index <= IntentActionRuleStore.MAX_RULES; index++) {
            rules.add(IntentActionRuleTest.rule("rule_" + index, false,
                    "sh.car.action_" + index, ActionBinding.OPERATION_SET, "true"));
        }
        IntentActionRuleStore.encode(rules);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonObjectArrayEntryIsRejected() {
        IntentActionRuleStore.decode("[1]");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void decodedListIsImmutable() {
        IntentActionRuleStore.decode("[]").add(
                IntentActionRuleTest.rule("x", true, "sh.car.x",
                        ActionBinding.OPERATION_TOGGLE, "{}"));
    }
}
