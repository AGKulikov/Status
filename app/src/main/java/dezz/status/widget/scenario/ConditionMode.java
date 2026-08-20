/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Locale;

/** How an ordered list of scenario conditions is combined. */
public enum ConditionMode {
    ALL,
    ANY;

    public String jsonName() {
        return name();
    }

    public static ConditionMode fromJsonName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return ALL;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown scenario condition mode: " + raw, error);
        }
    }
}
