/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.WidgetService;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.DriverFavoriteBlocksStore;
import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.launcher.LauncherAppTileRenderer;
import dezz.status.widget.launcher.LauncherAllAppsSurface;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.panels.PanelGridLayout;
import dezz.status.widget.launcher.SmartHomeShortcutStateBindingPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutStatePolicy;
import dezz.status.widget.launcher.apps.FavoriteAppConfig;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.launcher.information.InformationPanelConfig;
import dezz.status.widget.launcher.information.InformationPanelConfigStore;
import dezz.status.widget.launcher.information.InformationPanelView;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.shell.PrivilegedShell;
import dezz.status.widget.sprut.SprutHubController;

/**
 * Owns the new Monjaro-style driver rail and the overlay all-apps drawer.
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
    private final CarIntegration carIntegration;
    private final Map<String, CarControlState> carControlStates = new HashMap<>();
    private final Map<String, CarBinding> panelCarBindings = new HashMap<>();
    private final Map<String, CarBinding> drawerCarBindings = new HashMap<>();
    private final CarIntegration.ControlStateListener carStateListener = state -> {
        carControlStates.put(state.controlId, state);
        applyCarStates();
    };
    private final Map<String, SmartHomeBinding> panelSmartHomeBindings = new HashMap<>();
    private final Map<String, SmartHomeBinding> drawerSmartHomeBindings = new HashMap<>();
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
    private boolean favoritesDrawerOpen;
    @NonNull private String favoritesBlockId = DriverFavoriteBlocksStore.DEFAULT_BLOCK_ID;
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
        this.carIntegration = CarIntegrations.get(appContext);
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
        Map<String, CarBinding> previousCarBindings =
                new HashMap<>(panelCarBindings);
        panelWindows.clear();
        panelSmartHomeBindings.clear();
        panelCarBindings.clear();
        smartHomeRules = loadSmartHomeRules();
        mainHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        mainHandler.post(ensureSmartHomeValueSubscription);
        if (!preferences.driverPanelEnabled.get()) {
            removeWindows(previousWindows);
            dismissAllApps();
            resubscribeCarControls();
            statusListener.onStatus("stopped", "Панель водителя выключена");
            return;
        }

        Display display = defaultDisplay();
        if (display == null) {
            panelWindows.addAll(previousWindows);
            panelSmartHomeBindings.putAll(previousBindings);
            panelCarBindings.putAll(previousCarBindings);
            resubscribeCarControls();
            refreshFavoritesDrawer();
            statusListener.onStatus("error", "Основной дисплей не найден");
            return;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        LauncherShortcutStore store = LauncherShortcutStore.forDriverPanel(preferences, profile);
        WidgetService widgetService = WidgetService.getInstance();
        List<LauncherShortcutStore.Shortcut> enabled = new ArrayList<>();
        for (LauncherShortcutStore.Shortcut shortcut : store.all()) {
            boolean scenarioVisible = widgetService == null
                    || widgetService.driverShortcutVisible(shortcut.id, true);
            if (shortcut.enabled && scenarioVisible
                    && enabled.size() < DriverPanelLayoutPolicy.MAX_BUTTONS) {
                enabled.add(shortcut);
            }
        }
        DriverPanelLayoutPolicy.Layout geometry = DriverPanelLayoutPolicy.calculate(
                metrics.heightPixels,
                profile.topPaddingPx.get(),
                profile.bottomPaddingPx.get(),
                enabled.size(),
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
                refreshFavoritesDrawer();
                resubscribeCarControls();
                String mode = type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        ? "обычный overlay" : "системный ECARX";
                String pocket = "кнопки используют всю высоту; климат открывается прокси-кнопкой";
                statusListener.onStatus("active",
                        "Новая панель · " + enabled.size() + " кнопок · "
                                + mode + " · " + pocket);
                return;
            } catch (RuntimeException error) {
                failure = error;
                removeWindows(panelWindows);
                panelWindows.clear();
                panelSmartHomeBindings.clear();
                panelCarBindings.clear();
                Log.w(TAG, "Window type " + type + " rejected", error);
            }
        }
        // A rejected refresh must leave the last fully covering panel in place.
        panelWindows.addAll(previousWindows);
        panelSmartHomeBindings.putAll(previousBindings);
        panelCarBindings.putAll(previousCarBindings);
        resubscribeCarControls();
        refreshFavoritesDrawer();
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
        detachPanel();
        mainHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        if (smartHomeValueService != null) {
            smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
            smartHomeValueService = null;
        }
        carIntegration.unsubscribeControlStates(carStateListener);
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

        LauncherAllAppsSurface.Views surface =
                LauncherAllAppsSurface.create(context, preferences);
        FrameLayout root = surface.root;
        GridView grid = surface.grid;
        int appsGridScalePercent = Math.max(60, Math.min(180,
                preferences.launcherAllAppsIconScalePercent.get()));
        surface.close.setOnClickListener(view -> dismissAllApps());
        grid.setAdapter(new AppsAdapter(context, Collections.emptyList(),
                preferences, appsGridScalePercent, this::dismissAllApps));

        WindowManager.LayoutParams params = fullScreenParams(attachedType);
        try {
            manager.addView(root, params);
            drawerWindow = new AttachedWindow(root, params, manager);
            drawerGrid = grid;
            favoritesDrawerOpen = false;
            // Reattach the opaque rail after the full-screen drawer. Both windows use the same
            // accepted ECARX type, so the rail remains visually above it and fully clickable.
            applyPreferences();
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
    public void showFavorites(@NonNull String blockId) {
        showFavoriteBlock(blockId, null);
    }

    private void showFavoriteBlock(@NonNull String blockId, @Nullable View anchor) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(() -> showFavoriteBlock(blockId, anchor));
            return;
        }
        if (drawerWindow != null) {
            boolean same = favoritesDrawerOpen && favoritesBlockId.equals(blockId);
            dismissAllApps();
            if (same) return;
        }
        Display display = defaultDisplay();
        if (display == null) return;
        Context context = windowContext(display, attachedType);
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        Preferences.DriverPanelProfile profile = preferences.activeDriverPanelProfile();
        DriverFavoriteBlocksStore blocks = new DriverFavoriteBlocksStore(preferences);
        DriverFavoriteBlocksStore.Block block = blocks.find(blockId);
        LauncherShortcutStore shortcutStore =
                LauncherShortcutStore.forDriverFavorites(preferences);
        List<LauncherShortcutStore.Shortcut> favorites =
                visibleFavorites(block.id, blocks, shortcutStore);
        int rows = blocks.usedRows(block, favorites);
        int padding = dp(context, 10);
        int cellSize = block.cellSizePx;
        int gap = block.gapPx;
        int popupWidth = padding * 2 + block.columns * cellSize
                + Math.max(0, block.columns - 1) * gap;
        int popupHeight = padding * 2 + rows * cellSize
                + Math.max(0, rows - 1) * gap;
        popupWidth = Math.min(Math.max(dp(context, 96), popupWidth), metrics.widthPixels);
        popupHeight = Math.min(Math.max(dp(context, 80), popupHeight), metrics.heightPixels);
        FrameLayout root = new FrameLayout(context);
        root.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(safeOpaqueColor(profile.backgroundColor.get(),
                Color.rgb(19, 23, 28)));
        background.setCornerRadius(Math.max(dp(context, 18), profile.cornerRadiusPx.get()));
        root.setBackground(background);
        root.setClipToOutline(true);
        root.setContentDescription(block.title);
        root.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismissAllApps();
                return true;
            }
            return false;
        });

        PanelGridLayout grid = new PanelGridLayout(context);
        grid.setGridSize(block.columns, rows);
        grid.setCellGapPx(gap);
        for (LauncherShortcutStore.Shortcut shortcut : favorites) {
            View tile = shortcutButton(context, shortcut, true);
            tile.setPadding(dp(context, 4), dp(context, 4),
                    dp(context, 4), dp(context, 4));
            tile.setOnClickListener(view -> {
                if (DriverFavoriteBlocksStore.isFavoritesTarget(shortcut.target)
                        && shortcut.kind == LauncherShortcutStore.Kind.BUILTIN) {
                    showFavoriteBlock(
                            DriverFavoriteBlocksStore.blockIdFromTarget(shortcut.target),
                            anchor);
                    return;
                }
                dismissAllApps();
                actions.execute(shortcut);
            });
            if (shortcut.hasLongAction) {
                tile.setOnLongClickListener(view -> {
                    dismissAllApps();
                    return actions.executeLong(shortcut);
                });
            }
            grid.addView(tile, new PanelGridLayout.LayoutParams(
                    shortcut.gridColumn, shortcut.gridRow,
                    shortcut.columnSpan, shortcut.rowSpan));
        }
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new FavoriteGridDividersView(context, block, rows, gap),
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        try {
            WindowManager.LayoutParams params = favoriteBlockParams(attachedType,
                    popupWidth, popupHeight, metrics, profile, anchor);
            params.setTitle("Status Widget driver favorite block");
            manager.addView(root, params);
            drawerWindow = new AttachedWindow(root, params, manager);
            drawerGrid = null;
            favoritesDrawerOpen = true;
            favoritesBlockId = block.id;
            resubscribeCarControls();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not show driver favorite block", error);
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
                        profile.side.get() == 1, true);
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
        favoritesDrawerOpen = false;
        favoritesBlockId = DriverFavoriteBlocksStore.DEFAULT_BLOCK_ID;
        drawerSmartHomeBindings.clear();
        drawerCarBindings.clear();
        resubscribeCarControls();
        if (drawer != null) drawer.remove();
    }

    private void refreshFavoritesDrawer() {
        if (!favoritesDrawerOpen || drawerWindow == null) return;
        String blockId = favoritesBlockId;
        dismissAllApps();
        showFavoriteBlock(blockId, null);
    }

    @NonNull
    private List<LauncherShortcutStore.Shortcut> visibleFavorites(
            @NonNull String blockId,
            @NonNull DriverFavoriteBlocksStore blocks,
            @NonNull LauncherShortcutStore store) {
        List<LauncherShortcutStore.Shortcut> values = new ArrayList<>();
        WidgetService widgetService = WidgetService.getInstance();
        for (LauncherShortcutStore.Shortcut shortcut : blocks.items(blockId, store)) {
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
        InformationPanelConfigStore informationStore =
                InformationPanelConfigStore.forDriverPanel(preferences);
        InformationPanelConfig information = DriverInformationTilePolicy.vertical(
                informationStore.load());
        int informationCount = DriverInformationTilePolicy.enabledCount(information);
        if (informationCount > 0) {
            InformationPanelView informationView = new InformationPanelView(
                    context, CarIntegrations.get(appContext), informationStore);
            informationView.setConfig(information);
            informationView.setClickable(false);
            root.addView(informationView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, informationCount));
            informationView.start();
        }
        for (int index = 0; index < shortcuts.size(); index++) {
            LauncherShortcutStore.Shortcut shortcut = shortcuts.get(index);
            if (index > 0 && shortcut.dividerBefore) {
                View divider = new View(context);
                divider.setBackgroundColor(Color.argb(135, 225, 231, 242));
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
                int dividerGap = Math.max(2, shortcutGapBefore(shortcut, gap) / 2);
                dividerParams.setMargins(dp(context, 12), dividerGap,
                        dp(context, 12), dividerGap);
                root.addView(divider, dividerParams);
            }
            View button = shortcutButton(context, shortcut, false);
            boolean detailedClimate = isExpandedClimate(shortcut);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0,
                    DriverPanelLayoutPolicy.shortcutWeight(detailedClimate));
            int gapBefore = index == 0 || shortcut.dividerBefore
                    ? 0 : shortcutGapBefore(shortcut, gap);
            itemParams.setMargins(4, gapBefore, 4, 0);
            root.addView(button, itemParams);
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
    private View shortcutButton(@NonNull Context context,
                                @NonNull LauncherShortcutStore.Shortcut shortcut,
                                boolean drawer) {
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
        if (stockClimate) {
            // Only the OEM climate surface should produce a click sound. The proxy itself stays
            // silent while its synthetic tap reaches the covered stock control.
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
        if ((shortcut.kind == LauncherShortcutStore.Kind.RULE
                || shortcut.kind == LauncherShortcutStore.Kind.CAR)
                && shortcut.showState) {
            stateLabel = new TextView(context);
            stateLabel.setText("…");
            stateLabel.setTextSize(10);
            stateLabel.setTextColor(Color.LTGRAY);
            stateLabel.setSingleLine(false);
            stateLabel.setMaxLines(3);
            stateLabel.setHorizontallyScrolling(false);
            stateLabel.setGravity(Gravity.CENTER);
            stateLabel.setIncludeFontPadding(false);
            stateLabel.setEllipsize(null);
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    stateLabel, 6, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            stateParams.setMargins(2, 0, 2, 2);
            content.addView(stateLabel, stateParams);
        }
        button.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        WidgetService widgetService = WidgetService.getInstance();
        boolean actionEnabled = widgetService == null
                || widgetService.driverShortcutActionEnabled(shortcut.id, true);
        button.setAlpha(actionEnabled ? 1f : .42f);
        if (actionEnabled) {
            if (!drawer && shortcut.kind == LauncherShortcutStore.Kind.BUILTIN
                    && DriverFavoriteBlocksStore.isFavoritesTarget(shortcut.target)) {
                button.setOnClickListener(view -> showFavoriteBlock(
                        DriverFavoriteBlocksStore.blockIdFromTarget(shortcut.target), button));
            } else {
                button.setOnClickListener(view -> actions.execute(shortcut));
            }
            if (shortcut.hasLongAction) {
                button.setOnLongClickListener(view -> actions.executeLong(shortcut));
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
        } else if (shortcut.kind == LauncherShortcutStore.Kind.CAR && stateIcon != null) {
            CarBinding binding = new CarBinding(
                    shortcut.copy(), button, stateIcon, stateLabel);
            (drawer ? drawerCarBindings : panelCarBindings)
                    .put(shortcut.id, binding);
            applyCarState(binding, carControlStates.get(shortcut.target));
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
        return isStockClimate(shortcut) && shortcut.extendedClimateInfo;
    }

    private static int shortcutGapBefore(
            @NonNull LauncherShortcutStore.Shortcut shortcut, int fallback) {
        return shortcut.gapBeforePx >= 0 ? shortcut.gapBeforePx : Math.max(0, fallback);
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
    private WindowManager.LayoutParams segmentParams(
            int type, int screenHeight, int screenWidth,
            @NonNull Preferences.DriverPanelProfile profile) {
        int minimumReferenceWidth = DriverPanelLayoutPolicy.referencePanelWidth(true);
        int referenceWidth = Math.max(minimumReferenceWidth,
                Math.min(320, profile.widthPx.get()));
        int width = DriverPanelLayoutPolicy.scaleReferenceWidth(
                screenWidth, referenceWidth);
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
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
    private static WindowManager.LayoutParams fullScreenParams(int type) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("Status Widget all applications");
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
    private WindowManager.LayoutParams favoriteBlockParams(
            int type, int width, int height,
            @NonNull DisplayMetrics metrics,
            @NonNull Preferences.DriverPanelProfile profile,
            @Nullable View anchor) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width, height, type, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        int minimumReferenceWidth = DriverPanelLayoutPolicy.referencePanelWidth(true);
        int referenceWidth = Math.max(minimumReferenceWidth,
                Math.min(320, profile.widthPx.get()));
        int railWidth = DriverPanelLayoutPolicy.scaleReferenceWidth(
                metrics.widthPixels, referenceWidth);
        int x = profile.side.get() == 1
                ? metrics.widthPixels - railWidth - width : railWidth;
        int y = Math.max(0, (metrics.heightPixels - height) / 2);
        if (anchor != null && anchor.isAttachedToWindow()) {
            int[] location = new int[2];
            anchor.getLocationOnScreen(location);
            x = profile.side.get() == 1
                    ? location[0] - width : location[0] + anchor.getWidth();
            y = location[1] + (anchor.getHeight() - height) / 2;
        }
        params.x = Math.max(0, Math.min(x, Math.max(0, metrics.widthPixels - width)));
        params.y = Math.max(0, Math.min(y, Math.max(0, metrics.heightPixels - height)));
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

    private void resubscribeCarControls() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (CarBinding binding : panelCarBindings.values()) {
            ids.add(binding.shortcut.target);
        }
        for (CarBinding binding : drawerCarBindings.values()) {
            ids.add(binding.shortcut.target);
        }
        if (ids.isEmpty()) {
            carIntegration.unsubscribeControlStates(carStateListener);
        } else {
            carIntegration.subscribeControlStates(ids, carStateListener);
        }
    }

    private void applyCarStates() {
        for (CarBinding binding : panelCarBindings.values()) {
            applyCarState(binding, carControlStates.get(binding.shortcut.target));
        }
        for (CarBinding binding : drawerCarBindings.values()) {
            applyCarState(binding, carControlStates.get(binding.shortcut.target));
        }
    }

    private void applyCarState(@NonNull CarBinding binding,
                               @Nullable CarControlState state) {
        LauncherShortcutStore.Shortcut shortcut = binding.shortcut;
        boolean confirmed = state != null && state.available && state.known;
        boolean active = confirmed && state.active;
        if (confirmed && shortcut.command == CarControlCommand.Operation.SET) {
            active = Math.abs(state.value - shortcut.commandValue) < .01d;
        } else if (confirmed && shortcut.command == CarControlCommand.Operation.CYCLE
                && !shortcut.cycleValues.isEmpty()) {
            active = containsCycleValue(shortcut.cycleValues, state.value);
        }
        int background = safeColor(active
                ? shortcut.activeBackgroundColor : shortcut.backgroundColor,
                Color.TRANSPARENT);
        binding.button.setBackground(rippleBackground(background, 14));
        String tint = active ? shortcut.activeIconColor : shortcut.iconColor;
        if (active && shortcut.useVehicleStateColor
                && state != null && state.suggestedColor != null) {
            tint = state.suggestedColor;
        }
        binding.icon.setImageDrawable(
                LauncherIconResolver.resolve(appContext, shortcut, tint));
        binding.button.setAlpha(state == null ? .62f : state.available ? 1f : .42f);
        if (binding.stateLabel != null) {
            binding.stateLabel.setText(state == null ? "…" : state.valueLabel);
            binding.stateLabel.setTextColor(safeColor(tint, Color.LTGRAY));
        }
        binding.button.setContentDescription(shortcut.title + (state == null
                ? ", состояние неизвестно" : ", " + state.valueLabel));
    }

    private static boolean containsCycleValue(
            @NonNull List<Double> values, double candidate) {
        for (Double value : values) {
            if (value != null && Math.abs(value - candidate) < .01d) return true;
        }
        return false;
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

    /** Draws only user-selected inner group boundaries; it never consumes a pointer event. */
    private static final class FavoriteGridDividersView extends View {
        @NonNull private final DriverFavoriteBlocksStore.Block block;
        private final int rows;
        private final int gapPx;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FavoriteGridDividersView(@NonNull Context context,
                                 @NonNull DriverFavoriteBlocksStore.Block block,
                                 int rows, int gapPx) {
            super(context);
            this.block = block.copy();
            this.rows = Math.max(1, rows);
            this.gapPx = Math.max(0, gapPx);
            paint.setColor(Color.argb(135, 225, 231, 242));
            paint.setStrokeWidth(Math.max(1f,
                    context.getResources().getDisplayMetrics().density));
            setClickable(false);
            setFocusable(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cellWidth = (getWidth() - gapPx * Math.max(0, block.columns - 1))
                    / (float) Math.max(1, block.columns);
            float cellHeight = (getHeight() - gapPx * Math.max(0, rows - 1))
                    / (float) rows;
            for (int column = 0; column < block.columns - 1; column++) {
                if (!block.hasVerticalDividerAfter(column)) continue;
                float x = (column + 1) * cellWidth + column * gapPx + gapPx / 2f;
                canvas.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int row = 0; row < rows - 1; row++) {
                if (!block.hasHorizontalDividerAfter(row)) continue;
                float y = (row + 1) * cellHeight + row * gapPx + gapPx / 2f;
                canvas.drawLine(0, y, getWidth(), y, paint);
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
            return tile;
        }
    }

    private final class ShortcutDrawerAdapter extends BaseAdapter {
        private final Context context;
        private final List<LauncherShortcutStore.Shortcut> values;

        ShortcutDrawerAdapter(@NonNull Context context,
                              @NonNull List<LauncherShortcutStore.Shortcut> values) {
            this.context = context;
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
            if (enabled) {
                tile.setOnClickListener(view -> {
                    dismissAllApps();
                    actions.execute(shortcut);
                });
                if (shortcut.hasLongAction) {
                    tile.setOnLongClickListener(view -> {
                        dismissAllApps();
                        return actions.executeLong(shortcut);
                    });
                }
            }
            return tile;
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

    private static final class CarBinding {
        @NonNull final LauncherShortcutStore.Shortcut shortcut;
        @NonNull final FrameLayout button;
        @NonNull final ImageView icon;
        @Nullable final TextView stateLabel;

        CarBinding(@NonNull LauncherShortcutStore.Shortcut shortcut,
                   @NonNull FrameLayout button, @NonNull ImageView icon,
                   @Nullable TextView stateLabel) {
            this.shortcut = shortcut;
            this.button = button;
            this.icon = icon;
            this.stateLabel = stateLabel;
        }
    }
}
