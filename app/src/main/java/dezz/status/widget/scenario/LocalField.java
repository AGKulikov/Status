/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Locale;

/** Whitelisted local presentation fields; actions cannot invoke connector services. */
public enum LocalField {
    VISIBLE,
    TEXT,
    TEXT_COLOR,
    ICON,
    BACKGROUND_COLOR,
    ACTION_ENABLED,
    BORDER_COLOR,
    BORDER_WIDTH,
    ICON_TINT,
    ICON_BACKGROUND_COLOR,
    ICON_OUTLINE_COLOR,
    ICON_OUTLINE_WIDTH;

    public String jsonName() {
        return name();
    }

    public static LocalField fromJsonName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing scenario local field");
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown scenario local field: " + raw, error);
        }
    }
}
