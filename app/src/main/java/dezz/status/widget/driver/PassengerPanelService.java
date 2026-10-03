/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import dezz.status.widget.PassengerPanelSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.StartupWorkCoordinator;

/** Independent owner: survives late display arrival and never falls back to display zero. */
public final class PassengerPanelService extends Service implements DisplayManager.DisplayListener {
    private static final String CHANNEL = "PassengerPanelChannel";
    private static final int NOTIFICATION = 1021;
    private static final String ACTION_FAVORITES = "dezz.status.widget.PASSENGER_FAVORITES";
    private static final String EXTRA_PANEL = "panel";
    private static volatile PassengerPanelService instance;
    public static boolean isRunning() { return instance != null; }
    private static volatile String detail = "Панель пассажира выключена";
    private final Handler main = new Handler(Looper.getMainLooper());
    private Preferences preferences;
    private DisplayManager displays;
    private DriverPanelOverlayController controller;
    private int retries;
    private boolean listening;
    private boolean destroyed;
    private final Runnable retry = this::reconcile;

    public static String getRuntimeDetail() { return detail; }
    public static void apply(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, PassengerPanelService.class));
    }
    public static void showFavorites(Context context, String panelId) {
        ContextCompat.startForegroundService(context, new Intent(context, PassengerPanelService.class)
                .setAction(ACTION_FAVORITES).putExtra(EXTRA_PANEL, panelId));
    }
    public static void triggerStockClimate(Context context) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(
                dezz.status.widget.climate.StockHvacPopupClient.SERVICE_PACKAGE);
        try {
            if (intent == null) throw new IllegalStateException("No passenger climate Activity");
            PanelDisplayLauncher.start(context.getApplicationContext(), intent, PassengerPanelPlacement.DISPLAY_ID);
        } catch (RuntimeException error) {
            dezz.status.widget.launcher.PassengerHomeLauncher.reportFailure(context, error);
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Панель пассажира", NotificationManager.IMPORTANCE_LOW));
        PendingIntent settings = PendingIntent.getActivity(this, NOTIFICATION,
                new Intent(this, PassengerPanelSettingsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(NOTIFICATION, new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_apps).setContentTitle("Панель пассажира")
                .setContentText("Боковая панель на пассажирском экране")
                .setContentIntent(settings).setOngoing(true).build());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null && StartupWorkCoordinator.shouldDeferAutomaticStickyRestart(this)) {
            StartupWorkCoordinator.ensureIntegrationHostScheduled(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (preferences == null) preferences = new Preferences(this);
        if (displays == null) displays = getSystemService(DisplayManager.class);
        if (displays != null && !listening) {
            displays.registerDisplayListener(this, main);
            listening = true;
        }
        retries = 0;
        reconcile();
        if (intent != null && ACTION_FAVORITES.equals(intent.getAction())) {
            if (controller != null) {
                String panelId = intent.getStringExtra(EXTRA_PANEL);
                controller.showFavorites(panelId == null ? DriverFavoritesPanelConfig.DEFAULT_ID : panelId, null);
            } else android.widget.Toast.makeText(this,
                    "Включите панель и экран пассажира для показа избранного",
                    android.widget.Toast.LENGTH_LONG).show();
        }
        return preferences.passengerPanelEnabled.get() ? START_STICKY : START_NOT_STICKY;
    }
    private void reconcile() {
        main.removeCallbacks(retry);
        if (destroyed || preferences == null) return;
        if (!preferences.passengerPanelEnabled.get()) {
            detach();
            detail = "Панель пассажира выключена";
            stopForeground(true);
            stopSelf();
            return;
        }
        Display display = displays == null ? null : displays.getDisplay(PassengerPanelPlacement.DISPLAY_ID);
        if (display == null || !display.isValid() || display.getState() == Display.STATE_OFF) {
            detach();
            detail = "Ожидание пассажирского дисплея 3";
            scheduleRetry();
            return;
        }
        if (controller == null) controller = new DriverPanelOverlayController(this, preferences,
                (status, message) -> {
                    detail = "Дисплей 3: " + message.replace("водителя", "пассажира");
                    if ("error".equals(status)) scheduleRetry();
                }, true);
        controller.applyPreferences();
    }
    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if (!destroyed && retries++ < 12) main.postDelayed(retry, 5000L);
        // DisplayListener remains armed after the bounded startup poll.
    }
    private void detach() {
        if (controller != null) controller.destroy();
        controller = null;
    }
    private void displayChanged(int id) {
        if (id != PassengerPanelPlacement.DISPLAY_ID) return;
        retries = 0;
        detach();
        reconcile();
    }
    @Override public void onDisplayAdded(int id) { displayChanged(id); }
    @Override public void onDisplayChanged(int id) { displayChanged(id); }
    @Override public void onDisplayRemoved(int id) { displayChanged(id); }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        reconcile();
    }
    @Override public void onDestroy() {
        destroyed = true;
        if (instance == this) instance = null;
        main.removeCallbacksAndMessages(null);
        if (listening && displays != null) displays.unregisterDisplayListener(this);
        detach();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
