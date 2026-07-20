/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Locale;

/** Local UI surface affected by a scenario action. */
public enum TargetScope {
    MAIN,
    POPUP,
    BUILTIN,
    OVERLAY;

    public String jsonName() {
        return name();
    }

    public static TargetScope fromJsonName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Missing scenario target scope");
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Unknown scenario target scope: " + raw, error);
        }
    }
}
