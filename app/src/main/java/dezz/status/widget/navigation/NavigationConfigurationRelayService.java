/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

/**
 * Non-exported relay for settings written by Natro itself.
 *
 * <p>The public Navigator endpoint must remain exported for its explicit Binder connection, so it
 * never accepts startService payloads. This separate component runs in the same Natro process but
 * is visible only to Natro's UID, preventing another app from injecting a map profile.</p>
 */
public final class NavigationConfigurationRelayService extends Service {
    static final String ACTION_REFRESH_CONFIGURATION =
            "ru.natro.statuswidget.internal.REFRESH_NAVIGATION_CONFIGURATION";
    static final String EXTRA_CONFIGURATION_JSON =
            "ru.natro.statuswidget.internal.NAVIGATION_CONFIGURATION_JSON";

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_REFRESH_CONFIGURATION.equals(intent.getAction())) {
            String raw;
            try {
                raw = intent.getStringExtra(EXTRA_CONFIGURATION_JSON);
            } catch (RuntimeException invalidExtra) {
                raw = null;
            }
            NavigationHudEndpointService.acceptRelayedConfiguration(raw);
        }
        stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }
}

