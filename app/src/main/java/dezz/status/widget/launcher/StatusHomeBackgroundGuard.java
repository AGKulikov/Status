/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import dezz.status.widget.LauncherActivity;

/** Reasserts Status Widget HOME before an ECARX freeform navigation task is created. */
public final class StatusHomeBackgroundGuard {
    public static final String EXTRA_BACKGROUND_GUARD =
            "dezz.status.widget.extra.WINDOW_BACKGROUND_GUARD";
    public static final long SETTLE_MS = 96L;

    private StatusHomeBackgroundGuard() {}

    public static boolean raise(@NonNull Context context) {
        try {
            context.startActivity(new Intent(context, LauncherActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .putExtra(EXTRA_BACKGROUND_GUARD, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
