/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

/** Pure guard rails for the last-resort Android 9 Bluetooth-controller recovery. */
public final class AncsAdapterRecoveryPolicy {
    /** Let the retained autoConnect owner and its serialized scan fallback finish first. */
    public static final long ESCALATION_DELAY_MS = 150_000L;
    public static final long RESET_COOLDOWN_MS = 5L * 60L * 1_000L;

    private AncsAdapterRecoveryPolicy() {
    }

    public static boolean mayReset(boolean ancsWasReadyBefore,
                                   boolean ancsReadyNow,
                                   boolean resetAlreadyRunning,
                                   long nowElapsedMs,
                                   long lastResetElapsedMs) {
        if (!ancsWasReadyBefore || ancsReadyNow || resetAlreadyRunning) return false;
        return lastResetElapsedMs <= 0L
                || nowElapsedMs - lastResetElapsedMs >= RESET_COOLDOWN_MS;
    }
}
