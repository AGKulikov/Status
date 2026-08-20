/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;

/** Pure policy for holding iPhone notifications behind a foreground full-screen application. */
public final class PhoneNotificationDeferralPolicy {
    public static final int DEFAULT_MAX_WAIT_SECONDS = 30;
    public static final int MIN_MAX_WAIT_SECONDS = 1;
    public static final int MAX_MAX_WAIT_SECONDS = 600;

    private PhoneNotificationDeferralPolicy() {
    }

    public static int boundedMaxWaitSeconds(int seconds) {
        return Math.max(MIN_MAX_WAIT_SECONDS, Math.min(MAX_MAX_WAIT_SECONDS, seconds));
    }

    /** Package matching is deliberately exact; prefix matching could block an unrelated app. */
    public static boolean isBlocking(boolean enabled,
                                     @NonNull Set<String> selectedPackages,
                                     @Nullable String foregroundPackage) {
        if (!enabled || foregroundPackage == null) return false;
        String current = foregroundPackage.trim();
        return !current.isEmpty() && selectedPackages.contains(current);
    }

    /** Saturating monotonic deadline used by the single nearest-deadline callback. */
    public static long deadline(long enqueuedAtElapsed, int maxWaitSeconds) {
        long delay = boundedMaxWaitSeconds(maxWaitSeconds) * 1_000L;
        if (enqueuedAtElapsed > Long.MAX_VALUE - delay) return Long.MAX_VALUE;
        return enqueuedAtElapsed + delay;
    }
}
