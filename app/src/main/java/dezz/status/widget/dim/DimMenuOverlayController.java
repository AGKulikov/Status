/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.Context;
import android.content.Intent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.LauncherActivity;
import dezz.status.widget.Permissions;
import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.driver.DriverPanelActionExecutor;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.SmartHomeShortcutStateBindingPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutStatePolicy;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.sprut.SprutHubController;

/** Owns selection, conflicts and the exact display-2 overlay. */
final class DimMenuOverlayController implements DisplayManager.DisplayListener,
        DimMenuVendorBridge.Listener, DriverPanelActionExecutor.Host {
    interface RuntimeReporter {
        void report(@NonNull String detail);
    }

    private static final String TAG = "DimMenuController";
    private static final long MONITOR_INTERVAL_MS = 800L;
    private static final long ACTION_CLOSE_MS = 1_500L;
    private static final long FAILED_ATTACH_BACKOFF_MS = 2_000L;
    private static final long FOREGROUND_LOOKBACK_MS = 60_000L;
    private static final long FOREGROUND_FALLBACK_CACHE_MS = 2_000L;
    private static final String MNAVI_PACKAGE = "dd.monjaro.navi";

    @NonNull private final Context context;
    @NonNull private final Preferences preferences;
    @NonNull private final DimMenuPanelStore panelStore;
    @NonNull private final LauncherShortcutStore actionStore;
    @NonNull private final DimMenuSelectionModel selection =
            new DimMenuSelectionModel(0);
    @NonNull private final DriverPanelActionExecutor executor;
    @NonNull private final DimMenuVendorBridge vendor;
    @NonNull private final RuntimeReporter reporter;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private final DisplayManager displayManager;
    @NonNull private DimMenuPanelConfig config;
    @NonNull private List<LauncherShortcutStore.Shortcut> actions = new ArrayList<>();
    @NonNull private final Map<String, ConnectorValue> smartHomeValues = new HashMap<>();
    @NonNull private Map<String, IntentActionRule> smartHomeRules = Collections.emptyMap();
    @Nullable private WidgetService smartHomeValueService;
    @Nullable private DimMenuOverlayWindow overlay;
    private boolean started;
    private long suppressedUntil;
    private long attachRetryAfter;
    private long nextForegroundFallbackCheck;
    private boolean cachedMnavActive;
    @Nullable private String lastDetail;

    @NonNull private final ConnectorValueRegistry.Listener smartHomeValueListener = changed -> {
        // Connector registries may reuse the callback collection after returning.
        List<ConnectorValue> snapshot = new ArrayList<>(changed);
        main.post(() -> applySmartHomeChanges(snapshot));
    };
    @NonNull private final Runnable ensureSmartHomeValueSubscription = new Runnable() {
        @Override public void run() {
            if (!started) return;
            WidgetService current = WidgetService.getInstance();
            if (current != smartHomeValueService) {
                if (smartHomeValueService != null) {
                    smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
                }
                smartHomeValueService = current;
                if (current == null) {
                    smartHomeValues.clear();
                    applySmartHomeStatuses();
                } else {
                    applySmartHomeValues(
                            current.addConnectorValueListener(smartHomeValueListener));
                }
            }
            main.postDelayed(this, current == null ? 250L : 2_000L);
        }
    };

    @NonNull private final Runnable monitor = new Runnable() {
        @Override public void run() {
            if (!started) return;
            reconcile();
            main.postDelayed(this, MONITOR_INTERVAL_MS);
        }
    };

    DimMenuOverlayController(@NonNull Context context,
                             @NonNull Preferences preferences,
                             @NonNull RuntimeReporter reporter) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.preferences = preferences;
        this.reporter = reporter;
        panelStore = new DimMenuPanelStore(preferences);
        actionStore = LauncherShortcutStore.forDimMenu(preferences);
        config = panelStore.load();
        executor = new DriverPanelActionExecutor(this.context, preferences, this);
        vendor = new DimMenuVendorBridge(this.context, this);
        displayManager = this.context.getSystemService(DisplayManager.class);
    }

    void start() {
        if (started) return;
        started = true;
        if (displayManager != null) {
            try { displayManager.registerDisplayListener(this, main); }
            catch (RuntimeException failure) {
                Log.w(TAG, "Could not register display listener", failure);
            }
        }
        vendor.start();
        reload();
        main.post(ensureSmartHomeValueSubscription);
        main.post(monitor);
    }

    void reload() {
        config = panelStore.load();
        actionStore.load();
        actions = interactive(actionStore.all());
        smartHomeRules = loadSmartHomeRules();
        selection.setItemCount(actions.size());
        dismiss();
        attachRetryAfter = 0L;
        reconcile();
    }

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(monitor);
        main.removeCallbacks(ensureSmartHomeValueSubscription);
        if (smartHomeValueService != null) {
            smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
            smartHomeValueService = null;
        }
        smartHomeValues.clear();
        vendor.stop();
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(this); }
            catch (RuntimeException ignored) { }
        }
        dismiss();
    }

    @Override public void onPrevious() { move(-1); }
    @Override public void onNext() { move(1); }

    @Override public void onConfirm() {
        if (overlay == null || !selection.hasSelection()
                || selection.selectedIndex() >= actions.size()) return;
        LauncherShortcutStore.Shortcut selected = actions.get(selection.selectedIndex());
        executor.execute(selected);
        if (config.closeAfterAction) {
            suppressedUntil = SystemClock.uptimeMillis() + ACTION_CLOSE_MS;
            dismiss();
        }
    }

    @Override public void onVendorStateChanged() { reconcile(); }

    private void move(int rawDelta) {
        if (overlay == null || actions.isEmpty()) return;
        int delta = config.invertScroll ? -rawDelta : rawDelta;
        selection.move(delta, config.wrapSelection);
        overlay.setSelectedIndex(selection.selectedIndex());
    }

    private void reconcile() {
        if (!started) return;
        Display display = displayManager == null ? null
                : displayManager.getDisplay(config.displayId);
        boolean interactive = display != null && display.getState() != Display.STATE_OFF;
        DimMenuConflictPolicy.Reason reason = DimMenuConflictPolicy.reason(
                panelStore.isEnabled(), interactive, vendor.isEngineOn(),
                isMnavActive(), vendor.currentTab(), vendor.controlCenterState(), config);
        if (SystemClock.uptimeMillis() < suppressedUntil) {
            dismiss();
            report("Действие выполнено · панель временно скрыта");
            return;
        }
        if (reason != DimMenuConflictPolicy.Reason.NONE) {
            dismiss();
            report(reasonLabel(reason));
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            dismiss();
            report("Нет разрешения «Поверх других приложений»");
            return;
        }
        if (overlay != null) {
            overlay.setStatuses(smartHomeStatusLabels());
            report(runtimeReadyLabel());
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (display == null || now < attachRetryAfter) return;
        try {
            overlay = DimMenuOverlayWindow.show(context, display, config,
                    actions, selection.selectedIndex());
            overlay.setStatuses(smartHomeStatusLabels());
            report(runtimeReadyLabel());
        } catch (RuntimeException failure) {
            attachRetryAfter = now + FAILED_ATTACH_BACKOFF_MS;
            report("Не удалось открыть окно: " + failure.getClass().getSimpleName());
            Log.w(TAG, "Could not attach DIM menu", failure);
        }
    }

    private boolean isMnavActive() {
        if (!config.hideForMnav) return false;
        WidgetAccessibilityService accessibility = WidgetAccessibilityService.getInstance();
        if (accessibility != null) {
            String foreground = accessibility.getForegroundPackageOnDisplay(config.displayId);
            if (foreground != null) return MNAVI_PACKAGE.equals(foreground);
        }
        if (!Permissions.isUsageAccessGranted(context)) return false;
        long uptime = SystemClock.uptimeMillis();
        if (uptime < nextForegroundFallbackCheck) return cachedMnavActive;
        nextForegroundFallbackCheck = uptime + FOREGROUND_FALLBACK_CACHE_MS;
        try {
            UsageStatsManager manager = context.getSystemService(UsageStatsManager.class);
            if (manager == null) return false;
            long now = System.currentTimeMillis();
            UsageEvents events = manager.queryEvents(now - FOREGROUND_LOOKBACK_MS, now);
            UsageEvents.Event event = new UsageEvents.Event();
            String latestPackage = null;
            long latestTimestamp = 0L;
            while (events.getNextEvent(event)) {
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND
                        && event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latestPackage = event.getPackageName();
                }
            }
            cachedMnavActive = MNAVI_PACKAGE.equals(latestPackage);
            return cachedMnavActive;
        } catch (RuntimeException ignored) {
            cachedMnavActive = false;
            return false;
        }
    }

    @NonNull
    private String runtimeReadyLabel() {
        String controls = vendor.isConnected() ? "ECARX + кнопки руля"
                : "кнопки руля · ECARX переподключается";
        return "Показана на дисплее " + config.displayId + " · " + controls
                + " · действий: " + actions.size();
    }

    @NonNull
    private static String reasonLabel(@NonNull DimMenuConflictPolicy.Reason reason) {
        switch (reason) {
            case DISABLED: return "Панель выключена";
            case DISPLAY_OFF: return "Дисплей водителя недоступен";
            case ENGINE_OFF: return "Скрыта: зажигание выключено";
            case MNAVI: return "Скрыта: экраном управляет mNavi";
            case CONTROL_CENTER: return "Скрыта: открыт штатный звонок или медиаплеер";
            case OTHER_DIM_TAB: return "Ожидает штатную вкладку «Навигация»";
            case NONE:
            default: return "Готова";
        }
    }

    private void report(@NonNull String detail) {
        if (detail.equals(lastDetail)) return;
        lastDetail = detail;
        reporter.report(detail);
    }

    private void dismiss() {
        DimMenuOverlayWindow current = overlay;
        overlay = null;
        if (current != null) current.dismiss();
    }

    @NonNull
    private Map<String, IntentActionRule> loadSmartHomeRules() {
        Map<String, IntentActionRule> result = new HashMap<>();
        try {
            for (IntentActionRule rule : new IntentActionRuleStore(preferences).loadStrict()) {
                result.put(rule.id, rule);
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not load DIM smart-home shortcuts", failure);
        }
        return result;
    }

    private void applySmartHomeValues(@NonNull Collection<ConnectorValue> values) {
        smartHomeValues.clear();
        for (ConnectorValue value : values) {
            smartHomeValues.put(smartHomeKey(value), value);
        }
        applySmartHomeStatuses();
    }

    private void applySmartHomeChanges(@NonNull Collection<ConnectorValue> values) {
        for (ConnectorValue value : values) {
            smartHomeValues.put(smartHomeKey(value), value);
        }
        applySmartHomeStatuses();
    }

    private void applySmartHomeStatuses() {
        DimMenuOverlayWindow current = overlay;
        if (current != null) current.setStatuses(smartHomeStatusLabels());
    }

    @NonNull
    private Map<String, String> smartHomeStatusLabels() {
        Map<String, String> result = new HashMap<>();
        SprutHubController sprut = SprutHubController.active();
        for (LauncherShortcutStore.Shortcut shortcut : actions) {
            if (shortcut.kind != LauncherShortcutStore.Kind.RULE || !shortcut.showState) continue;
            IntentActionRule rule = smartHomeRules.get(shortcut.target);
            SourceBinding source = SmartHomeShortcutStateBindingPolicy.resolve(shortcut, rule,
                    sprut == null ? null : sprut.catalog());
            ConnectorValue value = source == null
                    ? null : smartHomeValues.get(smartHomeKey(source));
            SmartHomeShortcutStatePolicy.State state =
                    SmartHomeShortcutStatePolicy.resolveValue(shortcut, rule, source, value);
            result.put(shortcut.id, state.present ? state.valueLabel : "Нет данных");
        }
        return result;
    }

    @NonNull
    private static String smartHomeKey(@NonNull ConnectorValue value) {
        return value.connectorType.jsonName() + '\u0000' + value.connectorId + '\u0000'
                + value.resourceId;
    }

    @NonNull
    private static String smartHomeKey(@NonNull SourceBinding value) {
        return value.connectorType.jsonName() + '\u0000' + value.connectorId + '\u0000'
                + value.resourceId;
    }

    @NonNull
    private static List<LauncherShortcutStore.Shortcut> interactive(
            @NonNull List<LauncherShortcutStore.Shortcut> source) {
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut value : source) {
            if (value.enabled && LauncherShortcutStore.isInteractive(value)) result.add(value);
        }
        return result;
    }

    @Override public void onDisplayAdded(int displayId) { reconcile(); }
    @Override public void onDisplayRemoved(int displayId) { reconcile(); }
    @Override public void onDisplayChanged(int displayId) { reconcile(); }

    @Override public void showAllApps(@Nullable View anchor) {
        try {
            context.startActivity(new Intent(context, LauncherActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException failure) {
            toast("Не удалось открыть приложения");
        }
    }

    @Override public void showFavorites(@NonNull String panelId, @Nullable View anchor) {
        DriverPanelService.showFavorites(context, panelId);
    }

    @Override public void triggerStockClimate() {
        DriverPanelService.triggerStockClimate(context);
    }

    @Nullable
    @Override public CarControlState carControlState(@NonNull String controlId) {
        return null;
    }

    private void toast(@NonNull String message) {
        main.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
