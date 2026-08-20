/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Reasserts floating call UI when the stock BTPhone process publishes its state event. */
public final class EcarxBtPhoneEventReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent != null
                && EcarxBtPhoneBridge.ACTION_PHONE_UI_EVENT.equals(intent.getAction())) {
            EcarxBtPhoneBridge.onPhoneUiEvent(context);
        }
    }
}
