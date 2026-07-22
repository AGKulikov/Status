/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import dezz.status.widget.ClimatePanelSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;

/** Long-lived owner of the compact/reserved climate panel independent from the main widget. */
public final class ClimatePanelService extends Service {
    public static final String ACTION_APPLY =
            "ru.natro.statuswidget.action.CLIMATE_PANEL_APPLY";
    public static final String ACTION_RESTORE =
            "ru.natro.statuswidget.action.CLIMATE_PANEL_RESTORE";

    private static final String CHANNEL_ID = "ClimatePanelChannel";
    private static final int NOTIFICATION_ID = 1006;

    private static volatile String runtimeStatus = "stopped";
    private static volatile String runtimeDetail = "";

    @Nullable private ClimatePanelOverlayController controller;
    @Nullable private Preferences preferences;
    private boolean explicitRestore;

    /** Start or keep the panel service alive using its persisted settings. */
    public static void start(@NonNull Context context) {
        startWithAction(context, ACTION_APPLY);
    }

    /** Re-read settings and immediately update the currently visible mode/geometry. */
    public static void apply(@NonNull Context context) {
        startWithAction(context, ACTION_APPLY);
    }

    /** Remove every climate window and safely restore the full application work area. */
    public static void stopAndRestore(@NonNull Context context) {
        startWithAction(context, ACTION_RESTORE);
    }

    /** Machine-stable state for settings/diagnostics: compact, reserved, fallback, etc. */
    @NonNull
    public static String getRuntimeStatus() {
        return runtimeStatus;
    }

    /** Last human-readable result from overlay/reservation reconciliation. */
    @NonNull
    public static String getRuntimeDetail() {
        return runtimeDetail;
    }

    private static void startWithAction(@NonNull Context context, @NonNull String action) {
        Intent intent = new Intent(context, ClimatePanelService.class).setAction(action);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        // Android allows only a few seconds after startForegroundService(). Do this before
        // constructing the ECARX integration or attempting privileged shell discovery.
        startForeground(NOTIFICATION_ID, createNotification());
        preferences = new Preferences(this);
        updateRuntimeStatus("starting", "Запуск климатической панели");
        controller = new ClimatePanelOverlayController(this, preferences,
                ClimatePanelService::updateRuntimeStatus);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_APPLY : intent.getAction();
        if (ACTION_RESTORE.equals(action)
                || preferences == null
                || !preferences.climatePanelEnabled.get()) {
            explicitRestore = true;
            ClimatePanelOverlayController current = controller;
            if (current == null) {
                finishStop();
            } else {
                current.stopAndRestore(this::finishStop);
            }
            return START_NOT_STICKY;
        }

        explicitRestore = false;
        if (controller != null) controller.applyPreferences();
        // A null-intent process restart re-applies the latest settings and vehicle subscriptions.
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controller != null && preferences != null
                && preferences.climatePanelEnabled.get()) {
            controller.applyPreferences();
        }
    }

    private void finishStop() {
        updateRuntimeStatus("stopped", "Панель выключена, область экрана восстановлена");
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        ClimatePanelOverlayController current = controller;
        controller = null;
        if (current != null) {
            // A deliberate stop has already queued restore; on an unexpected service teardown
            // request it here as a fail-safe so no blank reserved strip survives without UI.
            current.destroy(!explicitRestore);
        }
        super.onDestroy();
    }

    private static void updateRuntimeStatus(@NonNull String status, @NonNull String detail) {
        runtimeStatus = status;
        runtimeDetail = detail;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Панель климата", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Постоянная панель управления климатом автомобиля");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification createNotification() {
        Intent settings = new Intent(this, ClimatePanelSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1006, settings,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_car_climate)
                .setContentTitle("Панель климата")
                .setContentText("Управление климатом доступно поверх приложений")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
