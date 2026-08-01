/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import dezz.status.widget.integration.ActionBinding;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.scenario.IntentActionRule;

/** Semantic icon refresh for launcher RULE shortcuts, with a hard user-customization barrier. */
public final class LauncherRuleIconPolicy {
    private LauncherRuleIconPolicy() {}

    @NonNull
    public static String suggest(@NonNull IntentActionRule rule) {
        ActionBinding command = rule.command;
        String domain = "";
        if (command.connectorType == ConnectorType.HOME_ASSISTANT) {
            int separator = command.resourceId.indexOf('.');
            if (separator > 0) domain = command.resourceId.substring(0, separator);
        }
        String type = command.connectorType.jsonName() + " " + command.resourceId + " "
                + command.operation;
        String name = rule.accessoryLabel + " " + rule.serviceLabel + " "
                + rule.characteristicLabel;
        return SmartHomeIconResolver.suggest(domain, "", type, name);
    }

    /**
     * Applies a suggestion only to the matching non-customized RULE.
     *
     * @return true when the stored icon actually changed.
     */
    public static boolean refresh(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                  @NonNull IntentActionRule rule) {
        if (shortcut.kind != LauncherShortcutStore.Kind.RULE
                || shortcut.iconCustomized || !shortcut.target.equals(rule.id)) return false;
        String suggested = suggest(rule);
        if (suggested.equals(shortcut.icon)) return false;
        shortcut.icon = suggested;
        return true;
    }
}
