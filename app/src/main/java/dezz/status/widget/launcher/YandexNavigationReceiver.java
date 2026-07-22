/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Compatibility endpoint for navigation data broadcasts used by Yandex/MConfig builds. */
public final class YandexNavigationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        NavigationDataRepository.updateFromYandexBroadcast(context, intent);
    }
}
