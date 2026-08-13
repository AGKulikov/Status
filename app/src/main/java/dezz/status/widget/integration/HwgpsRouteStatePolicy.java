/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Pure mapping of the state strings rendered by the HWGPS 4.5.27 Fix widget. */
public final class HwgpsRouteStatePolicy {
    public enum State { ROUTE_LOST, ROUTE_AVAILABLE, UNAVAILABLE }

    /** HWGPS' initial/no-fix value, which renders {@code widget_fix_no}. */
    public static final String NOT_FIXED = "notFixed";

    /** Every evidenced non-default icon branch in HWGPS' {@code J2.l#a}. */
    private static final Set<String> AVAILABLE = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("fix_ok", "fix_dr", "fix_sw_dr", "fix_sw_dr_mm",
                    "fix_sw_yl_safe", "filtered", "spoofing")));

    private HwgpsRouteStatePolicy() {}

    @NonNull
    public static State classify(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return State.UNAVAILABLE;
        if (NOT_FIXED.equalsIgnoreCase(value)) return State.ROUTE_LOST;
        return AVAILABLE.contains(value.toLowerCase(Locale.ROOT))
                ? State.ROUTE_AVAILABLE : State.UNAVAILABLE;
    }
}
