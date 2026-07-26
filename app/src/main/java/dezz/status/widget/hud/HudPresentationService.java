/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import dezz.status.widget.HudPanelSettingsActivity;
import dezz.status.widget.Preferences;
import dezz.status.widget.R;

/** Foreground owner of the stable-id external HUD presentation. */
public final class HudPresentationService extends Service
        implements DisplayManager.DisplayListener {
    public static final String ACTION_APPLY =
            "ru.natro.statuswidget.internal.APPLY_HUD_PANEL";
    public static final String ACTION_CONFIG_CHANGED =
            "ru.natro.statuswidget.internal.HUD_CONFIG_CHANGED";
    public static final String ACTION_STOP =
            "ru.natro.statuswidget.internal.STOP_HUD_PANEL";

    private static final String TAG = "HudPresentation";
    private static final String CHANNEL_ID = "HudDisplayChannel";
    private static final int NOTIFICATION_ID = 0x485544;
    @Nullable private static volatile HudPresentationService instance;
    @NonNull private static volatile String runtimeDetail = "HUD не запущен";

    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    private Preferences preferences;
    private HudPanelStore store;
    private HudPanelConfig config;
    private DisplayManager displayManager;
    private HudRuntimeData data;
    private HudOverlayWindow overlayWindow;
    private HudPresentation presentation;
    @Nullable private String shownUniqueId;

    public static void apply(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences prefs = new Preferences(app);
        if (!prefs.hudPanelEnabled.get()) {
            HudPresentationService current = instance;
            if (current != null) {
                try {
                    app.startService(new Intent(app, HudPresentationService.class)
                            .setAction(ACTION_STOP));
                } catch (RuntimeException ignored) {
                    app.stopService(new Intent(app, HudPresentationService.class));
                }
            }
            return;
        }
        try {
            ContextCompat.startForegroundService(app,
                    new Intent(app, HudPresentationService.class).setAction(ACTION_APPLY));
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not start HUD service", failure);
        }
    }

    public static void notifyConfigChanged(@NonNull Context context) {
        HudPresentationService current = instance;
        if (current != null) {
            current.main.post(current::reloadAndReconcile);
        } else if (new Preferences(context).hudPanelEnabled.get()) {
            apply(context);
        }
    }

    public static void notifyAutomationChanged(@NonNull Context context) {
        HudPresentationService current = instance;
        if (current != null) current.main.post(() -> {
            if (current.overlayWindow != null) current.overlayWindow.invalidateHud();
            if (current.presentation != null) current.presentation.invalidateHud();
        });
    }

    @NonNull public static String runtimeDetail() { return runtimeDetail; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        preferences = new Preferences(this);
        store = new HudPanelStore(preferences);
        config = store.load();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Подключение к HUD…"));
        displayManager = getSystemService(DisplayManager.class);
        if (displayManager != null) {
            try { displayManager.registerDisplayListener(this, main); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not register display listener", failure);
            }
        }
        data = new HudRuntimeData(this, config, () -> {
            HudOverlayWindow currentOverlay = overlayWindow;
            HudPresentation current = presentation;
            if (currentOverlay != null) currentOverlay.invalidateHud();
            if (current != null) current.invalidateHud();
        });
        data.start();
        reconcilePresentation();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action) || !preferences.hudPanelEnabled.get()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        reloadAndReconcile();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        runtimeDetail = "HUD не запущен";
        dismissPresentation("service stopped");
        if (data != null) data.stop();
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(this); }
            catch (RuntimeException ignored) {}
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Nullable
    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDisplayAdded(int displayId) { reloadAndReconcile(); }
    @Override public void onDisplayRemoved(int displayId) { reconcilePresentation(); }
    @Override public void onDisplayChanged(int displayId) { reconcilePresentation(); }

    private void reloadAndReconcile() {
        if (preferences == null || !preferences.hudPanelEnabled.get()) {
            stopSelf();
            return;
        }
        config = store.load();
        if (data != null) data.updateConfig(config);
        reconcilePresentation();
    }

    private void reconcilePresentation() {
        if (config == null) return;
        HudDisplaySelector.Candidate candidate = HudDisplaySelector.select(this, config);
        if (candidate == null) {
            dismissPresentation("display unavailable");
            runtimeDetail = config.displayId < 0
                    ? "HUD не привязан — выберите точный Display ID в настройках"
                    : "Ожидание HUD с Display ID " + config.displayId;
            updateNotification(runtimeDetail);
            return;
        }
        if (!HudViewportPolicy.containsCompleteHudPlane(
                candidate.width, candidate.height)) {
            dismissPresentation("display is smaller than fixed HUD plane");
            runtimeDetail = "ID " + candidate.id + ": поверхность "
                    + candidate.width + "×" + candidate.height
                    + " меньше обязательных "
                    + HudViewportPolicy.MIN_SURFACE_WIDTH + "×"
                    + HudViewportPolicy.MIN_SURFACE_HEIGHT
                    + "; вывод заблокирован";
            updateNotification(runtimeDetail);
            return;
        }
        String identity = candidate.uniqueId + "|" + candidate.id;
        if ((overlayWindow != null || presentation != null)
                && identity.equals(shownUniqueId)) {
            if (overlayWindow != null) overlayWindow.updateConfig(config);
            if (presentation != null) presentation.updateConfig(config);
            runtimeDetail = "HUD: " + candidate.name + " · ID " + candidate.id
                    + " · поверхность " + candidate.width + "×" + candidate.height
                    + " · окно 728×190 @ (0,720)"
                    + (overlayWindow != null ? " · overlay" : " · presentation");
            updateNotification(runtimeDetail);
            return;
        }
        dismissPresentation("display selection changed");
        Display display = HudDisplaySelector.display(candidate);
        if (display == null || !display.isValid()) return;
        try {
            showOnDisplay(display);
            shownUniqueId = identity;
            runtimeDetail = "HUD: " + candidate.name + " · ID " + candidate.id
                    + " · поверхность " + candidate.width + "×" + candidate.height
                    + " · окно 728×190 @ (0,720)"
                    + (overlayWindow != null ? " · overlay" : " · presentation");
            updateNotification(runtimeDetail);
        } catch (RuntimeException failure) {
            presentation = null;
            shownUniqueId = null;
            runtimeDetail = "HUD найден, но окно пока недоступно";
            updateNotification(runtimeDetail);
            Log.w(TAG, "Could not show HUD presentation", failure);
        }
    }

    private void showOnDisplay(@NonNull Display display) {
        if (Settings.canDrawOverlays(this)) {
            try {
                overlayWindow = HudOverlayWindow.show(this, display, config, data);
                presentation = null;
                return;
            } catch (RuntimeException overlayFailure) {
                overlayWindow = null;
                Log.w(TAG, "Exact HUD application overlay unavailable; using Presentation",
                        overlayFailure);
            }
        }
        HudPresentation fallback = createPresentation(display);
        fallback.show();
        presentation = fallback;
    }

    @NonNull
    private HudPresentation createPresentation(@NonNull Display display) {
        HudPresentation next = new HudPresentation(
                this, display, config, data);
        next.setOnDismissListener(dialog -> {
            if (presentation == next) {
                presentation = null;
                shownUniqueId = null;
            }
        });
        return next;
    }

    private void dismissPresentation(@NonNull String reason) {
        HudOverlayWindow currentOverlay = overlayWindow;
        HudPresentation current = presentation;
        overlayWindow = null;
        presentation = null;
        shownUniqueId = null;
        if (currentOverlay != null) currentOverlay.dismiss();
        if (current != null) {
            try { current.dismiss(); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not dismiss HUD presentation: " + reason, failure);
            }
        }
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "HUD-дисплей", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Отображение на отдельном HUD-дисплее");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @NonNull
    private Notification notification(@NonNull String text) {
        Intent settings = new Intent(this, HudPanelSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, settings,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_smart_car)
                .setContentTitle("Status Widget · HUD")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    private void updateNotification(@NonNull String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
