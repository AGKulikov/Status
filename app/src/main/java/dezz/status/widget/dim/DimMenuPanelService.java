/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import dezz.status.widget.DimMenuPanelSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.StartupWorkCoordinator;

/** Foreground owner of the independent lower DIM menu. */
public final class DimMenuPanelService extends Service {
    public static final String ACTION_APPLY =
            "ru.natro.statuswidget.action.DIM_MENU_APPLY";
    public static final String ACTION_STOP =
            "ru.natro.statuswidget.action.DIM_MENU_STOP";
    private static final String CHANNEL_ID = "DimMenuPanelChannel";
    private static final int NOTIFICATION_ID = 0x44494D;
    @NonNull private static volatile String runtimeDetail = "Панель не запущена";
    @Nullable private DimMenuOverlayController controller;
    @Nullable private Preferences preferences;

    public static void apply(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences prefs = new Preferences(app);
        if (!prefs.dimMenuPanelEnabled.get()) {
            try { app.stopService(new Intent(app, DimMenuPanelService.class)); }
            catch (RuntimeException ignored) { }
            runtimeDetail = "Панель выключена";
            return;
        }
        start(app, ACTION_APPLY);
    }

    public static void stop(@NonNull Context context) {
        start(applicationContext(context), ACTION_STOP);
    }

    public static void reconcileAutomatic(@NonNull Context context) {
        Preferences prefs = new Preferences(context);
        if (prefs.dimMenuPanelEnabled.get() && prefs.dimMenuPanelAutostart.get()) {
            apply(context);
        }
    }

    @NonNull public static String runtimeDetail() { return runtimeDetail; }

    private static void start(@NonNull Context context, @NonNull String action) {
        try {
            ContextCompat.startForegroundService(context,
                    new Intent(context, DimMenuPanelService.class).setAction(action));
        } catch (RuntimeException failure) {
            runtimeDetail = "Не удалось запустить сервис: "
                    + failure.getClass().getSimpleName();
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification());
    }

    @Override public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_APPLY : intent.getAction();
        dezz.status.widget.diagnostics.ActionRecorder.recordServiceIntent(
                getClass().getName(), action, startId);
        if (ACTION_STOP.equals(action)) {
            stopSafely();
            return START_NOT_STICKY;
        }
        if (intent == null
                && StartupWorkCoordinator.shouldDeferAutomaticStickyRestart(this)) {
            stopSafely();
            return START_NOT_STICKY;
        }
        if (preferences == null) preferences = new Preferences(this);
        if (!preferences.dimMenuPanelEnabled.get()) {
            stopSafely();
            return START_NOT_STICKY;
        }
        if (controller == null) {
            controller = new DimMenuOverlayController(this, preferences,
                    detail -> runtimeDetail = detail);
            controller.start();
        } else {
            controller.reload();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        DimMenuOverlayController current = controller;
        controller = null;
        if (current != null) current.stop();
        runtimeDetail = "Панель не запущена";
        super.onDestroy();
    }

    private void stopSafely() {
        DimMenuOverlayController current = controller;
        controller = null;
        if (current != null) current.stop();
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "Меню экрана водителя", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Управление DIM-панелью кнопками руля");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification notification() {
        Intent settings = new Intent(this, DimMenuPanelSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent content = PendingIntent.getActivity(this, 0, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_navigation)
                .setContentTitle("Natro · меню экрана водителя")
                .setContentText("Управление кнопками руля")
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
