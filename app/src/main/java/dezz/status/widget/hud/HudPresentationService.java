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
import android.os.SystemClock;
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
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;

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
    private static final long SYSTEM_SURFACE_RETRY_MS = 60_000L;
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
    private HudSystemSurfaceWindow systemSurfaceWindow;
    private long systemSurfaceRetryAfter;
    @Nullable private String shownUniqueId;
    @Nullable private Boolean requestedStockHudCarHidden;

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
            if (current.systemSurfaceWindow != null) {
                current.systemSurfaceWindow.invalidateHud();
            }
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
            HudSystemSurfaceWindow currentSystemSurface = systemSurfaceWindow;
            HudOverlayWindow currentOverlay = overlayWindow;
            HudPresentation current = presentation;
            if (currentSystemSurface != null) currentSystemSurface.invalidateHud();
            if (currentOverlay != null) currentOverlay.invalidateHud();
            if (current != null) current.invalidateHud();
        });
        data.start();
        reconcileStockHudCarPreference();
        reconcilePresentation();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        dezz.status.widget.diagnostics.ActionRecorder.recordServiceIntent(
                getClass().getName(), action, startId);
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
        reconcileStockHudCarPreference();
        reconcilePresentation();
    }

    /**
     * Pair the visual mask option with the removed stock Settings AR preference.
     *
     * <p>The ECARX implementation preserves the complete active profile and changes only its
     * legacy AR key. We intentionally do not restore it merely because this service is destroyed:
     * Android may recreate the foreground service during boot/display churn. An explicit switch
     * from mask on to mask off sends the restoring value.</p>
     */
    private void reconcileStockHudCarPreference() {
        if (config == null) return;
        boolean hidden = config.maskStockHud;
        if (requestedStockHudCarHidden != null
                && requestedStockHudCarHidden.booleanValue() == hidden) {
            return;
        }
        requestedStockHudCarHidden = hidden;
        CarIntegration integration = CarIntegrations.get(this);
        integration.setStockHudCarHidden(hidden, (success, message) -> {
            Log.i(TAG, "Stock HUD ego-car preference hidden=" + hidden
                    + ", success=" + success + ", detail=" + message);
            if (!success && requestedStockHudCarHidden != null
                    && requestedStockHudCarHidden.booleanValue() == hidden) {
                // Let the next explicit apply/display reconnect retry after a cold Binder start.
                requestedStockHudCarHidden = null;
            }
        });
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
        if ((systemSurfaceWindow != null || overlayWindow != null || presentation != null)
                && identity.equals(shownUniqueId)) {
            if (systemSurfaceWindow != null) systemSurfaceWindow.updateConfig(config);
            if (overlayWindow != null) overlayWindow.updateConfig(config);
            if (presentation != null) presentation.updateConfig(config);
            runtimeDetail = runtimeDetail(candidate);
            updateNotification(runtimeDetail);
            return;
        }
        dismissPresentation("display selection changed");
        systemSurfaceRetryAfter = 0L;
        Display display = HudDisplaySelector.display(candidate);
        if (display == null || !display.isValid()) return;
        try {
            shownUniqueId = identity;
            showOnDisplay(display);
            runtimeDetail = runtimeDetail(candidate);
            updateNotification(runtimeDetail);
        } catch (RuntimeException failure) {
            systemSurfaceWindow = null;
            presentation = null;
            shownUniqueId = null;
            runtimeDetail = "HUD найден, но окно пока недоступно";
            updateNotification(runtimeDetail);
            Log.w(TAG, "Could not show HUD presentation", failure);
        }
    }

    private void showOnDisplay(@NonNull Display display) {
        showWindowManagerFallback(display);
        if (SystemClock.elapsedRealtime() < systemSurfaceRetryAfter) return;
        try {
            HudSystemSurfaceWindow window = HudSystemSurfaceWindow.show(
                    this, display, config, data, new HudSystemSurfaceWindow.Listener() {
                        @Override
                        public void onReady(@NonNull HudSystemSurfaceWindow readyWindow) {
                            if (systemSurfaceWindow != readyWindow
                                    || shownUniqueId == null) {
                                readyWindow.dismiss();
                                return;
                            }
                            // Keep the lower WindowManager copy alive as a fail-safe. It is
                            // visually hidden by the identical direct surface when SurfaceFlinger
                            // presents correctly, but prevents the custom panel from disappearing
                            // if an OEM compositor accepts a buffer on the wrong physical output.
                            // The direct opaque frame still masks the stock HUD.
                            runtimeDetail = "HUD: ID " + display.getDisplayId()
                                    + " · системный слой " + readyWindow.layerStack()
                                    + " · кадр принят SurfaceFlinger"
                                    + " · окно 728×190 @ (0,720)";
                            updateNotification(runtimeDetail);
                        }

                        @Override
                        public void onFailed(@NonNull HudSystemSurfaceWindow failedWindow,
                                             @NonNull String detail) {
                            if (systemSurfaceWindow != failedWindow) return;
                            systemSurfaceWindow = null;
                            failedWindow.dismiss();
                            systemSurfaceRetryAfter = SystemClock.elapsedRealtime()
                                    + SYSTEM_SURFACE_RETRY_MS;
                            if (overlayWindow == null && presentation == null
                                    && display.isValid() && shownUniqueId != null) {
                                try {
                                    showWindowManagerFallback(display);
                                } catch (RuntimeException fallbackFailure) {
                                    Log.w(TAG, "Could not restore HUD fallback",
                                            fallbackFailure);
                                }
                            }
                            runtimeDetail = "HUD: обычный overlay; системная маска недоступна — "
                                    + detail;
                            updateNotification(runtimeDetail);
                        }
                    });
            systemSurfaceWindow = window;
        } catch (RuntimeException failure) {
            systemSurfaceWindow = null;
            systemSurfaceRetryAfter = SystemClock.elapsedRealtime()
                    + SYSTEM_SURFACE_RETRY_MS;
            Log.w(TAG, "Could not start direct HUD surface; keeping WindowManager fallback",
                    failure);
        }
    }

    private void showWindowManagerFallback(@NonNull Display display) {
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
        HudSystemSurfaceWindow currentSystemSurface = systemSurfaceWindow;
        HudOverlayWindow currentOverlay = overlayWindow;
        HudPresentation current = presentation;
        systemSurfaceWindow = null;
        overlayWindow = null;
        presentation = null;
        shownUniqueId = null;
        if (currentSystemSurface != null) currentSystemSurface.dismiss();
        if (currentOverlay != null) currentOverlay.dismiss();
        if (current != null) {
            try { current.dismiss(); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not dismiss HUD presentation: " + reason, failure);
            }
        }
    }

    @NonNull
    private String runtimeDetail(@NonNull HudDisplaySelector.Candidate candidate) {
        String mode;
        if (systemSurfaceWindow != null) {
            mode = systemSurfaceWindow.isReady()
                    ? "системный SurfaceFlinger-слой " + systemSurfaceWindow.layerStack()
                    : "системная маска запускается";
        } else if (overlayWindow != null) {
            mode = "overlay";
        } else {
            mode = "presentation";
        }
        return "HUD: " + candidate.name + " · ID " + candidate.id
                + " · поверхность " + candidate.width + "×" + candidate.height
                + " · окно 728×190 @ (0,720) · " + mode;
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
