/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

/**
 * Adaptive recovery cadence for launcher's same-process WidgetService bindings.
 *
 * <p>The first lookup is always submitted immediately by the activity. Delays here apply only
 * after a lookup found that an expected runtime has not appeared yet. Once callbacks are bound,
 * a very infrequent watchdog detects process-local service replacement without returning to the
 * former permanent 250/500-ms polling loops.</p>
 */
public final class LauncherRuntimeProbePolicy {
    private static final long[] RECOVERY_DELAYS_MS = {
            100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 15_000L
    };
    public static final long STEADY_WATCHDOG_MS = 30_000L;

    private LauncherRuntimeProbePolicy() {
    }

    public static long nextDelayMillis(boolean runtimeReady, int failedAttempts) {
        if (runtimeReady) return STEADY_WATCHDOG_MS;
        int index = Math.max(0, Math.min(RECOVERY_DELAYS_MS.length - 1, failedAttempts));
        return RECOVERY_DELAYS_MS[index];
    }
}
