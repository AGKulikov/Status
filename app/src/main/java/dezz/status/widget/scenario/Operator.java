/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Locale;

/** Fixed, non-executable operators supported by local display scenarios. */
public enum Operator {
    ALWAYS,
    EQUALS,
    NOT_EQUALS,
    GREATER,
    GREATER_OR_EQUAL,
    LESS,
    LESS_OR_EQUAL,
    BETWEEN,
    CONTAINS,
    EMPTY,
    NOT_EMPTY,
    TRUE,
    FALSE,
    FRESH,
    STALE,
    AVAILABLE,
    UNAVAILABLE;

    public static Operator fromJsonName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return ALWAYS;
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown scenario operator: " + raw, error);
        }
    }

    public String jsonName() {
        return name();
    }
}
