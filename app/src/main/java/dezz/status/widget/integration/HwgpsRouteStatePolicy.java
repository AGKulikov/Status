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

    /**
     * Evidence carried by HWGPS' exported {@code hwgps.fix.state} broadcast.
     *
     * <p>{@link #AUTO_SHOW} is stronger than {@link #NO_FIX}: HWGPS' auto-manifest branch
     * displays filtered/spoofed fixes immediately, while a plain no-fix value still passes
     * through its internal route-loss hysteresis.</p>
     */
    enum Signal { NO_FIX, AUTO_SHOW, ROUTE_AVAILABLE, UNKNOWN }

    /** HWGPS' initial/no-fix value, which renders {@code widget_fix_no}. */
    public static final String NOT_FIXED = "notFixed";

    /** Broadcast values which are not themselves explicit auto-show evidence. */
    private static final Set<String> AVAILABLE = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("fix_ok", "fix_dr", "fix_sw_dr", "fix_sw_dr_mm",
                    "fix_sw_yl_safe")));

    /** Explicit HWGPS 4.5.27 auto-show branches in {@code J2.k#b}. */
    private static final Set<String> AUTO_SHOW = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList("filtered", "spoofing")));

    private HwgpsRouteStatePolicy() {}

    @NonNull
    public static State classify(@Nullable String raw) {
        switch (signal(raw)) {
            case NO_FIX:
            case AUTO_SHOW: return State.ROUTE_LOST;
            case ROUTE_AVAILABLE: return State.ROUTE_AVAILABLE;
            case UNKNOWN:
            default: return State.UNAVAILABLE;
        }
    }

    @NonNull
    static Signal signal(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return Signal.UNKNOWN;
        if (NOT_FIXED.toLowerCase(Locale.ROOT).equals(value)) return Signal.NO_FIX;
        if (AUTO_SHOW.contains(value)) return Signal.AUTO_SHOW;
        return AVAILABLE.contains(value) ? Signal.ROUTE_AVAILABLE : Signal.UNKNOWN;
    }
}
