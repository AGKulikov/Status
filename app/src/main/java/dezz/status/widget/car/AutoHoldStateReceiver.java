/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Cold-start endpoint for the {@code plus.monjaro.AUTOHOLD} compatibility contract. */
public final class AutoHoldStateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(@NonNull Context context, @Nullable Intent intent) {
        if (intent == null || !AutoHoldStateRepository.ACTION_EXTERNAL.equals(intent.getAction())) {
            return;
        }
        try {
            Bundle extras = intent.getExtras();
            Object state = extras != null
                    && extras.containsKey(AutoHoldStateRepository.EXTRA_STATE)
                    ? extras.get(AutoHoldStateRepository.EXTRA_STATE) : null;
            AutoHoldStateRepository.accept(context, state);
        } catch (RuntimeException ignored) {
            // This receiver is intentionally exported for mHUD/MConfig compatibility. Ignore
            // malformed or unparcelable extras instead of letting another app crash HOME.
        }
    }
}
