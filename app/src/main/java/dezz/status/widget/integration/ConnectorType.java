/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;

import java.util.Locale;

/** Connector family used by a per-brick source or action binding. */
public enum ConnectorType {
    HOME_ASSISTANT,
    MQTT,
    SPRUTHUB,
    PHONE;

    /** Parses the stable JSON name. Blank values use the supplied migration fallback. */
    @NonNull
    public static ConnectorType fromJsonName(String value, @NonNull ConnectorType fallback) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) return fallback;
        try {
            return valueOf(normalized.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown connector type: " + value, error);
        }
    }

    /** Stable, locale-independent representation written to configuration JSON. */
    @NonNull
    public String jsonName() {
        return name();
    }
}
