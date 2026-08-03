/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * ECARX callback matching the stock panel contract.
 * {@code NAVIGATION_BAR_STATUS=true} means the navigation bar is currently hidden.
 */
public final class NavigationBarStatusService extends Service {
    public static final String ACTION =
            "ecarx.intent.action.NAVIGATION_BAR_STATUS";
    public static final String EXTRA_HIDDEN = "NAVIGATION_BAR_STATUS";

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_HIDDEN)) {
            DriverPanelService.onNavigationBarStatus(this,
                    intent.getBooleanExtra(EXTRA_HIDDEN, false));
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
