/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;

import java.util.Locale;

import dezz.status.widget.launcher.SmartHomeIconResolver;

/** Semantic icon selection that stays useful before the first live value arrives. */
public final class InformationIconPolicy {
    private InformationIconPolicy() {}

    @NonNull
    public static String resolve(@NonNull InformationPanelConfig.Item item) {
        if (!"auto".equalsIgnoreCase(item.iconKey)) return item.iconKey;
        if (item.sourceKind == InformationPanelConfig.SourceKind.CONNECTOR) {
            String domain = "";
            if (item.binding != null) {
                int dot = item.binding.resourceId.indexOf('.');
                if (dot > 0) domain = item.binding.resourceId.substring(0, dot);
            }
            return SmartHomeIconResolver.suggest(domain, item.sourceTypeHint,
                    item.sourceUnit, item.sourceLabel + " " + item.sourceTypeHint);
        }
        String value = (item.sourceId + " " + item.sourceLabel + " "
                + item.sourceTypeHint).toLowerCase(Locale.ROOT);
        if (value.contains("battery") || value.contains("батар")) return "battery";
        if (value.contains("fuel") || value.contains("топлив")) return "fuel_save";
        if (value.contains("temperature") || value.contains("temp")
                || value.contains("температур")) return "temperature";
        if (value.contains("humidity") || value.contains("влажност")) return "humidity";
        if (value.contains("location") || value.contains("местополож")) return "location";
        if (value.contains("music") || value.contains("media")) return "music";
        if (value.contains("network") || value.contains("wifi")
                || value.contains("сеть")) return "devices";
        if (item.sourceKind == InformationPanelConfig.SourceKind.VEHICLE) return "car";
        return "notification";
    }
}
