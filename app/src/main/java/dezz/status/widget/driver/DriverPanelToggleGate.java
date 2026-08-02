/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;

/** Filters the duplicate callback pair produced by one ECARX rail press. */
final class DriverPanelToggleGate {
    static final long DUPLICATE_WINDOW_MS = 450L;

    private String lastTarget = "";
    private long lastAcceptedAt = Long.MIN_VALUE;
    private long lastPressToken;

    boolean accept(@NonNull String target, long pressToken, long now) {
        if (target.equals(lastTarget)) {
            // A real second rail press has a new MotionEvent downTime and must close immediately,
            // even if the driver taps faster than the fallback debounce interval.
            if (pressToken != 0L && lastPressToken != 0L
                    && pressToken != lastPressToken) {
                return remember(target, pressToken, now);
            }
            if (now >= lastAcceptedAt && now - lastAcceptedAt < DUPLICATE_WINDOW_MS) {
                return false;
            }
        }
        return remember(target, pressToken, now);
    }

    private boolean remember(@NonNull String target, long pressToken, long now) {
        lastTarget = target;
        lastPressToken = pressToken;
        lastAcceptedAt = now;
        return true;
    }
}
