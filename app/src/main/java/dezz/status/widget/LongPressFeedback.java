/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.view.SoundEffectConstants;
import android.view.View;

import androidx.annotation.Nullable;

/** Plays Android's ordinary click sound exactly once when a long press is recognized. */
public final class LongPressFeedback {
    private LongPressFeedback() {
    }

    /** The platform still owns the user's global touch-sound setting and final volume policy. */
    public static void play(@Nullable View view) {
        if (view == null) return;
        view.setSoundEffectsEnabled(true);
        try {
            view.playSoundEffect(SoundEffectConstants.CLICK);
        } catch (RuntimeException ignored) {
            // A successful action may synchronously detach its source view. Never undo the action
            // merely because WindowManager retired that exact surface before feedback completed.
        }
    }
}
