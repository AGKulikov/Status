/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.scenario.IntentActionRule;

public final class LauncherRuleIconPolicyTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test public void oldGenericRuleReceivesSemanticIconOnMigration() {
        IntentActionRule rule = rule("lights",
                new ActionBinding(ConnectorType.HOME_ASSISTANT, "default",
                        "light.kitchen", ActionBinding.OPERATION_TOGGLE, "{}"),
                "Кухня", "Свет", "");
        LauncherShortcutStore.Shortcut shortcut = shortcut("lights", "devices", false);

        assertTrue(LauncherRuleIconPolicy.refresh(shortcut, rule));
        assertEquals("light", shortcut.icon);
        assertFalse(shortcut.iconCustomized);
    }

    @Test public void explicitUserIconIsNeverOverwritten() {
        IntentActionRule rule = rule("leak",
                ActionBinding.legacy("water_alarm", "TOGGLE"),
                "Датчик протечки", "", "");
        LauncherShortcutStore.Shortcut shortcut = shortcut("leak", "car", true);

        assertFalse(LauncherRuleIconPolicy.refresh(shortcut, rule));
        assertEquals("car", shortcut.icon);
        assertTrue(shortcut.iconCustomized);
    }

    private static LauncherShortcutStore.Shortcut shortcut(
            String target, String icon, boolean customized) {
        LauncherShortcutStore.Shortcut value = new LauncherShortcutStore.Shortcut();
        value.kind = LauncherShortcutStore.Kind.RULE;
        value.target = target;
        value.icon = icon;
        value.iconCustomized = customized;
        return value;
    }

    private static IntentActionRule rule(String id, ActionBinding binding,
                                         String accessory, String service,
                                         String characteristic) {
        return new IntentActionRule(id, true,
                IntentActionRule.secureIntentAction("test.launcher." + id, TOKEN), TOKEN,
                binding, accessory, service, characteristic);
    }
}
