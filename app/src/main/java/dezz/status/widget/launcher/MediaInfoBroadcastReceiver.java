/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Exported entry point that retains media updates even when the launcher is not visible. */
public final class MediaInfoBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        PendingResult result = goAsync();
        MediaBroadcastRepository.processAsync(context, intent, result::finish);
    }
}
