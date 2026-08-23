/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

/** Pure timing policy for the LOCKED_BOOT/BOOT/ECARX QuickBoot broadcast burst. */
final class MediaAutoResumeLifecyclePolicy {
    static final String ACTION_LOCKED_BOOT_COMPLETED =
            "android.intent.action.LOCKED_BOOT_COMPLETED";
    static final String ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    static final String ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON";
    static final long BURST_COALESCE_MS = 120_000L;

    private MediaAutoResumeLifecyclePolicy() {}

    static boolean isLifecycleAction(String action) {
        return ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action);
    }

    static boolean isUsableBoundary(String action) {
        return ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action);
    }

    /**
     * ECARX can publish BOOT and QUICKBOOT repeatedly during one startup. Treat a continuous burst
     * as one lifecycle even after the media plan has already consumed its token; otherwise every
     * repeated QUICKBOOT cancels the timer and starts the user delay again.
     */
    static boolean shouldCoalesce(String previousAction,
                                  String currentAction,
                                  boolean differentKnownBootCount,
                                  long elapsedSincePreviousBoundaryMs) {
        if (differentKnownBootCount) return false;
        boolean standardPair = isStandardBootAction(previousAction)
                && isStandardBootAction(currentAction);
        boolean sameLifecycleBurst = elapsedSincePreviousBoundaryMs >= 0L
                && elapsedSincePreviousBoundaryMs <= BURST_COALESCE_MS
                && isLifecycleAction(previousAction)
                && isLifecycleAction(currentAction);
        return standardPair || sameLifecycleBurst;
    }

    /** The user delay starts at the earliest direct-boot boundary and is never moved later. */
    static boolean shouldMovePlanAnchor(String previousAction, String currentAction) {
        return false;
    }

    private static boolean isStandardBootAction(String action) {
        return ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_COMPLETED.equals(action);
    }
}
