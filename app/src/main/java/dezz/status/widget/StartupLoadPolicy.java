/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

/** Pure timing and deduplication policy for the Android 9 head-unit startup lane. */
final class StartupLoadPolicy {
    enum Trigger {
        LOCKED_BOOT,
        BOOT_COMPLETED,
        QUICK_BOOT,
        USER_UNLOCKED,
        PACKAGE_REPLACED,
        OTHER
    }

    /** Covers HOME/Application starts which race ahead of BOOT_COMPLETED on Android 9. */
    static final long EARLY_BOOT_QUIET_UNTIL_ELAPSED_MS = 12_000L;
    /** Gives a high-uptime QuickBoot broadcast time to arrive before a cold main process works. */
    static final long MAIN_PROCESS_SETTLE_MS = 2_000L;
    static final long PROCESS_SETTLE_RUNTIME_AFTER_MS = 1_000L;
    static final long LOCKED_BOOT_QUIET_MS = 12_000L;
    static final long BOOT_COMPLETED_QUIET_MS = 10_000L;
    static final long USER_UNLOCKED_QUIET_MS = 3_000L;
    static final long PACKAGE_REPLACED_QUIET_MS = 2_000L;
    /** Host stages finish first; Driver/HUD, Climate, media and fallback occupy separate lanes. */
    static final long LAUNCHER_PANELS_AFTER_HOST_MS = 4_500L;
    static final long LAUNCHER_RUNTIME_AFTER_HOST_MS = 6_500L;
    static final long CLIMATE_AFTER_HOST_MS = 10_000L;
    static final long MEDIA_AUTO_RESUME_MIN_MS = 26_000L;
    static final long HUD_FALLBACK_DELAY_MS = 32_000L;
    static final long MAX_VALID_QUIET_MS = 45_000L;
    static final long MAX_VALID_STARTUP_LANE_MS = 120_000L;
    static final long HOST_HANDOFF_GRACE_MS = 5_000L;

    private StartupLoadPolicy() {}

    static long quietWindowMillis(Trigger trigger) {
        switch (trigger) {
            case LOCKED_BOOT:
                return LOCKED_BOOT_QUIET_MS;
            case BOOT_COMPLETED:
            case QUICK_BOOT:
                return BOOT_COMPLETED_QUIET_MS;
            case USER_UNLOCKED:
                return USER_UNLOCKED_QUIET_MS;
            case PACKAGE_REPLACED:
                return PACKAGE_REPLACED_QUIET_MS;
            default:
                return 0L;
        }
    }

    static boolean schedulesIntegrationHost(Trigger trigger) {
        return trigger == Trigger.BOOT_COMPLETED
                || trigger == Trigger.QUICK_BOOT
                || trigger == Trigger.USER_UNLOCKED
                || trigger == Trigger.PACKAGE_REPLACED;
    }

    static boolean schedulesClimate(Trigger trigger) {
        return trigger == Trigger.BOOT_COMPLETED
                || trigger == Trigger.QUICK_BOOT
                || trigger == Trigger.PACKAGE_REPLACED;
    }

    static boolean schedulesMediaPlan(Trigger trigger) {
        return trigger == Trigger.BOOT_COMPLETED || trigger == Trigger.QUICK_BOOT;
    }

    static boolean opensCredentialGate(Trigger trigger) {
        return trigger == Trigger.USER_UNLOCKED;
    }

    static long remainingQuietMillis(long nowElapsedMillis, long quietUntilElapsedMillis) {
        long remaining = quietUntilElapsedMillis - nowElapsedMillis;
        if (remaining <= 0L || remaining > MAX_VALID_QUIET_MS) return 0L;
        return remaining;
    }

    static long remainingStartupLaneMillis(long nowElapsedMillis,
                                           long notBeforeElapsedMillis) {
        long remaining = notBeforeElapsedMillis - nowElapsedMillis;
        if (remaining <= 0L || remaining > MAX_VALID_STARTUP_LANE_MS) return 0L;
        return remaining;
    }

    static boolean isNewBootGeneration(int currentBootCount, int recordedBootCount) {
        return currentBootCount >= 0 && currentBootCount != recordedBootCount;
    }

    static long earlyBootQuietMillis(long elapsedRealtimeMillis) {
        if (elapsedRealtimeMillis < 0L
                || elapsedRealtimeMillis >= EARLY_BOOT_QUIET_UNTIL_ELAPSED_MS) {
            return 0L;
        }
        return EARLY_BOOT_QUIET_UNTIL_ELAPSED_MS - elapsedRealtimeMillis;
    }
}
