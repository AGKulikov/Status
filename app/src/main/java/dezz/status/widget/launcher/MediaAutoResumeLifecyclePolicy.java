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
        // Device-protected history is captured at LOCKED_BOOT, but third-party players are not
        // reliably runnable yet.  Start the user's delay only after Android's normal boot gate,
        // matching the proven mSaver behaviour and avoiding an ignored pre-unlock PLAY.
        return ACTION_BOOT_COMPLETED.equals(action)
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

    /** Move a frozen direct-boot snapshot to the first player-usable boot boundary exactly once. */
    static boolean shouldMovePlanAnchor(String previousAction, String currentAction) {
        return ACTION_LOCKED_BOOT_COMPLETED.equals(previousAction)
                && isUsableBoundary(currentAction);
    }

    private static boolean isStandardBootAction(String action) {
        return ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_COMPLETED.equals(action);
    }
}
