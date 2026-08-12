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

    /**
     * Cold HOME gets a bounded quiet period measured from kernel start, not from whichever boot
     * broadcast happens to arrive last. This keeps the first useful surface out of SystemServer's
     * initial burst without adding another fixed ten seconds on a slow ECARX boot.
     */
    static final long COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS = 4_500L;
    /** Coalesces a late BOOT_COMPLETED edge without penalising an already-settled system. */
    static final long BOOT_EVENT_SETTLE_MS = 1_000L;
    /** QuickBoot keeps kernel uptime, so it needs its own short relative settle window. */
    static final long QUICK_BOOT_QUIET_MS = 1_500L;
    /** Normal process recovery should be perceptible as a restart, not as a blank two-second UI. */
    static final long MAIN_PROCESS_SETTLE_MS = 400L;
    static final long PROCESS_SETTLE_RUNTIME_AFTER_MS = 750L;
    static final long USER_UNLOCKED_QUIET_MS = 750L;
    static final long PACKAGE_REPLACED_QUIET_MS = 750L;
    /** Host stages finish first; Driver/HUD, Climate, media and fallback occupy separate lanes. */
    static final long LAUNCHER_RUNTIME_AFTER_HOST_MS = 2_500L;
    static final long CLIMATE_AFTER_HOST_MS = 7_500L;
    static final long MEDIA_AUTO_RESUME_MIN_MS = 26_000L;
    static final long HUD_FALLBACK_DELAY_MS = 32_000L;
    static final long MAX_VALID_QUIET_MS = 45_000L;
    static final long MAX_VALID_STARTUP_LANE_MS = 120_000L;
    static final long HOST_HANDOFF_GRACE_MS = 5_000L;

    private StartupLoadPolicy() {}

    static long quietWindowMillis(Trigger trigger, long elapsedRealtimeMillis) {
        switch (trigger) {
            case LOCKED_BOOT:
            case BOOT_COMPLETED:
                return Math.max(BOOT_EVENT_SETTLE_MS,
                        earlyBootQuietMillis(elapsedRealtimeMillis));
            case QUICK_BOOT:
                return QUICK_BOOT_QUIET_MS;
            case USER_UNLOCKED:
                return USER_UNLOCKED_QUIET_MS;
            case PACKAGE_REPLACED:
                return PACKAGE_REPLACED_QUIET_MS;
            default:
                return 0L;
        }
    }

    /** Convenience overload retained for pure policy callers that model a kernel-start event. */
    static long quietWindowMillis(Trigger trigger) {
        return quietWindowMillis(trigger, 0L);
    }

    static boolean schedulesIntegrationHost(Trigger trigger) {
        return trigger == Trigger.BOOT_COMPLETED
                || trigger == Trigger.QUICK_BOOT
                || trigger == Trigger.USER_UNLOCKED
                || trigger == Trigger.PACKAGE_REPLACED;
    }

    static boolean isBootLifecycle(Trigger trigger) {
        return trigger == Trigger.LOCKED_BOOT
                || trigger == Trigger.BOOT_COMPLETED
                || trigger == Trigger.QUICK_BOOT;
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
                || elapsedRealtimeMillis >= COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS) {
            return 0L;
        }
        return COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS - elapsedRealtimeMillis;
    }
}
