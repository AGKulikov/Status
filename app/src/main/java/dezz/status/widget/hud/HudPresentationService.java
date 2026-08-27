/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ActivityManager;
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
import dezz.status.widget.StatusWidgetApplication;
import dezz.status.widget.StartupWorkCoordinator;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.navigation.NavigationHudEndpointService;

/** Foreground owner of the stable-id external HUD presentation. */
public final class HudPresentationService extends Service
        implements DisplayManager.DisplayListener {
    public static final String ACTION_APPLY =
            "ru.natro.statuswidget.internal.APPLY_HUD_PANEL";
    public static final String ACTION_CONFIG_CHANGED =
            "ru.natro.statuswidget.internal.HUD_CONFIG_CHANGED";
    public static final String ACTION_STOP =
            "ru.natro.statuswidget.internal.STOP_HUD_PANEL";
    public static final String ACTION_DATA_CHANGED =
            "ru.natro.statuswidget.internal.HUD_DATA_CHANGED";
    public static final String ACTION_LIFECYCLE_RECONCILE =
            "ru.natro.statuswidget.internal.HUD_LIFECYCLE_RECONCILE";
    private static final String EXTRA_CONFIG_JSON =
            "ru.natro.statuswidget.internal.HUD_CONFIG_JSON";

    private static final String TAG = "HudPresentation";
    private static final String CHANNEL_ID = "HudDisplayChannel";
    private static final int NOTIFICATION_ID = 0x485544;
    /** Stay well below Android's process-wide Binder transaction limit. */
    private static final int MAX_COMMAND_CONFIG_CHARS = 240_000;
    private static final long SYSTEM_SURFACE_RETRY_MS = 15_000L;
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
    private boolean runtimeInitialized;
    private boolean customFrameReady;
    @Nullable private String shownUniqueId;
    @Nullable private Boolean requestedStockHudCarHidden;
    @NonNull private final Runnable retrySystemSurface = new Runnable() {
        @Override public void run() {
            if (!runtimeInitialized || config == null || shownUniqueId == null
                    || systemSurfaceWindow != null) {
                return;
            }
            systemSurfaceRetryAfter = 0L;
            reconcilePresentation();
        }
    };

    public static void apply(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences prefs = new Preferences(app);
        if (!prefs.hudPanelEnabled.get()) {
            try { app.stopService(new Intent(app, HudPresentationService.class)); }
            catch (RuntimeException ignored) {}
            HudRuntimeStatusStore.write(app, "HUD выключен");
            return;
        }
        sendCommand(app, ACTION_APPLY, prefs.hudPanelConfigJson.get());
    }

    public static void notifyConfigChanged(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences prefs = new Preferences(app);
        if (!prefs.hudPanelEnabled.get()) return;
        // The caller's in-memory SharedPreferences already contains the just-saved document.
        // Passing it with the command avoids an apply()/disk race during service startup.
        sendCommand(app, ACTION_CONFIG_CHANGED, prefs.hudPanelConfigJson.get());
    }

    public static void notifyAutomationChanged(@NonNull Context context) {
        HudPresentationService current = instance;
        if (current == null) {
            Context app = applicationContext(context);
            Preferences prefs = new Preferences(app);
            if (prefs.hudPanelEnabled.get()) {
                // The first HUD-related callback after boot/package replacement must construct
                // the presentation owner. 2.4.1 treated it as a data-only invalidation even when
                // no service existed; the physical HUD then received neither the clock nor
                // Navigator frames. An existing instance still takes the cheap in-process path.
                apply(app);
            }
            return;
        }
        current.main.post(() -> {
            if (current.data != null) current.data.refreshCrossProcessState();
            if (current.systemSurfaceWindow != null) {
                current.systemSurfaceWindow.invalidateHud();
            }
            if (current.overlayWindow != null) current.overlayWindow.invalidateHud();
            if (current.presentation != null) current.presentation.invalidateHud();
        });
    }

    /** Rebuilds only HUD surfaces after QuickBoot; a cold service start already builds them. */
    public static void reconcileAutomaticLifecycle(@NonNull Context context) {
        Context app = applicationContext(context);
        Preferences prefs = new Preferences(app);
        if (!prefs.hudPanelEnabled.get() || !prefs.hudPanelAutostart.get()) return;
        if (isRunning(app)) {
            sendCommand(app, ACTION_LIFECYCLE_RECONCILE, null);
        } else {
            apply(app);
        }
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** Service liveness check used without cold-starting HUD. */
    public static boolean isRunning(@NonNull Context context) {
        if (instance != null) return true;
        ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return false;
        try {
            for (ActivityManager.RunningServiceInfo info
                    : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (info.service != null
                        && HudPresentationService.class.getName().equals(
                        info.service.getClassName())) return true;
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not query HUD process liveness", failure);
        }
        return false;
    }

    @NonNull public static String runtimeDetail() { return runtimeDetail; }
    @NonNull public static String runtimeDetail(@NonNull Context context) {
        return instance != null ? runtimeDetail : HudRuntimeStatusStore.read(context);
    }

    private static void sendCommand(@NonNull Context app, @NonNull String action,
                                    @Nullable String configJson) {
        Intent command = new Intent(app, HudPresentationService.class).setAction(action);
        if (configJson != null && configJson.length() <= MAX_COMMAND_CONFIG_CHARS) {
            command.putExtra(EXTRA_CONFIG_JSON, configJson);
        }
        try {
            ContextCompat.startForegroundService(app, command);
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not send HUD command " + action, failure);
            DiagnosticJournal.error("hud-runtime",
                    "не удалось запустить HUD command=" + action, failure);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification("Подключение к HUD…"));
        DiagnosticJournal.info("hud-runtime", "HUD service создан в основном процессе Natro");
    }

    private void initializeRuntime() {
        if (runtimeInitialized) return;
        runtimeInitialized = true;
        preferences = new Preferences(this);
        store = new HudPanelStore(preferences);
        config = store.load();
        DiagnosticJournal.info("hud-runtime",
                "инициализация: enabled=" + preferences.hudPanelEnabled.get()
                        + ", autostart=" + preferences.hudPanelAutostart.get()
                        + ", displayId=" + config.displayId
                        + ", elements=" + config.elements.size());
        displayManager = getSystemService(DisplayManager.class);
        if (displayManager != null) {
            try { displayManager.registerDisplayListener(this, main); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not register display listener", failure);
                DiagnosticJournal.error("hud-runtime",
                        "не удалось зарегистрировать DisplayListener", failure);
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
        // Enable the diagnostic preference layer only after a useful HUD construction attempt,
        // never from Application startup.
        StatusWidgetApplication.notifyFirstUsefulSurface(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        DiagnosticJournal.info("hud-runtime",
                "onStartCommand action=" + (action == null ? "sticky" : action)
                        + ", startId=" + startId);
        dezz.status.widget.diagnostics.ActionRecorder.recordServiceIntent(
                getClass().getName(), action, startId);
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null
                && StartupWorkCoordinator.shouldDeferAutomaticStickyRestart(this)) {
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        initializeRuntime();
        boolean commandHasConfig = applyCommandConfig(intent);
        if (ACTION_LIFECYCLE_RECONCILE.equals(action)) {
            // WindowManager/SurfaceFlinger can recreate their state while the service and Java
            // references survive QuickBoot. Drop only visual owners and reselect the exact
            // display; data/connectors remain alive.
            dismissPresentation("automatic lifecycle reconcile");
            systemSurfaceRetryAfter = 0L;
            reloadAndReconcile(true);
        } else if (ACTION_DATA_CHANGED.equals(action)) {
            if (data != null) data.refreshCrossProcessState();
            invalidateHudSurfaces();
        } else {
            // Very large editor documents intentionally travel through the file-backed settings
            // store rather than risking TransactionTooLargeException on Android 9.
            reloadAndReconcile(!commandHasConfig);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DiagnosticJournal.warn("hud-runtime", "HUD service уничтожается");
        instance = null;
        runtimeInitialized = false;
        setRuntimeDetail("HUD не запущен");
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

    @Override public void onDisplayAdded(int displayId) {
        DiagnosticJournal.info("hud-runtime", "display added id=" + displayId);
        reloadAndReconcile();
    }
    @Override public void onDisplayRemoved(int displayId) {
        DiagnosticJournal.warn("hud-runtime", "display removed id=" + displayId);
        reconcilePresentation();
    }
    @Override public void onDisplayChanged(int displayId) {
        DiagnosticJournal.info("hud-runtime", "display changed id=" + displayId);
        reconcilePresentation();
    }

    private void reloadAndReconcile() {
        reloadAndReconcile(true);
    }

    private void reloadAndReconcile(boolean reloadPreferences) {
        if (reloadPreferences) {
            // Recreate the wrapper after an explicit settings command so API 28 reloads the
            // device-protected preference file before rebuilding the visual owner.
            preferences = new Preferences(this);
            store = new HudPanelStore(preferences);
            config = store.load();
        }
        if (data != null) data.updateConfig(config);
        NavigationHudEndpointService.notifyConfigurationChanged();
        reconcileStockHudCarPreference();
        reconcilePresentation();
    }

    private boolean applyCommandConfig(@Nullable Intent intent) {
        if (intent == null || !intent.hasExtra(EXTRA_CONFIG_JSON)) return false;
        String raw = intent.getStringExtra(EXTRA_CONFIG_JSON);
        if (raw == null) return false;
        try {
            config = HudPanelConfig.fromJson(raw);
            if (data != null) data.updateConfig(config);
            return true;
        } catch (RuntimeException invalid) {
            Log.w(TAG, "Rejected invalid HUD configuration", invalid);
            DiagnosticJournal.error("hud-runtime", "отклонена конфигурация HUD", invalid);
            return false;
        }
    }

    private void invalidateHudSurfaces() {
        if (systemSurfaceWindow != null) systemSurfaceWindow.invalidateHud();
        if (overlayWindow != null) overlayWindow.invalidateHud();
        if (presentation != null) presentation.invalidateHud();
    }

    /**
     * Pair the visual mask option with the removed stock Settings AR preference.
     *
     * <p>The ECARX implementation preserves the complete active profile and changes only its
     * legacy AR key. The stock layer is hidden only after SurfaceFlinger acknowledges a useful
     * Natro frame. Any display, bridge or service failure restores it immediately.</p>
     */
    private void reconcileStockHudCarPreference() {
        if (config == null) return;
        boolean hidden = HudStockMaskPolicy.shouldHideStockCar(
                config.maskStockHud,
                customFrameReady,
                config.hasStandaloneDrawableElement());
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

    private void setCustomFrameReady(boolean ready) {
        if (customFrameReady == ready) return;
        customFrameReady = ready;
        reconcileStockHudCarPreference();
    }

    private void reconcilePresentation() {
        if (config == null) return;
        HudDisplaySelector.Candidate candidate = HudDisplaySelector.select(this, config);
        if (candidate == null) {
            dismissPresentation("display unavailable");
            setRuntimeDetail(config.displayId < 0
                    ? "HUD не привязан — выберите точный Display ID в настройках"
                    : "Ожидание HUD с Display ID " + config.displayId);
            updateNotification(runtimeDetail);
            DiagnosticJournal.warn("hud-runtime", runtimeDetail);
            return;
        }
        if (!HudViewportPolicy.containsCompleteHudPlane(
                candidate.width, candidate.height)) {
            dismissPresentation("display is smaller than fixed HUD plane");
            setRuntimeDetail("ID " + candidate.id + ": поверхность "
                    + candidate.width + "×" + candidate.height
                    + " меньше обязательных "
                    + HudViewportPolicy.MIN_SURFACE_WIDTH + "×"
                    + HudViewportPolicy.MIN_SURFACE_HEIGHT
                    + "; вывод заблокирован");
            updateNotification(runtimeDetail);
            DiagnosticJournal.warn("hud-runtime", runtimeDetail);
            return;
        }
        String identity = candidate.uniqueId + "|" + candidate.id;
        if ((systemSurfaceWindow != null || overlayWindow != null || presentation != null)
                && identity.equals(shownUniqueId)) {
            if (systemSurfaceWindow != null) systemSurfaceWindow.updateConfig(config);
            if (overlayWindow != null) overlayWindow.updateConfig(config);
            if (presentation != null) presentation.updateConfig(config);
            Display display = HudDisplaySelector.display(candidate);
            if (systemSurfaceWindow == null && display != null && display.isValid()) {
                startSystemSurface(display);
            }
            setRuntimeDetail(runtimeDetail(candidate));
            updateNotification(runtimeDetail);
            return;
        }
        dismissPresentation("display selection changed");
        systemSurfaceRetryAfter = 0L;
        Display display = HudDisplaySelector.display(candidate);
        if (display == null || !display.isValid()) {
            DiagnosticJournal.warn("hud-runtime",
                    "выбранный HUD display недействителен: id=" + candidate.id);
            return;
        }
        try {
            shownUniqueId = identity;
            DiagnosticJournal.info("hud-runtime",
                    "создаём HUD на display id=" + candidate.id + " "
                            + candidate.width + "×" + candidate.height);
            showOnDisplay(display);
            setRuntimeDetail(runtimeDetail(candidate));
            updateNotification(runtimeDetail);
            DiagnosticJournal.info("hud-runtime", runtimeDetail);
        } catch (RuntimeException failure) {
            systemSurfaceWindow = null;
            presentation = null;
            shownUniqueId = null;
            setRuntimeDetail("HUD найден, но окно пока недоступно");
            updateNotification(runtimeDetail);
            Log.w(TAG, "Could not show HUD presentation", failure);
            DiagnosticJournal.error("hud-runtime",
                    "не удалось создать HUD presentation", failure);
        }
    }

    private void showOnDisplay(@NonNull Display display) {
        if (overlayWindow == null && presentation == null) {
            showWindowManagerFallback(display);
        }
        startSystemSurface(display);
    }

    private void startSystemSurface(@NonNull Display display) {
        if (systemSurfaceWindow != null || shownUniqueId == null || !display.isValid()) return;
        if (SystemClock.elapsedRealtime() < systemSurfaceRetryAfter) {
            scheduleSystemSurfaceRetry();
            return;
        }
        main.removeCallbacks(retrySystemSurface);
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
                            systemSurfaceRetryAfter = 0L;
                            main.removeCallbacks(retrySystemSurface);
                            setCustomFrameReady(true);
                            setRuntimeDetail("HUD: ID " + display.getDisplayId()
                                    + " · системный слой " + readyWindow.layerStack()
                                    + " · кадр принят SurfaceFlinger"
                                    + " · окно 728×190 @ (0,720)");
                            updateNotification(runtimeDetail);
                            DiagnosticJournal.info("hud-runtime", runtimeDetail);
                        }

                        @Override
                        public void onFailed(@NonNull HudSystemSurfaceWindow failedWindow,
                                             @NonNull String detail) {
                            if (systemSurfaceWindow != failedWindow) return;
                            systemSurfaceWindow = null;
                            failedWindow.dismiss();
                            setCustomFrameReady(false);
                            systemSurfaceRetryAfter = SystemClock.elapsedRealtime()
                                    + SYSTEM_SURFACE_RETRY_MS;
                            if (overlayWindow == null && presentation == null
                                    && display.isValid() && shownUniqueId != null) {
                                try {
                                    showWindowManagerFallback(display);
                                } catch (RuntimeException fallbackFailure) {
                                    Log.w(TAG, "Could not restore HUD fallback",
                                            fallbackFailure);
                                    DiagnosticJournal.error("hud-runtime",
                                            "не удалось восстановить HUD fallback",
                                            fallbackFailure);
                                }
                            }
                            setRuntimeDetail(
                                    "HUD: обычный overlay; системная маска недоступна — "
                                            + detail + "; повтор через 15 с");
                            updateNotification(runtimeDetail);
                            DiagnosticJournal.warn("hud-runtime", runtimeDetail);
                            scheduleSystemSurfaceRetry();
                        }
                    });
            systemSurfaceWindow = window;
        } catch (RuntimeException failure) {
            systemSurfaceWindow = null;
            setCustomFrameReady(false);
            systemSurfaceRetryAfter = SystemClock.elapsedRealtime()
                    + SYSTEM_SURFACE_RETRY_MS;
            Log.w(TAG, "Could not start direct HUD surface; keeping WindowManager fallback",
                    failure);
            DiagnosticJournal.error("hud-runtime",
                    "не удалось запустить прямой HUD surface; оставлен fallback", failure);
            scheduleSystemSurfaceRetry();
        }
    }

    private void scheduleSystemSurfaceRetry() {
        main.removeCallbacks(retrySystemSurface);
        if (!runtimeInitialized || shownUniqueId == null || systemSurfaceWindow != null) return;
        long delay = Math.max(0L,
                systemSurfaceRetryAfter - SystemClock.elapsedRealtime());
        main.postDelayed(retrySystemSurface, delay);
    }

    private void showWindowManagerFallback(@NonNull Display display) {
        if (Settings.canDrawOverlays(this)) {
            try {
                overlayWindow = HudOverlayWindow.show(this, display, config, data);
                presentation = null;
                DiagnosticJournal.info("hud-runtime",
                        "WindowManager HUD overlay создан на display id="
                                + display.getDisplayId());
                return;
            } catch (RuntimeException overlayFailure) {
                overlayWindow = null;
                Log.w(TAG, "Exact HUD application overlay unavailable; using Presentation",
                        overlayFailure);
                DiagnosticJournal.error("hud-runtime",
                        "HUD overlay недоступен; пробуем Presentation", overlayFailure);
            }
        }
        HudPresentation fallback = createPresentation(display);
        fallback.show();
        presentation = fallback;
        DiagnosticJournal.info("hud-runtime",
                "HUD Presentation создан на display id=" + display.getDisplayId());
    }

    @NonNull
    private HudPresentation createPresentation(@NonNull Display display) {
        HudPresentation next = new HudPresentation(
                this, display, config, data);
        next.setOnDismissListener(dialog -> {
            if (presentation == next) {
                presentation = null;
                shownUniqueId = null;
                setCustomFrameReady(false);
                if (runtimeInitialized) main.post(this::reconcilePresentation);
            }
        });
        return next;
    }

    private void dismissPresentation(@NonNull String reason) {
        if (systemSurfaceWindow != null || overlayWindow != null || presentation != null) {
            DiagnosticJournal.warn("hud-runtime", "закрываем HUD surfaces: " + reason);
        }
        main.removeCallbacks(retrySystemSurface);
        HudSystemSurfaceWindow currentSystemSurface = systemSurfaceWindow;
        HudOverlayWindow currentOverlay = overlayWindow;
        HudPresentation current = presentation;
        systemSurfaceWindow = null;
        overlayWindow = null;
        presentation = null;
        shownUniqueId = null;
        systemSurfaceRetryAfter = 0L;
        setCustomFrameReady(false);
        if (currentSystemSurface != null) currentSystemSurface.dismiss();
        if (currentOverlay != null) currentOverlay.dismiss();
        if (current != null) {
            try { current.dismiss(); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not dismiss HUD presentation: " + reason, failure);
                DiagnosticJournal.error("hud-runtime",
                        "не удалось закрыть HUD presentation: " + reason, failure);
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
                .setContentTitle("Natro · HUD")
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

    private void setRuntimeDetail(@NonNull String detail) {
        runtimeDetail = detail;
        HudRuntimeStatusStore.write(this, detail);
    }

    @NonNull
    private static Context applicationContext(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return app == null ? context : app;
    }
}
