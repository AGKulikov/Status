/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Maps HWGPS 4.5.27's exported fix state to availability of its Find-me command. */
public final class HwgpsDrStatePolicy {
    public enum State { DR_ACTIVE, DR_INACTIVE, UNAVAILABLE }

    /**
     * Exact states accepted by HWGPS' own Find-me widget before it checks {@code dr_active}.
     * In the verified APK these states are produced only while its DR runtime is active.
     */
    private static final Set<String> DR_ACTIVE_FIXES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "fix_dr", "fix_sw_dr", "fix_sw_dr_mm", "fix_sw_yl_safe")));

    /** Known 4.5.27 states in which the same widget does not offer Find-me. */
    private static final Set<String> DR_INACTIVE_FIXES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "notfixed", "fix_ok", "filtered", "spoofing")));

    private HwgpsDrStatePolicy() {}

    @NonNull
    public static State classify(@Nullable String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (DR_ACTIVE_FIXES.contains(value)) return State.DR_ACTIVE;
        if (DR_INACTIVE_FIXES.contains(value)) return State.DR_INACTIVE;
        return State.UNAVAILABLE;
    }

    public static boolean findMeAvailable(@Nullable String raw) {
        return classify(raw) == State.DR_ACTIVE;
    }
}
