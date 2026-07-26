/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.AppUninstallLauncher;
import dezz.status.widget.launcher.InformationShortcutView;
import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.launcher.LauncherAppTileRenderer;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.SmartHomeShortcutStateBindingPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutStatePolicy;
import dezz.status.widget.launcher.apps.FavoriteAppConfig;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.shell.PrivilegedShell;
import dezz.status.widget.sprut.SprutHubController;

/**
 * Owns the selected Monjaro driver rail and the overlay all-apps drawer.
 *
 * <p>The rail is always one continuous window. Its movable climate shortcut uses the already
 * normalized live climate state and temporarily removes the whole rail from input hit-testing
 * while an accessibility gesture taps the covered OEM climate coordinate.</p>
 */
final class DriverPanelOverlayController implements DriverPanelActionExecutor.Host {
    interface StatusListener {
        void onStatus(@NonNull String status, @NonNull String detail);
    }

    private static final String TAG = "DriverPanelOverlay";
    private static final int DISPLAY_ID = Display.DEFAULT_DISPLAY;
    private static final long PROXY_TAP_SETTLE_MS = 70L;
    private static final long PROXY_TAP_WATCHDOG_MS = 15_000L;

    private final Context appContext;
    private final Preferences preferences;
    private final StatusListener statusListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "driver-panel-app-catalog");
        thread.setDaemon(true);
        return thread;
    });
    private final DriverPanelActionExecutor actions;
    private final Map<String, SmartHomeBinding> panelSmartHomeBindings = new HashMap<>();
    private final Map<String, SmartHomeBinding> drawerSmartHomeBindings = new HashMap<>();
    private final Map<String, View> favoritePanelAnchors = new HashMap<>();
    private final Map<String, FavoritePanelWindow> favoriteWindows = new LinkedHashMap<>();
    private final Set<String> manuallyOpenFavorites = new LinkedHashSet<>();
    private final Map<String, ConnectorValue> smartHomeValues = new HashMap<>();
    private Map<String, IntentActionRule> smartHomeRules = Collections.emptyMap();
    @Nullable private WidgetService smartHomeValueService;
    private final ConnectorValueRegistry.Listener smartHomeValueListener = changed -> {
        // Connector registries may reuse/mutate their callback collection after returning.
        // Snapshot it before crossing to the main thread.
        List<ConnectorValue> snapshot = new ArrayList<>(changed);
        mainHandler.post(() -> applySmartHomeChanges(snapshot));
    };
    private final Runnable ensureSmartHomeValueSubscription = new Runnable() {
        @Override public void run() {
            WidgetService current = WidgetService.getInstance();
            if (current != smartHomeValueService) {
                if (smartHomeValueService != null) {
                    smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
                }
                smartHomeValueService = current;
                if (current == null) {
                    smartHomeValues.clear();
                    applySmartHomeStates();
                } else {
                    applySmartHomeValues(
                            current.addConnectorValueListener(smartHomeValueListener));
                }
            }
            mainHandler.postDelayed(this, current == null ? 250L : 2_000L);
        }
    };

    private final List<AttachedWindow> panelWindows = new ArrayList<>();
    @Nullable private AttachedWindow drawerWindow;
    @Nullable private GridView drawerGrid;
    private int attachedType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private int applyGeneration;
    private int drawerGeneration;
    private int proxyTapGeneration;
    private boolean navigationHidden;

    DriverPanelOverlayController(@NonNull Context context,
                                 @NonNull Preferences preferences,
                                 @NonNull StatusListener statusListener) {
        this.appContext = context.getApplicationContext();
        this.preferences = preferences;
        this.statusListener = statusListener;
        this.actions = new DriverPanelActionExecutor(appContext, preferences, this);
    }

    int getAttachedWindowType() {
        return attachedType;
    }

    void applyPreferences() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::applyPreferences);
            return;
        }
        int generation = ++applyGeneration;
        // Transactional replacement: keep the currently opaque rail attached until its
        // successor is already accepted by WindowManager. This prevents even a one-frame flash
        // of the covered OEM panel during app switches, configuration updates and z-order raises.
        List<AttachedWindow> previousWindows = new ArrayList<>(panelWindows);
        Map<String, SmartHomeBinding> previousBindings =
                new HashMap<>(panelSmartHomeBindings);
        Map<String, View> previousFavoriteAnchors = new HashMap<>(favoritePanelAnchors);
        panelWindows.clear();
        panelSmartHomeBindings.clear();
        favoritePanelAnchors.clear();
        smartHomeRules = loadSmartHomeRules();
        mainHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        mainHandler.post(ensureSmartHomeValueSubscription);
        if (!preferences.driverPanelEnabled.get()) {
            removeWindows(previousWindows);
            dismissAllApps();
            dismissAllFavoritePanels();
            statusListener.onStatus("stopped", "Панель водителя выключена");
            return;
        }

        Display display = defaultDisplay();
        if (display == null) {
            panelWindows.addAll(previousWindows);
            panelSmartHomeBindings.putAll(previousBindings);
            favoritePanelAnchors.putAll(previousFavoriteAnchors);
            refreshFavoriteWindows();
            statusListener.onStatus("error", "Основной дисплей не найден");
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        LauncherShortcutStore store = LauncherShortcutStore.forDriverPanel(preferences, profile);
        WidgetService widgetService = WidgetService.getInstance();
        List<LauncherShortcutStore.Shortcut> informational = new ArrayList<>();
        List<LauncherShortcutStore.Shortcut> controls = new ArrayList<>();
        int interactiveCount = 0;
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            boolean scenarioVisible = widgetService == null
                    || widgetService.driverShortcutVisible(shortcut.id, true);
            if (!shortcut.enabled || !scenarioVisible) continue;
            if (shortcut.kind == LauncherShortcutStore.Kind.INFO) {
                informational.add(shortcut);
                continue;
            }
            if (LauncherShortcutStore.isInteractive(shortcut)
                    && interactiveCount >= DriverPanelLayoutPolicy.MAX_BUTTONS) continue;
            if (LauncherShortcutStore.isInteractive(shortcut)) interactiveCount++;
            controls.add(shortcut);
        }
        List<LauncherShortcutStore.Shortcut> enabled = new ArrayList<>(
                informational.size() + controls.size());
        enabled.addAll(informational);
        enabled.addAll(controls);
        DriverPanelLayoutPolicy.Layout geometry = DriverPanelLayoutPolicy.calculate(
                metrics.heightPixels,
                profile.topPaddingPx.get(),
                profile.bottomPaddingPx.get(),
                interactiveCount,
                false);

        RuntimeException failure = null;
        for (int type : DriverPanelWindowTypePolicy.candidates()) {
            if (generation != applyGeneration) return;
            try {
                attachForType(display, type, enabled, geometry,
                        metrics.widthPixels, metrics.heightPixels, profile);
                attachedType = type;
                List<AttachedWindow> successorWindows = new ArrayList<>(panelWindows);
                panelWindows.clear();
                panelWindows.addAll(previousWindows);
                panelWindows.addAll(successorWindows);
                retireAfterFirstDraw(previousWindows, successorWindows);
                refreshFavoriteWindows();
                reconcileAutomatedFavoritePanels();
                String mode = type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        ? "обычный overlay" : "системный ECARX";
                String pocket = "кнопки используют всю высоту; климат открывается прокси-кнопкой";
                statusListener.onStatus("active",
                        "Панель водителя · " + interactiveCount + " кнопок · "
                                + informational.size() + " инфо · "
                                + mode + " · " + pocket);
                return;
            } catch (RuntimeException error) {
                failure = error;
                removeWindows(panelWindows);
                panelWindows.clear();
                panelSmartHomeBindings.clear();
                Log.w(TAG, "Window type " + type + " rejected", error);
            }
        }
        // A rejected refresh must leave the last fully covering panel in place.
        panelWindows.addAll(previousWindows);
        panelSmartHomeBindings.putAll(previousBindings);
        favoritePanelAnchors.putAll(previousFavoriteAnchors);
        refreshFavoriteWindows();
        statusListener.onStatus("error", failure == null
                ? "Не удалось добавить панель"
                : "WindowManager отклонил панель: " + failure.getClass().getSimpleName());
    }

    /**
     * Keeps the last opaque rail alive until SurfaceFlinger has had time to present its
     * successor. WindowManager#addView only accepts the window synchronously; it does not mean
     * the first buffer is already on screen.
     */
    private void retireAfterFirstDraw(@NonNull List<AttachedWindow> previous,
                                      @NonNull List<AttachedWindow> successors) {
        if (previous.isEmpty() || successors.isEmpty()) return;
        AttachedWindow anchor = successors.get(successors.size() - 1);
        ViewTreeObserver.OnPreDrawListener[] callback =
                new ViewTreeObserver.OnPreDrawListener[1];
        callback[0] = () -> {
            ViewTreeObserver observer = anchor.view.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnPreDrawListener(callback[0]);
            // Keep both opaque buffers for one complete additional vsync. The new rail was added
            // later and therefore remains visually/input-wise above the retiring copy.
            anchor.view.postOnAnimation(() -> anchor.view.postOnAnimation(() -> {
                removeWindows(previous);
                panelWindows.removeAll(previous);
            }));
            return true;
        };
        anchor.view.getViewTreeObserver().addOnPreDrawListener(callback[0]);
    }

    boolean setNavigationHidden(boolean hidden) {
        if (navigationHidden == hidden) return false;
        navigationHidden = hidden;
        // Fullscreen/system-bar events are treated as z-order hints only. The replacement panel
        // never disappears merely because an app asked Android to hide navigation chrome.
        if (preferences.driverPanelEnabled.get()) {
            applyPreferences();
            return true;
        }
        return false;
    }

    void raise() {
        if (!preferences.driverPanelEnabled.get()) return;
        applyPreferences();
    }

    void destroy() {
        applyGeneration++;
        dismissAllApps();
        dismissAllFavoritePanels();
        detachPanel();
        mainHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        if (smartHomeValueService != null) {
            smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
            smartHomeValueService = null;
        }
        catalogExecutor.shutdownNow();
    }

    @Override
    public void showAllApps() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::showAllApps);
            return;
        }
        if (drawerWindow != null) {
            dismissAllApps();
            return;
        }
        Display display = defaultDisplay();
        if (display == null) return;
        // Use the rail's successfully attached ECARX system layer. The drawer is added later on
        // the same layer, so it stays above floating navigation/application windows as well as
        // above the rail itself. Portable builds naturally keep TYPE_APPLICATION_OVERLAY here.
        Context context = windowContext(display, attachedType);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return;

        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        int minimumReferenceWidth = DriverPanelLayoutPolicy.referencePanelWidth(
                profile.style == Preferences.DriverPanelStyle.NEW);
        int referenceWidth = Math.max(minimumReferenceWidth,
                Math.min(320, profile.widthPx.get()));
        int physicalWidth = DriverPanelLayoutPolicy.scaleReferenceWidth(
                metrics.widthPixels, referenceWidth);
        int appsGridScalePercent = Math.max(60, Math.min(180,
                preferences.launcherAllAppsIconScalePercent.get()));
        FrameLayout root = new FrameLayout(context);
        root.setClickable(true);
        root.setBackgroundColor(Color.argb(70, 0, 0, 0));
        root.setOnClickListener(view -> dismissAllApps());

        FrameLayout drawer = new FrameLayout(context);
        drawer.setClickable(true);
        // Consume taps inside the drawer; only the uncovered driver-rail side dismisses it.
        drawer.setOnClickListener(view -> { });
        drawer.setBackgroundColor(Color.argb(247, 10, 13, 18));
        int contentPadding = dp(context, 24);
        drawer.setPadding(contentPadding, contentPadding, contentPadding, contentPadding);

        TextView title = new TextView(context);
        title.setText("Все приложения");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, 72, Gravity.TOP | Gravity.START);
        drawer.addView(title, titleParams);

        ImageButton close = new ImageButton(context);
        close.setImageResource(R.drawable.ic_driver_close);
        close.setColorFilter(Color.WHITE);
        close.setBackground(rippleBackground(Color.argb(45, 255, 255, 255), 18));
        close.setContentDescription("Закрыть список приложений");
        close.setOnClickListener(view -> dismissAllApps());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(72, 72,
                Gravity.TOP | Gravity.END);
        drawer.addView(close, closeParams);

        GridView grid = new GridView(context);
        grid.setNumColumns(Math.max(3,
                Math.min(8, preferences.launcherAllAppsColumns.get())));
        int gridGap = Math.max(0, Math.min(40, preferences.launcherAllAppsGapPx.get()));
        grid.setVerticalSpacing(dp(context, gridGap));
        grid.setHorizontalSpacing(dp(context, gridGap));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setPadding(dp(context, 16), dp(context, 16),
                dp(context, 16), dp(context, 16));
        grid.setAdapter(new AppsAdapter(context, Collections.emptyList(),
                preferences, appsGridScalePercent, this::dismissAllApps));
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridParams.topMargin = 84;
        drawer.addView(grid, gridParams);

        // The full-screen modal root receives an outside tap while the actual drawer leaves the
        // driver rail visible. Its child position uses physical display coordinates, independent
        // of ECARX's shifted system-window origin.
        int drawerWidth = Math.max(1, metrics.widthPixels - physicalWidth);
        int drawerLeft = profile.side.get() == 0 ? physicalWidth : 0;
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.LEFT);
        drawerParams.leftMargin = drawerLeft;
        root.addView(drawer, drawerParams);
        WindowManager.LayoutParams params = allAppsOverlayParams(
                attachedType, metrics.widthPixels, metrics.heightPixels,
                "Status Widget all applications");
        try {
            manager.addView(root, params);
            drawerWindow = new AttachedWindow(root, params, manager);
            drawerGrid = grid;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not show all-apps drawer", error);
            Toast.makeText(appContext, "Не удалось открыть список приложений",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        final int generation = ++drawerGeneration;
        catalogExecutor.execute(() -> {
            List<LauncherAppCatalog.App> apps =
                    LauncherAppCatalog.loadVisible(appContext, preferences);
            mainHandler.post(() -> {
                if (drawerGrid == null || drawerWindow == null
                        || generation != drawerGeneration) return;
                drawerGrid.setAdapter(new AppsAdapter(drawerGrid.getContext(), apps,
                        preferences, appsGridScalePercent, this::dismissAllApps));
            });
        });
    }

    @Override
    public void showFavorites(@NonNull String panelId, @Nullable View anchor) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> showFavorites(panelId, anchor));
            return;
        }
        if (favoriteWindows.containsKey(panelId)) {
            manuallyOpenFavorites.remove(panelId);
            dismissFavoritePanel(panelId);
            return;
        }
        dismissAllApps();
        manuallyOpenFavorites.add(panelId);
        showFavoritePanel(panelId, anchor);
    }

    private void showFavoritePanel(@NonNull String panelId, @Nullable View requestedAnchor) {
        DriverFavoritesPanelConfig config =
                new DriverFavoritesPanelStore(preferences).find(panelId);
        if (config == null || favoriteWindows.containsKey(panelId)) return;
        Display display = defaultDisplay();
        if (display == null) return;
        Context context = windowContext(display, attachedType);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        FrameLayout root = new FrameLayout(context);
        boolean panelOnRight = profile.side.get() == 1;
        root.setBackground(favoritePanelBackground(context, profile, panelOnRight));
        root.setClickable(true);
        root.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) return false;
            manuallyOpenFavorites.remove(panelId);
            dismissFavoritePanel(panelId);
            return true;
        });

        int padding = Math.max(6, config.gapPx);
        int desiredWidth = config.columns * config.cellSizePx
                + Math.max(0, config.columns - 1) * config.gapPx + padding * 2;
        int desiredHeight = config.visibleRows * config.cellSizePx
                + Math.max(0, config.visibleRows - 1) * config.gapPx + padding * 2;
        int minimumReferenceWidth = DriverPanelLayoutPolicy.referencePanelWidth(
                profile.style == Preferences.DriverPanelStyle.NEW);
        int referenceWidth = Math.max(minimumReferenceWidth,
                Math.min(320, profile.widthPx.get()));
        int physicalWidth = DriverPanelLayoutPolicy.scaleReferenceWidth(
                metrics.widthPixels, referenceWidth);
        int width = Math.max(1, Math.min(desiredWidth,
                Math.max(1, metrics.widthPixels - physicalWidth)));
        int height = Math.max(1, Math.min(desiredHeight, metrics.heightPixels));

        GridView grid = new GridView(context);
        grid.setNumColumns(config.columns);
        grid.setColumnWidth(config.cellSizePx);
        grid.setVerticalSpacing(config.gapPx);
        grid.setHorizontalSpacing(config.gapPx);
        grid.setStretchMode(GridView.NO_STRETCH);
        grid.setGravity(Gravity.CENTER);
        grid.setPadding(padding, padding, padding, padding);
        grid.setClipToPadding(false);
        List<LauncherShortcutStore.Shortcut> values = visibleFavorites(panelId);
        grid.setAdapter(new ShortcutDrawerAdapter(context, panelId, config, values));
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            View anchor = requestedAnchor == null
                    ? favoritePanelAnchors.get(panelId) : requestedAnchor;
            int anchorCenterY = metrics.heightPixels / 2;
            if (anchor != null && anchor.isAttachedToWindow()) {
                int[] location = new int[2];
                anchor.getLocationOnScreen(location);
                anchorCenterY = location[1] + Math.max(1, anchor.getHeight()) / 2;
            }
            int panelX = DriverPanelLayoutPolicy.panelWindowX(
                    metrics.widthPixels, physicalWidth, panelOnRight);
            // Always grow toward the screen content and meet the driver rail with a zero-pixel gap.
            int x = panelOnRight ? panelX - width : panelX + physicalWidth;
            int y = Math.max(0, Math.min(metrics.heightPixels - height,
                    anchorCenterY - height / 2));
            WindowManager.LayoutParams params = compactDrawerParams(
                    attachedType, width, height, x, y,
                    "Status Widget driver favorites " + panelId);
            manager.addView(root, params);
            Set<String> itemIds = new LinkedHashSet<>();
            for (LauncherShortcutStore.Shortcut value : values) itemIds.add(value.id);
            favoriteWindows.put(panelId, new FavoritePanelWindow(
                    config, grid, new AttachedWindow(root, params, manager), itemIds));
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not show driver favorites", error);
            manuallyOpenFavorites.remove(panelId);
            Toast.makeText(appContext, "Не удалось открыть избранное",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void triggerStockClimate() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::triggerStockClimate);
            return;
        }
        dismissAllApps();
        Display display = defaultDisplay();
        if (display == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        DriverPanelLayoutPolicy.TapTarget target =
                DriverPanelLayoutPolicy.stockClimateTapTarget(
                        metrics.widthPixels, metrics.heightPixels,
                        profile.side.get() == 1,
                        profile.style == Preferences.DriverPanelStyle.NEW);
        int generation = ++proxyTapGeneration;

        // Keep the panel visually stable but remove it from input hit-testing for the duration of
        // the injected gesture. That makes the event land on the covered OEM climate icon.
        setPanelTouchable(false);
        Runnable restore = () -> mainHandler.postDelayed(() -> {
            if (generation != proxyTapGeneration) return;
            setPanelTouchable(true);
        }, 90L);
        mainHandler.postDelayed(() -> {
            if (generation == proxyTapGeneration) setPanelTouchable(true);
        }, PROXY_TAP_WATCHDOG_MS);
        // updateViewLayout() is asynchronous on Android. Wait for one short WindowManager
        // relayout before injecting the gesture, otherwise the still-interactive rail can consume
        // the synthetic tap even though its opaque pixels never left the screen.
        mainHandler.postDelayed(() -> {
            if (generation != proxyTapGeneration) return;
            if (WidgetAccessibilityService.performTap(target.x, target.y, success -> {
                if (generation != proxyTapGeneration) return;
                if (success) restore.run();
                else fallbackStockClimateTap(target, generation);
            })) return;
            fallbackStockClimateTap(target, generation);
        }, PROXY_TAP_SETTLE_MS);
    }

    private void fallbackStockClimateTap(@NonNull DriverPanelLayoutPolicy.TapTarget target,
                                         int generation) {
        // The shared settle delay above already made the opaque rail input-transparent. The shell
        // fallback therefore injects immediately without detaching or visually exposing OEM UI.
        PrivilegedShell.get(appContext).runCommand(
                "input tap " + target.x + " " + target.y, (output, error) ->
                        mainHandler.post(() -> {
                            if (generation != proxyTapGeneration) return;
                            setPanelTouchable(true);
                            if (error != null) {
                                Toast.makeText(appContext,
                                        "Включите спецвозможности для кнопки штатного климата",
                                        Toast.LENGTH_LONG).show();
                            }
                        }));
    }

    private void dismissAllApps() {
        drawerGeneration++;
        AttachedWindow drawer = drawerWindow;
        drawerWindow = null;
        drawerGrid = null;
        if (drawer != null) drawer.remove();
    }

    private void dismissFavoritePanel(@NonNull String panelId) {
        FavoritePanelWindow value = favoriteWindows.remove(panelId);
        if (value == null) return;
        for (String itemId : value.itemIds) drawerSmartHomeBindings.remove(itemId);
        value.window.remove();
    }

    private void dismissAllFavoritePanels() {
        List<String> ids = new ArrayList<>(favoriteWindows.keySet());
        for (String id : ids) dismissFavoritePanel(id);
        manuallyOpenFavorites.clear();
        drawerSmartHomeBindings.clear();
    }

    private void refreshFavoriteWindows() {
        List<String> open = new ArrayList<>(favoriteWindows.keySet());
        for (String id : open) {
            dismissFavoritePanel(id);
            showFavoritePanel(id, favoritePanelAnchors.get(id));
        }
    }

    private void reconcileAutomatedFavoritePanels() {
        WidgetService service = WidgetService.getInstance();
        if (service == null) return;
        for (DriverFavoritesPanelConfig panel :
                new DriverFavoritesPanelStore(preferences).load()) {
            Boolean visible = service.driverFavoritePanelVisibility(panel.id);
            if (Boolean.TRUE.equals(visible)) {
                showFavoritePanel(panel.id, favoritePanelAnchors.get(panel.id));
            } else if (Boolean.FALSE.equals(visible)) {
                manuallyOpenFavorites.remove(panel.id);
                dismissFavoritePanel(panel.id);
            }
        }
    }

    @NonNull
    private List<LauncherShortcutStore.Shortcut> visibleFavorites(
            @NonNull String panelId) {
        List<LauncherShortcutStore.Shortcut> values = new ArrayList<>();
        WidgetService widgetService = WidgetService.getInstance();
        for (LauncherShortcutStore.Shortcut shortcut :
                LauncherShortcutStore.forDriverFavorites(preferences, panelId).all()) {
            boolean scenarioVisible = widgetService == null
                    || widgetService.driverShortcutVisible(shortcut.id, true);
            if (shortcut.enabled && scenarioVisible) values.add(shortcut);
        }
        return values;
    }

    private void attachForType(@NonNull Display display, int type,
                               @NonNull List<LauncherShortcutStore.Shortcut> shortcuts,
                               @NonNull DriverPanelLayoutPolicy.Layout geometry,
                               int screenWidth,
                               int screenHeight,
                               @NonNull Preferences.DriverPanelProfile profile) {
        Context context = windowContext(display, type);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) throw new IllegalStateException("WindowManager unavailable");
        attachSegment(context, manager, type, screenWidth, screenHeight,
                geometry, shortcuts, profile);
    }

    private void attachSegment(@NonNull Context context, @NonNull WindowManager manager,
                               int type, int screenWidth, int screenHeight,
                               @NonNull DriverPanelLayoutPolicy.Layout geometry,
                               @NonNull List<LauncherShortcutStore.Shortcut> shortcuts,
                               @NonNull Preferences.DriverPanelProfile profile) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setBackground(panelBackground(context, profile));
        // The window and its backdrop always cover the complete OEM rail. User top/bottom values
        // constrain only the button content; they must never create transparent stock-panel gaps.
        root.setPadding(0, geometry.contentTop, 0,
                Math.max(0, screenHeight - geometry.contentBottom));
        int gap = Math.max(0, profile.itemGapPx.get());
        List<LauncherShortcutStore.Shortcut> topInformation = new ArrayList<>();
        List<LauncherShortcutStore.Shortcut> bottomInformation = new ArrayList<>();
        List<LauncherShortcutStore.Shortcut> controls = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
            if (shortcut.kind == LauncherShortcutStore.Kind.INFO) {
                (shortcut.informationPlacement == 1
                        ? bottomInformation : topInformation).add(shortcut);
            }
            else controls.add(shortcut);
        }
        int availableHeight = Math.max(1, geometry.contentBottom - geometry.contentTop);
        InformationSection topSection = buildInformationSection(
                context, topInformation, gap);
        InformationSection bottomSection = buildInformationSection(
                context, bottomInformation, gap);
        int desiredInformationHeight = topSection.desiredHeight
                + bottomSection.desiredHeight;
        int informationBudget = desiredInformationHeight <= 0 ? 0
                : controls.isEmpty() ? availableHeight
                : Math.max(dp(context, 64), Math.round(availableHeight * .48f));
        int topHeight;
        int bottomHeight;
        if (desiredInformationHeight <= informationBudget) {
            topHeight = topSection.desiredHeight;
            bottomHeight = bottomSection.desiredHeight;
        } else {
            topHeight = desiredInformationHeight == 0 ? 0
                    : Math.round(informationBudget
                    * (topSection.desiredHeight / (float) desiredInformationHeight));
            bottomHeight = informationBudget - topHeight;
        }
        if (topSection.view != null && topHeight > 0) {
            root.addView(topSection.view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, topHeight));
        }
        LinearLayout controlHost = new LinearLayout(context);
        controlHost.setOrientation(LinearLayout.VERTICAL);
        controlHost.setGravity(Gravity.CENTER);
        for (LauncherShortcutStore.Shortcut shortcut : controls) {
            View button = shortcutButton(context, shortcut, false);
            registerFavoriteAnchors(shortcut, button);
            boolean expandedClimate = isExpandedClimate(shortcut);
            int itemGap = shortcut.gapAfterPx < 0 ? gap : shortcut.gapAfterPx;
            LinearLayout.LayoutParams itemParams;
            if (shortcut.kind == LauncherShortcutStore.Kind.DIVIDER) {
                itemParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        shortcut.dividerThicknessPx + Math.max(4, itemGap));
            } else {
                itemParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0,
                        DriverPanelLayoutPolicy.shortcutWeight(expandedClimate));
            }
            itemParams.setMargins(4, 0, 4, Math.max(0, itemGap));
            controlHost.addView(button, itemParams);
        }
        if (!controls.isEmpty()) {
            root.addView(controlHost, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        if (bottomSection.view != null && bottomHeight > 0) {
            root.addView(bottomSection.view, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, bottomHeight));
        }
        WindowManager.LayoutParams params = segmentParams(
                type, screenHeight, screenWidth, profile);
        manager.addView(root, params);
        Log.d(TAG, "Attached " + profile.style.key + " driver panel type=" + type
                + " screenWidth=" + screenWidth + " width=" + params.width
                + " x=" + params.x + " side=" + profile.side.get());
        panelWindows.add(new AttachedWindow(root, params, manager));
    }

    @NonNull
    private InformationSection buildInformationSection(
            @NonNull Context context,
            @NonNull List<LauncherShortcutStore.Shortcut> information,
            int panelGap) {
        if (information.isEmpty()) return new InformationSection(null, 0);
        LinkedHashMap<String, List<LauncherShortcutStore.Shortcut>> rows =
                new LinkedHashMap<>();
        for (LauncherShortcutStore.Shortcut shortcut : information) {
            String group = shortcut.informationGroup.trim();
            // Empty groups are intentionally unique; every named group is unlimited and may mix
            // status-bar, vehicle, phone and smart-home information sources.
            String key = group.isEmpty() ? "\u0000" + shortcut.id : group;
            rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(shortcut);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(rows.size() > 3);
        LinearLayout host = new LinearLayout(context);
        host.setOrientation(LinearLayout.VERTICAL);
        int contentHeight = 0;
        for (List<LauncherShortcutStore.Shortcut> rowItems : rows.values()) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int rowHeight = 0;
            int rowGap = 0;
            for (LauncherShortcutStore.Shortcut shortcut : rowItems) {
                View tile = shortcutButton(context, shortcut, false);
                rowHeight = Math.max(rowHeight, informationTileHeight(context, shortcut));
                rowGap = Math.max(rowGap, Math.max(0,
                        shortcut.gapAfterPx < 0 ? panelGap : shortcut.gapAfterPx));
                LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                tileParams.setMargins(dp(context, 2), 0, dp(context, 2), 0);
                row.addView(tile, tileParams);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, rowHeight);
            rowParams.bottomMargin = rowGap;
            host.addView(row, rowParams);
            contentHeight += rowHeight + rowGap;
        }
        scroll.addView(host, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new InformationSection(scroll, contentHeight);
    }

    private void registerFavoriteAnchors(
            @NonNull LauncherShortcutStore.Shortcut shortcut, @NonNull View button) {
        if (shortcut.kind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.isDriverFavoritesTarget(shortcut.target)) {
            favoritePanelAnchors.put(
                    LauncherShortcutStore.driverFavoritesPanelId(shortcut.target), button);
        }
        if (shortcut.hasLongAction
                && shortcut.longKind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.isDriverFavoritesTarget(shortcut.longTarget)) {
            favoritePanelAnchors.put(
                    LauncherShortcutStore.driverFavoritesPanelId(shortcut.longTarget), button);
        }
    }

    private static int informationTileHeight(
            @NonNull Context context,
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        int text = Math.round((shortcut.informationValueTextSizeSp
                * (shortcut.informationShowValue ? 1 : 0)
                + (shortcut.showTitle ? shortcut.informationLabelTextSizeSp : 0))
                * scaledDensity);
        int padding = dp(context, shortcut.informationPaddingTopPx
                + shortcut.informationPaddingBottomPx);
        int icon = shortcut.informationIconSizePx + padding;
        return Math.max(dp(context, 40),
                Math.max(icon, text + padding + dp(context, 8)));
    }

    private static final class InformationSection {
        @Nullable final ScrollView view;
        final int desiredHeight;

        InformationSection(@Nullable ScrollView view, int desiredHeight) {
            this.view = view;
            this.desiredHeight = desiredHeight;
        }
    }

    @NonNull
    private View shortcutButton(@NonNull Context context,
                                @NonNull LauncherShortcutStore.Shortcut shortcut,
                                boolean drawer) {
        if (shortcut.kind == LauncherShortcutStore.Kind.INFO) {
            return new InformationShortcutView(context, preferences, shortcut);
        }
        if (shortcut.kind == LauncherShortcutStore.Kind.DIVIDER) {
            FrameLayout holder = new FrameLayout(context);
            View line = InformationShortcutView.divider(context,
                    shortcut.backgroundColor, shortcut.dividerThicknessPx);
            holder.addView(line, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    shortcut.dividerThicknessPx, Gravity.CENTER));
            holder.setClickable(false);
            return holder;
        }
        FrameLayout button = new FrameLayout(context);
        button.setClickable(true);
        button.setFocusable(false);
        button.setClipChildren(false);
        button.setClipToPadding(false);
        button.setContentDescription(shortcut.title);
        int background = safeColor(shortcut.backgroundColor, Color.TRANSPARENT);
        button.setBackground(rippleBackground(background, 14));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int requested = Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                Math.min(LauncherShortcutStore.MAX_ICON_SIZE_PX, shortcut.iconSizePx));
        boolean stockClimate = isStockClimate(shortcut);
        boolean expandedClimate = isExpandedClimate(shortcut);
        View icon;
        @Nullable ImageView stateIcon = null;
        if (stockClimate) {
            icon = new DriverClimateShortcutView(context, CarIntegrations.get(appContext),
                    shortcut.iconColor, shortcut.extendedClimateInfo);
        } else {
            ImageView image = new ImageView(context);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            Drawable resolved;
            if (shortcut.kind == LauncherShortcutStore.Kind.APP
                    && "app".equals(shortcut.icon)) {
                android.content.ComponentName component =
                        android.content.ComponentName.unflattenFromString(shortcut.target);
                resolved = component == null ? null
                        : HighResolutionAppIconLoader.load(context, component);
            } else {
                resolved = LauncherIconResolver.resolve(context, shortcut);
            }
            if (resolved != null) image.setImageDrawable(resolved);
            icon = image;
            stateIcon = image;
        }
        if (stockClimate || opensWindowedYandex(shortcut)) {
            // The destination surface already produces its own audible acknowledgement. Keeping
            // the proxy button silent prevents the intermittent double click heard when ECARX
            // creates a Yandex floating window (and does the same for the stock-climate proxy).
            button.setSoundEffectsEnabled(false);
            content.setSoundEffectsEnabled(false);
            icon.setSoundEffectsEnabled(false);
        }
        content.addView(icon, new LinearLayout.LayoutParams(requested,
                DriverPanelLayoutPolicy.shortcutIconHeight(requested, expandedClimate)));

        if (shortcut.showTitle) {
            TextView label = new TextView(context);
            label.setText(shortcut.title);
            label.setTextColor(safeColor(shortcut.textColor, Color.WHITE));
            label.setTextSize(11);
            label.setSingleLine(true);
            label.setGravity(Gravity.CENTER);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.setMargins(2, 2, 2, 0);
            content.addView(label, labelParams);
        }
        @Nullable TextView stateLabel = null;
        if (shortcut.kind == LauncherShortcutStore.Kind.RULE && shortcut.showState) {
            stateLabel = new TextView(context);
            stateLabel.setText("…");
            stateLabel.setTextSize(10);
            stateLabel.setTextColor(Color.LTGRAY);
            stateLabel.setSingleLine(false);
            stateLabel.setMaxLines(Integer.MAX_VALUE);
            stateLabel.setEllipsize(null);
            stateLabel.setGravity(Gravity.CENTER);
            content.addView(stateLabel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        button.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        WidgetService widgetService = WidgetService.getInstance();
        boolean actionEnabled = widgetService == null
                || widgetService.driverShortcutActionEnabled(shortcut.id, true);
        button.setAlpha(actionEnabled ? 1f : .42f);
        if (actionEnabled) {
            button.setOnClickListener(view -> actions.execute(shortcut, view));
            if (shortcut.hasLongAction) {
                button.setOnLongClickListener(view -> actions.executeLong(shortcut, view));
            }
        } else {
            button.setClickable(false);
        }
        if (shortcut.kind == LauncherShortcutStore.Kind.RULE && stateIcon != null) {
            SmartHomeBinding binding = new SmartHomeBinding(
                    shortcut.copy(), button, stateIcon, stateLabel, actionEnabled);
            (drawer ? drawerSmartHomeBindings : panelSmartHomeBindings)
                    .put(shortcut.id, binding);
            applySmartHomeState(binding);
        }
        return button;
    }

    private static boolean isStockClimate(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return shortcut.kind == LauncherShortcutStore.Kind.BUILTIN
                && LauncherShortcutStore.Builtin.STOCK_CLIMATE.key.equals(shortcut.target);
    }

    private static boolean isExpandedClimate(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        // HA1085 keeps the detailed climate tile at the same height as every other rail button.
        return false;
    }

    private static boolean opensWindowedYandex(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        if (shortcut.kind != LauncherShortcutStore.Kind.BUILTIN) return false;
        return LauncherShortcutStore.Builtin.MAPS_WINDOW.key.equals(shortcut.target)
                || LauncherShortcutStore.Builtin.NAVIGATOR_WINDOW.key.equals(shortcut.target);
    }

    @NonNull
    private GradientDrawable panelBackground(
            @NonNull Context context,
            @NonNull Preferences.DriverPanelProfile profile) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(safeOpaqueColor(profile.backgroundColor.get(),
                Color.rgb(19, 23, 28)));
        float radius = Math.max(dp(context, 20), profile.cornerRadiusPx.get());
        background.setCornerRadii(panelCornerRadii(radius, profile.side.get() == 1));
        return background;
    }

    @NonNull
    private GradientDrawable favoritePanelBackground(
            @NonNull Context context,
            @NonNull Preferences.DriverPanelProfile profile,
            boolean panelOnRight) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(safeOpaqueColor(profile.backgroundColor.get(),
                Color.rgb(19, 23, 28)));
        float radius = Math.max(dp(context, 20), profile.cornerRadiusPx.get());
        // The edge that touches the rail is square, so Favorites reads as a continuation of the
        // same driver panel. Only the outer free edge keeps the user-selected rounding.
        background.setCornerRadii(panelOnRight
                ? new float[]{radius, radius, 0f, 0f, 0f, 0f, radius, radius}
                : new float[]{0f, 0f, radius, radius, radius, radius, 0f, 0f});
        return background;
    }

    @NonNull
    private WindowManager.LayoutParams segmentParams(
            int type, int screenHeight, int screenWidth,
            @NonNull Preferences.DriverPanelProfile profile) {
        int minimumReferenceWidth = DriverPanelLayoutPolicy.referencePanelWidth(
                profile.style == Preferences.DriverPanelStyle.NEW);
        int referenceWidth = Math.max(minimumReferenceWidth,
                Math.min(320, profile.widthPx.get()));
        int width = DriverPanelLayoutPolicy.scaleReferenceWidth(
                screenWidth, referenceWidth);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, Math.max(1, screenHeight), type, flags, PixelFormat.TRANSLUCENT);
        // ECARX lays x=0 out after its stock left rail. MonjaroPanel always anchors TOP|LEFT and
        // crosses that reserved frame with a scaled negative 160/1920 inset.
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = DriverPanelLayoutPolicy.panelWindowX(
                screenWidth, width, profile.side.get() == 1);
        params.y = 0;
        params.setTitle("Status Widget driver panel");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        return params;
    }

    @NonNull
    private static WindowManager.LayoutParams compactDrawerParams(
            int type, int width, int height, int x, int y, @NonNull String title) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                // Without this flag Android routes the outside tap only to the underlying app,
                // so the favorite root never receives ACTION_OUTSIDE and remains stuck open.
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(1, width), Math.max(1, height), type, flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = x;
        params.y = y;
        params.setTitle(title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        return params;
    }

    @NonNull
    private static WindowManager.LayoutParams allAppsOverlayParams(
            int type, int screenWidth, int screenHeight, @NonNull String title) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(1, screenWidth), Math.max(1, screenHeight), type, flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = DriverPanelLayoutPolicy.panelWindowX(
                screenWidth, screenWidth, false);
        params.y = 0;
        params.setTitle(title);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        return params;
    }

    @Nullable
    private Display defaultDisplay() {
        DisplayManager manager = (DisplayManager) appContext.getSystemService(
                Context.DISPLAY_SERVICE);
        return manager == null ? null : manager.getDisplay(DISPLAY_ID);
    }

    @NonNull
    private Context windowContext(@NonNull Display display, int type) {
        Context displayContext = appContext.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return displayContext.createWindowContext(type, null);
            } catch (RuntimeException ignored) {
            }
        }
        return displayContext;
    }

    private void detachPanel() {
        removeWindows(panelWindows);
        panelWindows.clear();
        panelSmartHomeBindings.clear();
    }

    private static void removeWindows(@NonNull List<AttachedWindow> windows) {
        for (int index = windows.size() - 1; index >= 0; index--) {
            windows.get(index).remove();
        }
    }

    @NonNull
    private Map<String, IntentActionRule> loadSmartHomeRules() {
        Map<String, IntentActionRule> result = new HashMap<>();
        try {
            for (IntentActionRule rule : new IntentActionRuleStore(preferences).loadStrict()) {
                result.put(rule.id, rule);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not load smart-home driver shortcuts", error);
        }
        return result;
    }

    private void applySmartHomeValues(@NonNull Collection<ConnectorValue> values) {
        smartHomeValues.clear();
        for (ConnectorValue value : values) {
            smartHomeValues.put(smartHomeKey(value), value);
        }
        applySmartHomeStates();
    }

    private void applySmartHomeChanges(@NonNull Collection<ConnectorValue> values) {
        for (ConnectorValue value : values) {
            smartHomeValues.put(smartHomeKey(value), value);
        }
        applySmartHomeStates();
    }

    private void applySmartHomeStates() {
        List<SmartHomeBinding> bindings =
                new ArrayList<>(panelSmartHomeBindings.values());
        bindings.addAll(drawerSmartHomeBindings.values());
        for (SmartHomeBinding binding : bindings) {
            applySmartHomeState(binding);
        }
    }

    private void applySmartHomeState(@NonNull SmartHomeBinding binding) {
        LauncherShortcutStore.Shortcut shortcut = binding.shortcut;
        IntentActionRule rule = smartHomeRules.get(shortcut.target);
        SprutHubController sprut = SprutHubController.active();
        SourceBinding source = SmartHomeShortcutStateBindingPolicy.resolve(shortcut, rule,
                sprut == null ? null : sprut.catalog());
        ConnectorValue value = source == null ? null : smartHomeValues.get(smartHomeKey(source));
        SmartHomeShortcutStatePolicy.State state =
                SmartHomeShortcutStatePolicy.resolveValue(shortcut, rule, source, value);
        boolean active = state.present && state.fresh && state.available
                && state.activeKnown && state.active;
        int background = safeColor(active
                ? shortcut.activeBackgroundColor : shortcut.backgroundColor,
                Color.TRANSPARENT);
        binding.button.setBackground(rippleBackground(background, 14));
        String tint = active ? shortcut.activeIconColor : shortcut.iconColor;
        LauncherShortcutStore.Shortcut visual = shortcut.copy();
        visual.icon = state.iconKey;
        binding.icon.setImageDrawable(LauncherIconResolver.resolve(appContext, visual, tint));
        float stateAlpha = !state.present ? .62f
                : !state.available ? .42f : !state.fresh ? .68f : 1f;
        binding.button.setAlpha(binding.actionEnabled ? stateAlpha : .42f);
        if (binding.stateLabel != null) {
            binding.stateLabel.setText(state.valueLabel);
            binding.stateLabel.setTextColor(safeColor(tint, Color.LTGRAY));
        }
        binding.button.setContentDescription(shortcut.title + ", " + state.valueLabel);
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

    private void setPanelTouchable(boolean touchable) {
        for (AttachedWindow window : panelWindows) {
            if (touchable) {
                window.params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                window.params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            try {
                window.manager.updateViewLayout(window.view, window.params);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static int safeColor(@Nullable String raw, int fallback) {
        try {
            return Color.parseColor(raw);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static int safeOpaqueColor(@Nullable String raw, int fallback) {
        return safeColor(raw, fallback) | 0xFF000000;
    }

    private static float[] panelCornerRadii(float radius, boolean panelOnRight) {
        float r = Math.max(0f, radius);
        // MonjaroPanel keeps the physical screen edge square and rounds only the inner edge.
        return panelOnRight
                ? new float[]{r, r, 0f, 0f, 0f, 0f, r, r}
                : new float[]{0f, 0f, r, r, r, r, 0f, 0f};
    }

    @NonNull
    private static Drawable rippleBackground(int color, int radius) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(color);
        content.setCornerRadius(radius);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(
                Color.argb(0x33, 255, 255, 255)), content, mask);
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class AttachedWindow {
        final View view;
        final WindowManager.LayoutParams params;
        final WindowManager manager;

        AttachedWindow(View view, WindowManager.LayoutParams params, WindowManager manager) {
            this.view = view;
            this.params = params;
            this.manager = manager;
        }

        void remove() {
            try {
                manager.removeViewImmediate(view);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class AppsAdapter extends BaseAdapter {
        private final Context context;
        private final List<LauncherAppCatalog.App> apps;
        private final Map<String, FavoriteAppConfig> appearances;
        private final int scalePercent;
        private final Runnable close;

        AppsAdapter(Context context, List<LauncherAppCatalog.App> apps,
                    Preferences preferences, int scalePercent, Runnable close) {
            this.context = context;
            this.apps = apps;
            this.appearances = new FavoriteAppsConfigStore(preferences).appearanceSnapshot();
            this.scalePercent = scalePercent;
            this.close = close;
        }

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LauncherAppCatalog.App app = apps.get(position);
            FavoriteAppConfig appearance = appearances.get(app.packageName);
            if (appearance == null) appearance = new FavoriteAppConfig(app.packageName);
            LinearLayout tile = LauncherAppTileRenderer.render(
                    context, convertView, app.label,
                    LauncherAppCatalog.loadIcon(context, app),
                    appearance, scalePercent);
            tile.setContentDescription(app.label);
            tile.setOnClickListener(view -> {
                try {
                    close.run();
                    context.startActivity(LauncherAppCatalog.launchIntent(app));
                } catch (RuntimeException error) {
                    Toast.makeText(context, "Не удалось открыть " + app.label,
                            Toast.LENGTH_SHORT).show();
                }
            });
            tile.setOnLongClickListener(view -> {
                close.run();
                AppUninstallLauncher.request(context, app);
                return true;
            });
            return tile;
        }
    }

    private final class ShortcutDrawerAdapter extends BaseAdapter {
        private final Context context;
        private final String panelId;
        private final DriverFavoritesPanelConfig config;
        private final List<LauncherShortcutStore.Shortcut> values;

        ShortcutDrawerAdapter(@NonNull Context context,
                              @NonNull String panelId,
                              @NonNull DriverFavoritesPanelConfig config,
                              @NonNull List<LauncherShortcutStore.Shortcut> values) {
            this.context = context;
            this.panelId = panelId;
            this.config = config;
            this.values = values;
        }

        @Override public int getCount() { return values.size(); }
        @Override public Object getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            LauncherShortcutStore.Shortcut shortcut = values.get(position);
            View tile = shortcutButton(context, shortcut, true);
            tile.setPadding(dp(context, 8), dp(context, 8),
                    dp(context, 8), dp(context, 8));
            WidgetService widgetService = WidgetService.getInstance();
            boolean enabled = widgetService == null
                    || widgetService.driverShortcutActionEnabled(shortcut.id, true);
            if (enabled && LauncherShortcutStore.isInteractive(shortcut)) {
                tile.setOnClickListener(view -> {
                    actions.execute(shortcut, view);
                    if (shortcut.closeFavoritePanelAfterAction) {
                        manuallyOpenFavorites.remove(panelId);
                        dismissFavoritePanel(panelId);
                    }
                });
                if (shortcut.hasLongAction) {
                    tile.setOnLongClickListener(view -> {
                        boolean handled = actions.executeLong(shortcut, view);
                        if (handled && shortcut.closeFavoritePanelAfterAction) {
                            manuallyOpenFavorites.remove(panelId);
                            dismissFavoritePanel(panelId);
                        }
                        return handled;
                    });
                }
            }
            FrameLayout cell = new FrameLayout(context);
            GradientDrawable outline = new GradientDrawable();
            outline.setColor(Color.TRANSPARENT);
            outline.setCornerRadius(Math.max(8, config.cellSizePx * .12f));
            if (config.borderEnabled && config.borderWidthPx > 0) {
                outline.setStroke(config.borderWidthPx,
                        safeColor(config.borderColor, Color.argb(85, 255, 255, 255)));
            }
            cell.setBackground(outline);
            int inset = config.borderEnabled ? Math.max(0, config.borderWidthPx) : 0;
            cell.setPadding(inset, inset, inset, inset);
            cell.addView(tile, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, config.cellSizePx));
            return cell;
        }
    }

    private static final class FavoritePanelWindow {
        @NonNull final DriverFavoritesPanelConfig config;
        @NonNull final GridView grid;
        @NonNull final AttachedWindow window;
        @NonNull final Set<String> itemIds;

        FavoritePanelWindow(@NonNull DriverFavoritesPanelConfig config,
                            @NonNull GridView grid,
                            @NonNull AttachedWindow window,
                            @NonNull Set<String> itemIds) {
            this.config = config;
            this.grid = grid;
            this.window = window;
            this.itemIds = itemIds;
        }
    }

    private static final class SmartHomeBinding {
        @NonNull final LauncherShortcutStore.Shortcut shortcut;
        @NonNull final FrameLayout button;
        @NonNull final ImageView icon;
        @Nullable final TextView stateLabel;
        final boolean actionEnabled;

        SmartHomeBinding(@NonNull LauncherShortcutStore.Shortcut shortcut,
                         @NonNull FrameLayout button, @NonNull ImageView icon,
                         @Nullable TextView stateLabel, boolean actionEnabled) {
            this.shortcut = shortcut;
            this.button = button;
            this.icon = icon;
            this.stateLabel = stateLabel;
            this.actionEnabled = actionEnabled;
        }
    }
}
