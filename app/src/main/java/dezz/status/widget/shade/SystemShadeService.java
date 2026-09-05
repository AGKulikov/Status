/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;

/** Persistent owner for the replacement shade. */
public final class SystemShadeService extends Service {
    public static final String ACTION_RECONCILE = "dezz.status.widget.shade.RECONCILE";
    public static final String ACTION_STOP = "dezz.status.widget.shade.STOP";
    @Nullable private static volatile SystemShadeService instance;
    private static volatile boolean vehicleOverlayActive;
    @Nullable private SystemShadeOverlayController controller;

    public static void reconcile(@NonNull Context context, boolean automatic) {
        Preferences preferences = new Preferences(context);
        boolean enabled = preferences.systemShadeEnabled.get()
                && (!automatic || preferences.systemShadeAutostart.get());
        Intent intent = new Intent(context, SystemShadeService.class)
                .setAction(enabled ? ACTION_RECONCILE : ACTION_STOP);
        try { context.startService(intent); }
        catch (RuntimeException ignored) { }
    }

    public static void setVehicleOverlayActive(boolean active) {
        vehicleOverlayActive = active;
        SystemShadeService current = instance;
        if (current != null && current.controller != null) {
            current.controller.setVehicleOverlayActive(active);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        controller = new SystemShadeOverlayController(this, new Preferences(this));
        controller.setVehicleOverlayActive(vehicleOverlayActive);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_RECONCILE : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        SystemShadeOverlayController current = controller;
        if (current != null) {
            current.start();
            current.reload();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        SystemShadeOverlayController current = controller;
        controller = null;
        if (current != null) current.stop();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
