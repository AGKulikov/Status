/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.information;

import androidx.annotation.Nullable;

import java.util.Locale;

/** Pure freshness, visibility and activity rules shared by HOME and unit tests. */
public final class InformationValuePolicy {
    private InformationValuePolicy() {}

    public static boolean isConnectorKnown(boolean fresh, boolean available, boolean readable,
                                           @Nullable Object value) {
        return fresh && available && readable && value != null;
    }

    public static boolean isVisible(InformationPanelConfig.Visibility visibility,
                                    boolean known, boolean active) {
        if (visibility == null || visibility == InformationPanelConfig.Visibility.ALWAYS) {
            return true;
        }
        if (visibility == InformationPanelConfig.Visibility.WHEN_KNOWN) return known;
        if (visibility == InformationPanelConfig.Visibility.WHEN_ACTIVE) {
            return known && active;
        }
        return known && !active;
    }

    public static boolean isActive(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            return Double.isFinite(number) && Math.abs(number) > 0.000001d;
        }
        String normalized = value == null ? "" : String.valueOf(value).trim()
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        switch (normalized) {
            case "0":
            case "false":
            case "off":
            case "closed":
            case "locked":
            case "idle":
            case "standby":
            case "unavailable":
            case "unknown":
            case "нет":
            case "выкл":
            case "закрыто":
                return false;
            default:
                return true;
        }
    }
}
