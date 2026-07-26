/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Internal AlarmManager endpoint for the boot media-resume command. */
public final class MediaAutoResumeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !MediaAutoResumeController.ACTION_RESUME.equals(intent.getAction())) return;
        MediaAutoResumeController.execute(context,
                intent.getLongExtra(MediaAutoResumeController.EXTRA_BOOT_TOKEN, Long.MIN_VALUE),
                intent.getIntExtra(MediaAutoResumeController.EXTRA_ATTEMPT, 0));
    }
}
