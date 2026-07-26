/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

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

import dezz.status.widget.DriverPanelSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;

/** Foreground owner of the driver rail so it survives app switches and process pressure. */
public final class DriverPanelService extends Service {
    public static final String ACTION_APPLY =
            "ru.natro.statuswidget.action.DRIVER_PANEL_APPLY";
    public static final String ACTION_STOP =
            "ru.natro.statuswidget.action.DRIVER_PANEL_STOP";
    public static final String ACTION_RAISE =
            "ru.natro.statuswidget.action.DRIVER_PANEL_RAISE";
    public static final String ACTION_STOCK_CLIMATE =
            "ru.natro.statuswidget.action.DRIVER_PANEL_STOCK_CLIMATE";
    public static final String ACTION_FAVORITES =
            "ru.natro.statuswidget.action.DRIVER_PANEL_FAVORITES";
    public static final String EXTRA_FAVORITES_PANEL_ID =
            "ru.natro.statuswidget.extra.DRIVER_FAVORITES_PANEL_ID";

    private static final String CHANNEL_ID = "DriverPanelChannel";
    private static final int NOTIFICATION_ID = 1007;
    private static volatile String runtimeStatus = "stopped";
    private static volatile String runtimeDetail = "";
    private static volatile int runtimeWindowType =
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static volatile boolean navigationHidden;
    @Nullable private static volatile DriverPanelService instance;

    @Nullable private DriverPanelOverlayController controller;
    @Nullable private Preferences preferences;

    public static void apply(@NonNull Context context) {
        start(context, ACTION_APPLY);
    }

    public static void stop(@NonNull Context context) {
        start(context, ACTION_STOP);
    }

    public static void raise(@NonNull Context context) {
        start(context, ACTION_RAISE);
    }

    public static void triggerStockClimate(@NonNull Context context) {
        start(context, ACTION_STOCK_CLIMATE);
    }

    public static void showFavorites(@NonNull Context context) {
        showFavorites(context, DriverFavoritesPanelConfig.DEFAULT_ID);
    }

    public static void showFavorites(@NonNull Context context, @NonNull String panelId) {
        ContextCompat.startForegroundService(context,
                new Intent(context, DriverPanelService.class)
                        .setAction(ACTION_FAVORITES)
                        .putExtra(EXTRA_FAVORITES_PANEL_ID, panelId));
    }

    public static void onNavigationBarStatus(@NonNull Context context, boolean hidden) {
        navigationHidden = hidden;
        DriverPanelService current = instance;
        if (current != null && current.controller != null) {
            boolean refreshed = current.controller.setNavigationHidden(hidden);
            // ECARX emits this callback around application/freeform transitions. Treat every
            // notification as a z-order invalidation even when the boolean did not change;
            // fullscreen apps must not get a frame in which the covered OEM rail is exposed.
            if (!refreshed) current.controller.raise();
        } else if (new Preferences(context).driverPanelEnabled.get()) {
            apply(context);
        }
    }

    @NonNull public static String getRuntimeStatus() { return runtimeStatus; }
    @NonNull public static String getRuntimeDetail() { return runtimeDetail; }
    public static int getRuntimeWindowType() { return runtimeWindowType; }

    private static void start(@NonNull Context context, @NonNull String action) {
        ContextCompat.startForegroundService(context,
                new Intent(context, DriverPanelService.class).setAction(action));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        preferences = new Preferences(this);
        controller = new DriverPanelOverlayController(this, preferences, (status, detail) -> {
            runtimeStatus = status;
            runtimeDetail = detail;
            DriverPanelOverlayController current = controller;
            if (current != null) runtimeWindowType = current.getAttachedWindowType();
        });
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_APPLY : intent.getAction();
        if (ACTION_STOP.equals(action)
                || preferences == null
                || !preferences.driverPanelEnabled.get()) {
            runtimeStatus = "stopped";
            runtimeDetail = "Панель водителя выключена";
            stopSelfSafely();
            return START_NOT_STICKY;
        }
        if (controller != null) {
            boolean refreshed = controller.setNavigationHidden(navigationHidden);
            if (ACTION_STOCK_CLIMATE.equals(action)) controller.triggerStockClimate();
            else if (ACTION_FAVORITES.equals(action)) {
                String panelId = intent == null ? DriverFavoritesPanelConfig.DEFAULT_ID
                        : intent.getStringExtra(EXTRA_FAVORITES_PANEL_ID);
                controller.showFavorites(panelId == null
                        ? DriverFavoritesPanelConfig.DEFAULT_ID : panelId, null);
            }
            else if (ACTION_RAISE.equals(action)) controller.raise();
            else if (!refreshed) controller.applyPreferences();
        }
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (controller != null) controller.applyPreferences();
    }

    private void stopSelfSafely() {
        DriverPanelOverlayController current = controller;
        controller = null;
        if (current != null) current.destroy();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        DriverPanelOverlayController current = controller;
        controller = null;
        if (current != null) current.destroy();
        if (instance == this) instance = null;
        super.onDestroy();
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
                "Панель водителя", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Настраиваемая боковая панель поверх приложений");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification createNotification() {
        Intent settings = new Intent(this, DriverPanelSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 1007, settings,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_apps)
                .setContentTitle("Панель водителя")
                .setContentText("Кнопки доступны поверх приложений")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }
}
