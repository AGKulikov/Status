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
        dezz.status.widget.diagnostics.ActionRecorder.record(
                dezz.status.widget.diagnostics.ActionRecorder.SOURCE_SERVICE,
                "BROADCAST_RECEIVED",
                dezz.status.widget.diagnostics.ActionRecorder.object(
                        "receiver", getClass().getName(),
                        "action", intent == null ? null : intent.getAction()));
        if (intent == null
                || !MediaAutoResumeController.ACTION_RESUME.equals(intent.getAction())) return;
        long bootToken = intent.getLongExtra(
                MediaAutoResumeController.EXTRA_BOOT_TOKEN, Long.MIN_VALUE);
        int attempt = intent.getIntExtra(MediaAutoResumeController.EXTRA_ATTEMPT, 0);
        MediaAutoResumeController.recordAlarmDelivery(bootToken, attempt);
        MediaAutoResumeController.execute(context, bootToken, attempt);
    }
}
