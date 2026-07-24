/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget;

import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import dezz.status.widget.launcher.CombinedNavigationPanelPolicy;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.LauncherElementFrame;
import dezz.status.widget.launcher.LauncherGridView;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.LauncherSafeAreaPolicy;
import dezz.status.widget.launcher.LauncherSafeAreaResolver;
import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.launcher.SingleFlightRefresh;
import dezz.status.widget.launcher.SmartHomeShortcutStateBindingPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutStatePolicy;
import dezz.status.widget.launcher.YandexWindowLauncher;
import dezz.status.widget.launcher.apps.FavoriteAppConfig;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.climate.ClimatePanelConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelView;
import dezz.status.widget.launcher.media.MediaPanelConfig;
import dezz.status.widget.launcher.media.MediaPanelConfigStore;
import dezz.status.widget.launcher.media.MediaPanelView;
import dezz.status.widget.launcher.navigation.NavigationPanelConfig;
import dezz.status.widget.launcher.navigation.NavigationPanelConfigStore;
import dezz.status.widget.launcher.panels.PanelContentEditOverlay;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;
import dezz.status.widget.launcher.panels.PanelGridLayout;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;
import dezz.status.widget.launcher.routes.FavoriteRoutesPanelView;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfigStore;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelView;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.automation.ScenarioTriggerReceiver;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.sprut.SprutHubController;

/** Full HOME implementation that coexists with the original Status Widget settings activity. */
public final class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    public static final String EXTRA_EDIT_MODE = "dezz.status.widget.extra.EDIT_HOME";
    public static final String EXTRA_EDIT_NAVIGATION_CONTENT =
            "dezz.status.widget.extra.EDIT_NAVIGATION_CONTENT";
    public static final String EXTRA_EDIT_MEDIA_CONTENT =
            "dezz.status.widget.extra.EDIT_MEDIA_CONTENT";
    private static final long NAVIGATION_UI_REFRESH_MS = 30_000L;
    private static final long NAVIGATION_DYNAMIC_REFRESH_MS = 5_000L;
    private static final long SAFE_AREA_REFRESH_MS = 500L;
    private static final long APP_CATALOG_REFRESH_MS = 10L * 60L * 1_000L;
    /** Gives the foreground WidgetService a chance to attach the status row before HOME work. */
    private static final long PANEL_INITIALIZATION_GRACE_MS = 200L;
    /** At most one optional panel is inflated in a display frame. */
    private static final long PANEL_INITIALIZATION_STAGE_MS = 16L;
    private final Map<String, LauncherElementFrame> panels = new HashMap<>();
    private final Handler navigationUiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService launcherWorker = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
            catch (RuntimeException ignored) { }
            runnable.run();
        }, "launcher-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final SingleFlightRefresh navigationRefresh = new SingleFlightRefresh();
    private final Runnable navigationUiRefresh = new Runnable() {
        @Override public void run() {
            updateNavigation();
            scheduleNavigationRefresh();
        }
    };
    private final BroadcastReceiver navigationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateNavigation();
            scheduleNavigationRefresh();
        }
    };

    private Preferences preferences;
    private LauncherLayoutStore layoutStore;
    private PanelElementConfigStore panelElementStore;
    private NavigationPanelConfigStore navigationPanelConfigStore;
    private FavoriteAppsConfigStore favoriteAppsConfigStore;
    private FavoriteRoutesConfigStore favoriteRoutesConfigStore;
    private VehicleInfoPanelConfigStore vehicleInfoConfigStore;
    private FrameLayout workspace;
    private LauncherGridView editorGrid;
    private MaterialButton doneButton;
    private boolean editMode;
    private boolean navigationContentEditMode;
    private boolean mediaContentEditMode;
    private int systemLeftInset;
    private int systemTopInset;
    private int systemRightInset;
    private int systemBottomInset;
    @Nullable private LauncherSafeAreaPolicy.Insets appliedSafeInsets;
    private boolean panelsInitialized;
    private boolean panelsInitializing;
    private boolean panelInitializationAllowed;
    private int panelInitializationStage;
    private boolean navigationReceiverRegistered;
    private boolean navigationDynamicRefresh;
    private boolean navigationLiveContentAvailable;
    @Nullable private View navigationRouteContent;
    @Nullable private PanelGridLayout navigationGrid;
    @Nullable private PanelContentEditOverlay navigationContentEditOverlay;
    @Nullable private NavigationPanelConfig navigationPanelConfig;
    @Nullable private NavigationDataRepository.Snapshot lastNavigationSnapshot;
    private LauncherMediaController mediaController;
    private MediaPanelView mediaPanel;
    @Nullable private android.app.AlertDialog allAppsDialog;
    private TextView navigationArrival;
    private TextView navigationDuration;
    private TextView navigationDistance;
    private ImageView navigationManeuverImage;
    private TextView navigationManeuverDistance;
    private TextView navigationManeuver;
    private TextView navigationTripInfo;
    private LinearLayout navigationCombined;
    private ImageView navigationCombinedImage;
    private TextView navigationCombinedDistance;
    private TextView navigationCombinedManeuver;
    private TextView navigationSpeedLimit;
    private LinearLayout navigationTrafficLights;
    private int navigationTrafficScalePercent = 100;
    private ImageView navigationLanesImage;
    private TextView navigationLaneInfo;
    private ImageView navigationJamImage;
    private ImageView navigationRainbowImage;
    private TextView navigationInactive;
    /** Opens the same Yandex product that supplied the current route; Navigator is the default. */
    private YandexWindowLauncher.Product navigationLaunchProduct =
            YandexWindowLauncher.Product.NAVIGATOR;
    private GridView favoritesGrid;
    private AppCatalog appCatalog;
    private boolean appCatalogLoadInFlight;
    private long lastAppCatalogLoadElapsed;
    private LauncherShortcutStore shortcutStore;
    private GridLayout shortcutGrid;
    private ScrollView shortcutScroll;
    private CarIntegration carIntegration;
    private final Map<String, CarControlState> carControlStates = new HashMap<>();
    private final Map<String, ShortcutTileBinding> carShortcutBindings = new HashMap<>();
    private final Map<String, ShortcutTileBinding> smartHomeShortcutBindings = new HashMap<>();
    private Map<String, IntentActionRule> smartHomeRules = Collections.emptyMap();
    private final Map<String, ConnectorValue> smartHomeValueIndex = new HashMap<>();
    @Nullable private WidgetService smartHomeValueService;
    private final Set<String> pendingCarControls = new LinkedHashSet<>();
    private boolean activityStarted;
    private ClimatePanelView climatePanel;
    private FavoriteRoutesPanelView favoriteRoutesPanel;
    private boolean favoriteRoutesAvailable;
    private VehicleInfoPanelView vehicleInfoPanel;
    @Nullable private String appliedPanelElementsJson;
    @Nullable private String appliedNavigationConfigJson;
    private int appliedAppsColumns = -1;
    private int appliedActionsColumns = -1;
    private int appsGridScalePercent = 100;
    private int actionsTileScalePercent = 100;
    private int actionsAddScalePercent = 100;
    private boolean showActionTiles = true;
    private boolean showActionAdd = true;
    private final CarIntegration.ControlStateListener carStateListener = state -> {
        carControlStates.put(state.controlId, state);
        for (ShortcutTileBinding binding : new ArrayList<>(carShortcutBindings.values())) {
            if (binding.shortcut.target.equals(state.controlId)) applyCarState(binding, state);
        }
    };
    private final ConnectorValueRegistry.Listener smartHomeValueListener = changed -> {
        List<ConnectorValue> copy = new ArrayList<>(changed);
        navigationUiHandler.post(() -> applySmartHomeChanges(copy));
    };
    private final Runnable ensureSmartHomeValueSubscription = new Runnable() {
        @Override public void run() {
            if (!activityStarted || isFinishing() || isDestroyed()) return;
            WidgetService current = WidgetService.getInstance();
            if (current != smartHomeValueService) {
                if (smartHomeValueService != null) {
                    smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
                }
                smartHomeValueService = current;
                if (current != null) {
                    applySmartHomeValues(
                            current.addConnectorValueListener(smartHomeValueListener));
                }
                else applySmartHomeValues(Collections.emptyList());
            }
            navigationUiHandler.postDelayed(this, current == null ? 250L : 2_000L);
        }
    };
    private final Runnable safeAreaRefresh = new Runnable() {
        @Override public void run() {
            if (!activityStarted || isFinishing() || isDestroyed()) return;
            updateLauncherSafeArea();
            navigationUiHandler.postDelayed(this, SAFE_AREA_REFRESH_MS);
        }
    };
    private final Runnable allowPanelInitialization = () -> {
        panelInitializationAllowed = true;
        initializePanels();
    };
    private final Runnable panelInitializationStep = this::continuePanelInitialization;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = new Preferences(this);
        carIntegration = CarIntegrations.get(this);
        layoutStore = new LauncherLayoutStore(preferences);
        panelElementStore = new PanelElementConfigStore(preferences);
        navigationPanelConfigStore = new NavigationPanelConfigStore(preferences);
        favoriteAppsConfigStore = new FavoriteAppsConfigStore(preferences);
        favoriteRoutesConfigStore = new FavoriteRoutesConfigStore(preferences);
        vehicleInfoConfigStore = new VehicleInfoPanelConfigStore(preferences);
        configureWindow();
        View root = buildRoot();
        setContentView(root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            systemLeftInset = Math.max(0, insets.getSystemWindowInsetLeft());
            systemTopInset = Math.max(0, insets.getSystemWindowInsetTop());
            systemRightInset = Math.max(0, insets.getSystemWindowInsetRight());
            systemBottomInset = Math.max(0, insets.getSystemWindowInsetBottom());
            updateLauncherSafeArea();
            return insets;
        });
        root.requestApplyInsets();
        updateLauncherSafeArea();
        workspace.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (panelInitializationAllowed && !panelsInitialized && !panelsInitializing
                    && right > left && bottom > top) {
                initializePanels();
            }
        });
        // HA1048 inflated every new panel before the service could draw the status row. On this
        // shared main Looper that made both HOME and the row look dead. Delay briefly, then spread
        // optional panel inflation across frames.
        navigationUiHandler.postDelayed(allowPanelInitialization,
                PANEL_INITIALIZATION_GRACE_MS);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (panelsInitialized) {
            if (intent.getBooleanExtra(EXTRA_EDIT_MEDIA_CONTENT, false)) {
                setMediaContentEditMode(true);
            } else if (intent.getBooleanExtra(EXTRA_EDIT_NAVIGATION_CONTENT, false)) {
                setNavigationContentEditMode(true);
            } else if (intent.getBooleanExtra(EXTRA_EDIT_MODE, false)) {
                setEditMode(true);
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        if (!panelsInitialized) {
            navigationUiHandler.removeCallbacks(allowPanelInitialization);
            navigationUiHandler.removeCallbacks(panelInitializationStep);
            if (!panelInitializationAllowed) {
                navigationUiHandler.postDelayed(allowPanelInitialization,
                        PANEL_INITIALIZATION_GRACE_MS);
            } else if (panelsInitializing) {
                navigationUiHandler.post(panelInitializationStep);
            } else {
                initializePanels();
            }
        }
        registerNavigationReceiver();
        WidgetServiceStarter.startIfNeeded(this);
        navigationUiHandler.removeCallbacks(safeAreaRefresh);
        navigationUiHandler.post(safeAreaRefresh);
        navigationUiHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        navigationUiHandler.post(ensureSmartHomeValueSubscription);
        reconcileMediaController();
        if (panelsInitialized) refreshFavorites();
        updateNavigation();
        scheduleNavigationRefresh();
        resubscribeCarControls();
        if (climatePanel != null && preferences.launcherClimateVisible.get()
                && hasClimatePanelContent()) {
            climatePanel.start();
        }
        if (vehicleInfoPanel != null && preferences.launcherVehicleInfoVisible.get()) {
            vehicleInfoPanel.start();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        // Never inflate optional panels behind another foreground application.
        navigationUiHandler.removeCallbacks(allowPanelInitialization);
        navigationUiHandler.removeCallbacks(panelInitializationStep);
        navigationUiHandler.removeCallbacks(navigationUiRefresh);
        navigationUiHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        navigationUiHandler.removeCallbacks(safeAreaRefresh);
        dismissAllAppsDialog();
        if (smartHomeValueService != null) {
            smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
            smartHomeValueService = null;
        }
        applySmartHomeValues(Collections.emptyList());
        if (climatePanel != null) climatePanel.stop();
        if (vehicleInfoPanel != null) vehicleInfoPanel.stop();
        if (carIntegration != null) carIntegration.unsubscribeControlStates(carStateListener);
        if (mediaController != null) mediaController.stop();
        releaseNavigationGraphics();
        unregisterNavigationReceiver();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        navigationUiHandler.removeCallbacksAndMessages(null);
        navigationRefresh.cancel();
        launcherWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (appCatalog != null && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            appCatalog.clearIcons();
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            // GridView children retain their Drawable even after the catalog cache is cleared.
            // Drop the off-screen adapter; onStart rebuilds only the small favorite set.
            if (favoritesGrid != null) favoritesGrid.setAdapter(null);
            releaseNavigationGraphics();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preferences.launcherImmersive.get()) {
            applyImmersive();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        getWindow().getDecorView().requestApplyInsets();
        updateLauncherSafeArea();
        applyLauncherPreferences();
        if (appCatalog != null) reloadAppCatalogAsync(false);
        if (shortcutStore != null) {
            shortcutStore.load();
            refreshShortcutGrid();
        }
    }

    private void registerNavigationReceiver() {
        if (navigationReceiverRegistered) return;
        try {
            ContextCompat.registerReceiver(this, navigationReceiver,
                    new IntentFilter(NavigationDataRepository.ACTION_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            navigationReceiverRegistered = true;
        } catch (RuntimeException failure) {
            // A vendor Context implementation must not take down HOME and WidgetService merely
            // because one optional live-update receiver could not be registered.
            navigationReceiverRegistered = false;
            Log.e(TAG, "Could not register navigation refresh receiver", failure);
        }
    }

    private void unregisterNavigationReceiver() {
        if (!navigationReceiverRegistered) return;
        navigationReceiverRegistered = false;
        try { unregisterReceiver(navigationReceiver); }
        catch (RuntimeException failure) {
            Log.w(TAG, "Navigation receiver was already removed", failure);
        }
    }

    private void reconcileMediaController() {
        if (mediaController == null) return;
        boolean needed = activityStarted && preferences.launcherMediaVisible.get()
                && hasMediaPanelContent();
        if (activityStarted && mediaContentEditMode && hasMediaPanelContent()) needed = true;
        if (needed) mediaController.start(); else mediaController.stop();
    }

    private void reloadAppCatalogAsync(boolean force) {
        if (appCatalogLoadInFlight || launcherWorker.isShutdown()) return;
        if (!preferences.launcherAppsVisible.get() && !editMode) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastAppCatalogLoadElapsed < APP_CATALOG_REFRESH_MS) return;
        appCatalogLoadInFlight = true;
        try {
            launcherWorker.execute(() -> {
                AppCatalog loaded = new AppCatalog(getApplicationContext());
                RuntimeException error = null;
                try { loaded.reload(); }
                catch (RuntimeException failure) { error = failure; }
                RuntimeException finalError = error;
                navigationUiHandler.post(() -> {
                    appCatalogLoadInFlight = false;
                    if (isDestroyed() || isFinishing()) return;
                    if (!activityStarted) {
                        loaded.clearIcons();
                        return;
                    }
                    if (finalError != null) {
                        Log.e(TAG, "Application catalog refresh failed", finalError);
                        return;
                    }
                    appCatalog = loaded;
                    lastAppCatalogLoadElapsed = SystemClock.elapsedRealtime();
                    refreshFavorites();
                });
            });
        } catch (RejectedExecutionException failure) {
            appCatalogLoadInFlight = false;
        }
    }

    private void applyLauncherPreferences() {
        if (!panelsInitialized) return;
        View root = (View) workspace.getParent();
        if (root != null) root.setBackground(buildBackground());
        setPanelVisibility(LauncherLayoutStore.APPS, preferences.launcherAppsVisible.get()
                && hasSimplePanelContent(LauncherLayoutStore.APPS));
        if (preferences.launcherAppsVisible.get() && appCatalog != null
                && appCatalog.isEmpty()) reloadAppCatalogAsync(true);
        setPanelVisibility(LauncherLayoutStore.MEDIA, mediaContentEditMode
                || (preferences.launcherMediaVisible.get() && hasMediaPanelContent()));
        reconcileMediaController();
        setPanelVisibility(LauncherLayoutStore.CLOCK, preferences.launcherClockVisible.get()
                && hasSimplePanelContent(LauncherLayoutStore.CLOCK));
        if (favoriteRoutesPanel != null) {
            favoriteRoutesPanel.setColumns(Math.max(1, Math.min(6,
                    preferences.launcherFavoriteRoutesColumns.get())));
            favoriteRoutesPanel.reloadConfig();
            favoriteRoutesAvailable = favoriteRoutesPanel.hasEnabledRoutes();
        }
        updateCombinedNavigationFrameVisibility();
        setPanelVisibility(LauncherLayoutStore.ACTIONS, preferences.launcherActionsVisible.get()
                && hasSimplePanelContent(LauncherLayoutStore.ACTIONS));
        boolean climateVisible = preferences.launcherClimateVisible.get()
                && hasClimatePanelContent();
        setPanelVisibility(LauncherLayoutStore.CLIMATE, climateVisible);
        if (climatePanel != null) {
            climatePanel.reloadConfig();
            if (activityStarted && climateVisible) climatePanel.start();
            else climatePanel.stop();
        }
        if (mediaPanel != null) mediaPanel.reloadConfig();
        boolean vehicleInfoVisible = preferences.launcherVehicleInfoVisible.get();
        setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO, vehicleInfoVisible);
        if (vehicleInfoPanel != null) {
            vehicleInfoPanel.reloadConfig();
            if (activityStarted && vehicleInfoVisible) vehicleInfoPanel.start();
            else vehicleInfoPanel.stop();
        }

        layoutStore.load(workspace.getWidth(), workspace.getHeight());
        applyStoredPanelGeometry();
        refreshSimplePanelContentsIfNeeded();
        updateNavigation();
        scheduleNavigationRefresh();
    }

    /** Applies inner-element edits after returning from the visual panel editor. */
    private void refreshSimplePanelContentsIfNeeded() {
        String raw = preferences.launcherPanelElementsJson.get();
        String navigationRaw = preferences.launcherNavigationConfigJson.get();
        int appsColumns = preferences.launcherAppsColumns.get();
        int actionsColumns = preferences.launcherActionsColumns.get();
        boolean simpleChanged = !Objects.equals(appliedPanelElementsJson, raw)
                || appliedAppsColumns != appsColumns
                || appliedActionsColumns != actionsColumns;
        boolean navigationChanged = !Objects.equals(appliedNavigationConfigJson, navigationRaw);
        if (!simpleChanged && !navigationChanged) return;
        if (simpleChanged) {
            appliedPanelElementsJson = raw;
            appliedAppsColumns = appsColumns;
            appliedActionsColumns = actionsColumns;
            replacePanelContent(LauncherLayoutStore.APPS, buildAppsPanel());
            replacePanelContent(LauncherLayoutStore.CLOCK, buildClockPanel());
            replacePanelContent(LauncherLayoutStore.ACTIONS, buildActionsPanel());
        }
        if (navigationChanged) {
            appliedNavigationConfigJson = navigationRaw;
            replacePanelContent(LauncherLayoutStore.NAVIGATION,
                    buildCombinedNavigationPanel());
        }
        refreshFavorites();
        updateNavigation();
        scheduleNavigationRefresh();
        refreshShortcutGrid();
    }

    private void replacePanelContent(@NonNull String id, @NonNull View content) {
        LauncherElementFrame frame = panels.get(id);
        if (frame != null) frame.setContent(content);
    }

    private void setPanelVisibility(@NonNull String id, boolean visible) {
        LauncherElementFrame frame = panels.get(id);
        if (frame != null) frame.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private boolean hasSimplePanelContent(@NonNull String id) {
        return !panelElementStore.load(id).enabled().isEmpty();
    }

    private boolean hasNavigationPanelContent() {
        return navigationPanelConfigStore.load().hasEnabledElements();
    }

    private boolean hasMediaPanelContent() {
        for (MediaPanelConfig.Element element :
                new MediaPanelConfigStore(preferences).load().orderedElements()) {
            if (element.enabled) return true;
        }
        return false;
    }

    private boolean hasClimatePanelContent() {
        ClimatePanelConfig config = new ClimatePanelConfigStore(preferences).load();
        // The heading is decoration, not useful panel content. When every control is disabled,
        // hide the outer frame as well instead of leaving an empty rectangle on HOME.
        return config.hasEnabledElements();
    }

    @Override
    public void onBackPressed() {
        if (mediaContentEditMode) {
            setMediaContentEditMode(false);
        } else if (navigationContentEditMode) {
            setNavigationContentEditMode(false);
        } else if (editMode) {
            setEditMode(false);
        } else {
            super.onBackPressed();
        }
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        if (preferences.launcherImmersive.get()) applyImmersive();
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @NonNull
    private View buildRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(buildBackground());

        editorGrid = new LauncherGridView(this);
        editorGrid.setStepPx(preferences.launcherSnapPx.get());
        editorGrid.setVisibility(View.GONE);
        root.addView(editorGrid, match());

        workspace = new FrameLayout(this);
        workspace.setClipChildren(false);
        workspace.setLongClickable(true);
        workspace.setOnLongClickListener(v -> {
            if (!navigationContentEditMode && !mediaContentEditMode) setEditMode(true);
            return true;
        });
        root.addView(workspace, match());

        doneButton = new MaterialButton(this);
        doneButton.setText("Готово · закрепить компоновку");
        doneButton.setOnClickListener(v -> finishActiveEditor());
        doneButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(dp(420), dp(56),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        doneLp.topMargin = dp(12);
        root.addView(doneButton, doneLp);
        return root;
    }

    /** Keeps HOME content inside every live app-owned and system-owned safe edge. */
    private void updateLauncherSafeArea() {
        if (preferences == null || workspace == null || editorGrid == null
                || doneButton == null) return;
        LauncherSafeAreaPolicy.Insets safe = LauncherSafeAreaResolver.resolveInsets(
                preferences, systemLeftInset, systemTopInset,
                systemRightInset, systemBottomInset,
                getWindowManager().getDefaultDisplay().getDisplayId());
        if (safe.equals(appliedSafeInsets)) return;
        appliedSafeInsets = safe;
        applySafeMargins(workspace, safe);
        applySafeMargins(editorGrid, safe);
        FrameLayout.LayoutParams doneParams =
                (FrameLayout.LayoutParams) doneButton.getLayoutParams();
        doneParams.leftMargin = safe.left;
        doneParams.topMargin = safe.top + dp(12);
        doneParams.rightMargin = safe.right;
        doneParams.bottomMargin = safe.bottom;
        doneButton.setLayoutParams(doneParams);
        if (panelsInitialized) workspace.post(this::reflowPanelsInsideSafeArea);
    }

    private void applySafeMargins(@NonNull View view,
                                  @NonNull LauncherSafeAreaPolicy.Insets safe) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
        params.leftMargin = safe.left;
        params.topMargin = safe.top;
        params.rightMargin = safe.right;
        params.bottomMargin = safe.bottom;
        view.setLayoutParams(params);
    }

    private void reflowPanelsInsideSafeArea() {
        if (!panelsInitialized || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        layoutStore.load(workspace.getWidth(), workspace.getHeight());
        applyStoredPanelGeometry();
    }

    private void applyStoredPanelGeometry() {
        for (Map.Entry<String, LauncherElementFrame> entry : panels.entrySet()) {
            LauncherLayoutStore.Geometry geometry = layoutStore.get(entry.getKey());
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) entry.getValue().getLayoutParams();
            params.width = geometry.width;
            params.height = geometry.height;
            params.leftMargin = geometry.x;
            params.topMargin = geometry.y;
            entry.getValue().setLayoutParams(params);
        }
    }

    private void finishActiveEditor() {
        if (mediaContentEditMode) {
            setMediaContentEditMode(false);
        } else if (navigationContentEditMode) {
            setNavigationContentEditMode(false);
        } else {
            setEditMode(false);
        }
    }

    private Drawable buildBackground() {
        int base;
        try { base = Color.parseColor(preferences.launcherBackgroundColor.get()); }
        catch (IllegalArgumentException ignored) { base = Color.rgb(16, 24, 39); }
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{base, blend(base, Color.rgb(22, 77, 110), .38f), Color.BLACK});
        drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return drawable;
    }

    private void initializePanels() {
        if (!panelInitializationAllowed || panelsInitialized || panelsInitializing
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        panelsInitializing = true;
        try {
            layoutStore.load(workspace.getWidth(), workspace.getHeight());
            migrateLegacyNavigationPanel();
        } catch (RuntimeException failure) {
            panelsInitializing = false;
            Log.e(TAG, "HOME geometry could not be loaded; retrying", failure);
            navigationUiHandler.postDelayed(this::initializePanels, 500L);
            return;
        }
        // Build the first HOME frame with an empty catalog. PackageManager queries and icon loads
        // run on a worker, then atomically swap the completed catalog onto the UI thread.
        appCatalog = new AppCatalog(getApplicationContext());
        shortcutStore = new LauncherShortcutStore(preferences);
        panelInitializationStage = 0;
        continuePanelInitialization();
    }

    private void continuePanelInitialization() {
        if (!panelsInitializing || panelsInitialized || isFinishing() || isDestroyed()) return;
        try {
            switch (panelInitializationStage) {
                case 0:
                    addPanelSafely(LauncherLayoutStore.APPS, "Приложения", this::buildAppsPanel,
                            () -> preferences.launcherAppsVisible.get()
                                    && hasSimplePanelContent(LauncherLayoutStore.APPS));
                    break;
                case 1:
                    addPanelSafely(LauncherLayoutStore.MEDIA, "Медиа", this::buildMediaPanel,
                            () -> preferences.launcherMediaVisible.get()
                                    && hasMediaPanelContent());
                    makePanelTransparent(LauncherLayoutStore.MEDIA);
                    break;
                case 2:
                    addPanelSafely(LauncherLayoutStore.CLOCK, "Часы", this::buildClockPanel,
                            () -> preferences.launcherClockVisible.get()
                                    && hasSimplePanelContent(LauncherLayoutStore.CLOCK));
                    break;
                case 3:
                    addPanelSafely(LauncherLayoutStore.NAVIGATION, "Маршрут и избранное",
                            this::buildCombinedNavigationPanel,
                            this::isCombinedNavigationFrameVisible);
                    break;
                case 4:
                    addPanelSafely(LauncherLayoutStore.ACTIONS, "Действия",
                            this::buildActionsPanel,
                            () -> preferences.launcherActionsVisible.get()
                                    && hasSimplePanelContent(LauncherLayoutStore.ACTIONS));
                    break;
                case 5:
                    addPanelSafely(LauncherLayoutStore.CLIMATE, "Климат",
                            this::buildClimatePanel,
                            () -> preferences.launcherClimateVisible.get()
                                    && hasClimatePanelContent());
                    makePanelTransparent(LauncherLayoutStore.CLIMATE);
                    break;
                case 6:
                    addPanelSafely(LauncherLayoutStore.VEHICLE_INFO, "Данные автомобиля",
                            this::buildVehicleInfoPanel,
                            preferences.launcherVehicleInfoVisible::get);
                    makePanelTransparent(LauncherLayoutStore.VEHICLE_INFO);
                    LauncherElementFrame vehicleFrame = panels.get(
                            LauncherLayoutStore.VEHICLE_INFO);
                    if (vehicleFrame != null) {
                        vehicleFrame.setVisibility(preferences.launcherVehicleInfoVisible.get()
                                && vehicleInfoPanel != null
                                && vehicleInfoPanel.hasDisplayableSample()
                                ? View.VISIBLE : View.GONE);
                    }
                    break;
                default:
                    finishPanelInitialization();
                    return;
            }
        } catch (RuntimeException | LinkageError failure) {
            // addPanelSafely already isolates normal panel failures. This outer guard also covers
            // optional post-build styling supplied by vendor libraries.
            Log.e(TAG, "HOME panel stage " + panelInitializationStage + " failed", failure);
        }
        panelInitializationStage++;
        navigationUiHandler.postDelayed(panelInitializationStep,
                PANEL_INITIALIZATION_STAGE_MS);
    }

    private void makePanelTransparent(@NonNull String id) {
        LauncherElementFrame frame = panels.get(id);
        if (frame == null) return;
        frame.setCardBackgroundColor(Color.TRANSPARENT);
        frame.setCardElevation(0);
    }

    private void finishPanelInitialization() {

        mediaController = new LauncherMediaController(this, this::updateMedia);
        panelsInitialized = true;
        panelsInitializing = false;
        // initializePanels() is posted from onCreate. If the activity was stopped before that
        // callback runs, starting here would leave a MediaSession listener alive off-screen.
        // onStart() will start it normally when HOME becomes active again.
        reconcileMediaController();
        if (preferences.launcherAppsVisible.get()) reloadAppCatalogAsync(true);
        refreshFavorites();
        updateNavigation();
        scheduleNavigationRefresh();
        if (activityStarted && preferences.launcherClimateVisible.get()
                && hasClimatePanelContent() && climatePanel != null) climatePanel.start();
        if (activityStarted && preferences.launcherVehicleInfoVisible.get()) {
            if (vehicleInfoPanel != null) vehicleInfoPanel.start();
        }
        appliedPanelElementsJson = preferences.launcherPanelElementsJson.get();
        appliedNavigationConfigJson = preferences.launcherNavigationConfigJson.get();
        appliedAppsColumns = preferences.launcherAppsColumns.get();
        appliedActionsColumns = preferences.launcherActionsColumns.get();
        if (getIntent().getBooleanExtra(EXTRA_EDIT_MEDIA_CONTENT, false)) {
            setMediaContentEditMode(true);
        } else if (getIntent().getBooleanExtra(EXTRA_EDIT_NAVIGATION_CONTENT, false)) {
            setNavigationContentEditMode(true);
        } else if (getIntent().getBooleanExtra(EXTRA_EDIT_MODE, false)) {
            setEditMode(true);
        }
    }

    @NonNull
    private View buildFavoriteRoutesPanel() {
        favoriteRoutesPanel = new FavoriteRoutesPanelView(this, favoriteRoutesConfigStore,
                Math.max(1, Math.min(6, preferences.launcherFavoriteRoutesColumns.get())));
        favoriteRoutesAvailable = favoriteRoutesPanel.hasEnabledRoutes();
        return favoriteRoutesPanel;
    }

    /**
     * One physical HOME panel owns both states: favorite destinations while idle and the
     * user-selected navigation data while a real route is active.
     */
    @NonNull
    private View buildCombinedNavigationPanel() {
        FrameLayout host = new FrameLayout(this);
        navigationRouteContent = buildNavigationPanel();
        host.addView(navigationRouteContent, new FrameLayout.LayoutParams(
                matchWidth(), ViewGroup.LayoutParams.MATCH_PARENT));
        View favorites = buildFavoriteRoutesPanel();
        host.addView(favorites, new FrameLayout.LayoutParams(
                matchWidth(), ViewGroup.LayoutParams.MATCH_PARENT));
        return host;
    }

    /** Preserves the old favorite-only rectangle when upgrading from two independent panels. */
    private void migrateLegacyNavigationPanel() {
        if (preferences.launcherCombinedNavigationMigrated.get()) return;
        boolean navigationEnabled = preferences.launcherNavigationVisible.get();
        boolean favoritesEnabled = preferences.launcherFavoriteRoutesVisible.get();
        if (CombinedNavigationPanelPolicy.shouldUseLegacyFavoriteGeometry(false,
                navigationEnabled, favoritesEnabled)) {
            layoutStore.put(LauncherLayoutStore.NAVIGATION,
                    layoutStore.get(LauncherLayoutStore.FAVORITE_ROUTES));
        }
        boolean combinedEnabled = CombinedNavigationPanelPolicy.isEnabled(
                navigationEnabled, favoritesEnabled);
        if (navigationEnabled != combinedEnabled) {
            preferences.launcherNavigationVisible.set(combinedEnabled);
        }
        if (favoritesEnabled != combinedEnabled) {
            preferences.launcherFavoriteRoutesVisible.set(combinedEnabled);
        }
        preferences.launcherCombinedNavigationMigrated.set(true);
    }

    private boolean isCombinedNavigationEnabled() {
        return CombinedNavigationPanelPolicy.isEnabled(
                preferences.launcherNavigationVisible.get(),
                preferences.launcherFavoriteRoutesVisible.get());
    }

    private boolean isCombinedNavigationFrameVisible() {
        return isCombinedNavigationEnabled()
                && (hasNavigationPanelContent()
                || favoriteRoutesAvailable);
    }

    private void updateCombinedNavigationFrameVisibility() {
        setPanelVisibility(LauncherLayoutStore.NAVIGATION,
                navigationContentEditMode || isCombinedNavigationFrameVisible());
    }

    @NonNull
    private View buildVehicleInfoPanel() {
        vehicleInfoPanel = new VehicleInfoPanelView(this, carIntegration,
                vehicleInfoConfigStore);
        vehicleInfoPanel.setContentVisibilityListener(contentVisible ->
                setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO,
                        preferences.launcherVehicleInfoVisible.get()
                                && (editMode || contentVisible)));
        return vehicleInfoPanel;
    }

    private void addPanel(@NonNull String id, @NonNull String label, @NonNull View content,
                          boolean visible) {
        LauncherElementFrame frame = new LauncherElementFrame(this, id, label,
                (changedId, x, y, width, height) -> layoutStore.put(changedId,
                        new LauncherLayoutStore.Geometry(x, y, width, height)));
        frame.setContent(content);
        LauncherLayoutStore.Geometry g = layoutStore.get(id);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(g.width, g.height);
        lp.leftMargin = g.x;
        lp.topMargin = g.y;
        workspace.addView(frame, lp);
        frame.setVisibility(visible ? View.VISIBLE : View.GONE);
        panels.put(id, frame);
    }

    /** One optional integration must never crash HOME and the status service in the same process. */
    private void addPanelSafely(@NonNull String id, @NonNull String label,
            @NonNull Supplier<View> content, @NonNull BooleanSupplier visible) {
        try {
            addPanel(id, label, content.get(), visible.getAsBoolean());
        } catch (RuntimeException | LinkageError failure) {
            Log.e(TAG, "Could not build HOME panel " + id, failure);
            if (panels.containsKey(id)) return;
            TextView diagnostic = text(16f, Color.LTGRAY, false);
            diagnostic.setGravity(Gravity.CENTER);
            diagnostic.setText(label + " временно недоступен");
            try {
                addPanel(id, label, diagnostic, editMode);
            } catch (RuntimeException fallbackFailure) {
                Log.e(TAG, "Could not add fallback HOME panel " + id, fallbackFailure);
            }
        }
    }

    @NonNull
    private View buildAppsPanel() {
        PanelElementConfigStore.Panel config = panelElementStore.load(LauncherLayoutStore.APPS);
        LinearLayout root = verticalContainer();
        favoritesGrid = null;
        appsGridScalePercent = config.scale(PanelElementConfigStore.APPS_GRID);
        for (PanelElementConfigStore.Element element : config.enabled()) {
            if (PanelElementConfigStore.APPS_HEADING.equals(element.id)) {
                TextView heading = heading("Избранное");
                heading.setTextSize(18f * element.scalePercent / 100f);
                heading.setOnClickListener(v -> showAllApps());
                int height = Math.max(dp(34), dp(42) * element.scalePercent / 100);
                root.addView(heading, new LinearLayout.LayoutParams(matchWidth(), height));
            } else if (PanelElementConfigStore.APPS_GRID.equals(element.id)) {
                favoritesGrid = new GridView(this);
                favoritesGrid.setNumColumns(Math.max(1, Math.min(6,
                        preferences.launcherAppsColumns.get())));
                favoritesGrid.setVerticalSpacing(dp(4));
                favoritesGrid.setHorizontalSpacing(dp(4));
                favoritesGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
                favoritesGrid.setSelector(new ColorDrawable(Color.TRANSPARENT));
                favoritesGrid.setOnItemClickListener((parent, view, position, id) ->
                        launchApp((AppEntry) parent.getItemAtPosition(position)));
                favoritesGrid.setOnItemLongClickListener((parent, view, position, id) -> {
                    AppEntry entry = (AppEntry) parent.getItemAtPosition(position);
                    appCatalog.toggleFavorite(entry.packageName);
                    refreshFavorites();
                    return true;
                });
                root.addView(favoritesGrid,
                        new LinearLayout.LayoutParams(matchWidth(), 0, 1f));
            }
        }
        return root;
    }

    @NonNull
    private View buildMediaPanel() {
        mediaPanel = new MediaPanelView(this, new MediaPanelConfigStore(preferences),
                new MediaPanelView.Controls() {
                    @Override public void previous() {
                        if (mediaController != null) mediaController.previous();
                    }
                    @Override public void playPause() {
                        if (mediaController != null) mediaController.playPause();
                    }
                    @Override public void next() {
                        if (mediaController != null) mediaController.next();
                    }
                });
        return mediaPanel;
    }

    @NonNull
    private View buildClockPanel() {
        PanelElementConfigStore.Panel config = panelElementStore.load(LauncherLayoutStore.CLOCK);
        LinearLayout root = verticalContainer();
        root.setGravity(Gravity.CENTER);
        for (PanelElementConfigStore.Element element : config.enabled()) {
            TextClock value = new TextClock(this);
            if (PanelElementConfigStore.CLOCK_TIME.equals(element.id)) {
                value.setFormat24Hour("HH:mm");
                value.setFormat12Hour("h:mm");
                value.setTextColor(Color.WHITE);
                value.setTextSize(50f * element.scalePercent / 100f);
            } else if (PanelElementConfigStore.CLOCK_DATE.equals(element.id)) {
                value.setFormat24Hour("EEE, d MMMM");
                value.setFormat12Hour("EEE, d MMMM");
                value.setTextColor(Color.LTGRAY);
                value.setTextSize(17f * element.scalePercent / 100f);
            } else {
                continue;
            }
            value.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
            root.addView(value);
        }
        return root;
    }

    @NonNull
    private View buildNavigationPanel() {
        NavigationPanelConfig config = navigationPanelConfigStore.load();
        navigationPanelConfig = config;
        FrameLayout host = new FrameLayout(this);
        navigationGrid = new PanelGridLayout(this);
        navigationGrid.setGridSize(config.gridColumns, config.gridRows);
        navigationGrid.setCellGapPx(dp(4));
        host.addView(navigationGrid, match());
        navigationArrival = null;
        navigationDuration = null;
        navigationDistance = null;
        navigationManeuverImage = null;
        navigationManeuverDistance = null;
        navigationManeuver = null;
        navigationTripInfo = null;
        navigationCombined = null;
        navigationCombinedImage = null;
        navigationCombinedDistance = null;
        navigationCombinedManeuver = null;
        navigationSpeedLimit = null;
        navigationTrafficLights = null;
        navigationLanesImage = null;
        navigationLaneInfo = null;
        navigationJamImage = null;
        navigationRainbowImage = null;
        navigationInactive = null;
        navigationLiveContentAvailable = false;
        for (NavigationPanelConfig.Element element : config.enabledElements()) {
            TextView value = null;
            View content;
            if (NavigationPanelConfig.ARRIVAL.equals(element.id)) {
                navigationArrival = value = text(24f * element.scalePercent / 100f,
                        Color.WHITE, true);
                content = value;
            } else if (NavigationPanelConfig.DURATION.equals(element.id)) {
                navigationDuration = value = text(18f * element.scalePercent / 100f,
                        Color.LTGRAY, false);
                content = value;
            } else if (NavigationPanelConfig.DISTANCE.equals(element.id)) {
                navigationDistance = value = text(18f * element.scalePercent / 100f,
                        Color.LTGRAY, false);
                content = value;
            } else if (NavigationPanelConfig.MANEUVER_IMAGE.equals(element.id)) {
                navigationManeuverImage = navigationImage(element.scalePercent, 76);
                content = navigationManeuverImage;
            } else if (NavigationPanelConfig.MANEUVER_DISTANCE.equals(element.id)) {
                navigationManeuverDistance = value = text(25f * element.scalePercent / 100f,
                        Color.WHITE, true);
                content = value;
            } else if (NavigationPanelConfig.MANEUVER.equals(element.id)) {
                navigationManeuver = value = text(17f * element.scalePercent / 100f,
                        Color.WHITE, false);
                content = value;
            } else if (NavigationPanelConfig.TRIP_INFO.equals(element.id)) {
                navigationTripInfo = value = text(16f * element.scalePercent / 100f,
                        Color.LTGRAY, false);
                content = value;
            } else if (NavigationPanelConfig.COMBINED.equals(element.id)) {
                navigationCombined = buildNavigationCombined(element.scalePercent);
                content = navigationCombined;
            } else if (NavigationPanelConfig.SPEED_LIMIT.equals(element.id)) {
                navigationSpeedLimit = value = text(18f * element.scalePercent / 100f,
                        Color.rgb(255, 210, 90), true);
                content = value;
            } else if (NavigationPanelConfig.TRAFFIC_LIGHT.equals(element.id)) {
                navigationTrafficScalePercent = element.scalePercent;
                navigationTrafficLights = new LinearLayout(this);
                navigationTrafficLights.setOrientation(LinearLayout.VERTICAL);
                navigationTrafficLights.setGravity(Gravity.CENTER_VERTICAL);
                content = navigationTrafficLights;
            } else if (NavigationPanelConfig.LANES_IMAGE.equals(element.id)) {
                navigationLanesImage = navigationImage(element.scalePercent, 82);
                content = navigationLanesImage;
            } else if (NavigationPanelConfig.LANE_INFO.equals(element.id)) {
                navigationLaneInfo = value = text(16f * element.scalePercent / 100f,
                        Color.WHITE, false);
                content = value;
            } else if (NavigationPanelConfig.JAM_PROGRESS.equals(element.id)) {
                navigationJamImage = navigationImage(element.scalePercent, 58);
                content = navigationJamImage;
            } else if (NavigationPanelConfig.RAINBOW_IMAGE.equals(element.id)) {
                navigationRainbowImage = navigationImage(element.scalePercent, 58);
                content = navigationRainbowImage;
            } else if (NavigationPanelConfig.INACTIVE.equals(element.id)) {
                navigationInactive = value = text(16f * element.scalePercent / 100f,
                        Color.GRAY, false);
                content = value;
            } else {
                continue;
            }
            if (value != null) value.setGravity(Gravity.CENTER);
            if (!NavigationPanelConfig.INACTIVE.equals(element.id)) {
                navigationLiveContentAvailable = true;
            }
            addNavigationGridElement(element, content);
        }
        navigationContentEditOverlay = new PanelContentEditOverlay(this);
        navigationContentEditOverlay.setModel(new PanelContentEditOverlay.Model() {
            @Override public int columns() { return config.gridColumns; }
            @Override public int rows() { return config.gridRows; }
            @NonNull @Override public List<PanelContentEditOverlay.Item> items() {
                List<PanelContentEditOverlay.Item> result = new ArrayList<>();
                for (NavigationPanelConfig.Element element : config.enabledElements()) {
                    NavigationPanelConfig.Spec spec = NavigationPanelConfig.spec(element.id);
                    result.add(new PanelContentEditOverlay.Item(element.id,
                            spec == null ? element.id : spec.label,
                            element.column, element.row,
                            element.columnSpan, element.rowSpan));
                }
                return result;
            }
            @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                                  int columnSpan, int rowSpan) {
                return config.setPlacement(id, column, row, columnSpan, rowSpan);
            }
        }, (id, finished) -> {
            applyNavigationGridPlacements();
            navigationPanelConfigStore.save(config);
            appliedNavigationConfigJson = preferences.launcherNavigationConfigJson.get();
        });
        host.addView(navigationContentEditOverlay, match());
        navigationContentEditOverlay.setEditing(navigationContentEditMode);
        host.setOnClickListener(v -> {
            if (!navigationContentEditMode) {
                launchYandex(navigationLaunchProduct, false);
            }
        });
        return host;
    }

    private void addNavigationGridElement(@NonNull NavigationPanelConfig.Element element,
                                          @NonNull View content) {
        PanelGridLayout grid = navigationGrid;
        if (grid == null) return;
        FrameLayout cell = new FrameLayout(this);
        cell.setTag(element.id);
        cell.setPadding(dp(4), dp(2), dp(4), dp(2));
        ViewGroup.LayoutParams existing = content.getLayoutParams();
        FrameLayout.LayoutParams contentParams = existing instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams) existing : match();
        cell.addView(content, contentParams);
        grid.addView(cell, new PanelGridLayout.LayoutParams(
                element.column, element.row, element.columnSpan, element.rowSpan));
    }

    private void applyNavigationGridPlacements() {
        NavigationPanelConfig config = navigationPanelConfig;
        PanelGridLayout grid = navigationGrid;
        if (config == null || grid == null) return;
        grid.setGridSize(config.gridColumns, config.gridRows);
        for (NavigationPanelConfig.Element element : config.enabledElements()) {
            grid.updatePlacement(element.id, element.column, element.row,
                    element.columnSpan, element.rowSpan);
        }
        if (navigationContentEditOverlay != null) navigationContentEditOverlay.invalidate();
    }

    @NonNull
    private ImageView navigationImage(int scalePercent, int baseHeightDp) {
        ImageView value = new ImageView(this);
        value.setAdjustViewBounds(true);
        value.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        value.setVisibility(View.GONE);
        int height = dp(Math.max(28, baseHeightDp * scalePercent / 100));
        value.setLayoutParams(new FrameLayout.LayoutParams(matchWidth(), height, Gravity.CENTER));
        return value;
    }

    @NonNull
    private LinearLayout buildNavigationCombined(int scalePercent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setVisibility(View.GONE);
        navigationCombinedImage = new ImageView(this);
        navigationCombinedImage.setAdjustViewBounds(true);
        navigationCombinedImage.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int imageSize = dp(Math.max(42, 68 * scalePercent / 100));
        card.addView(navigationCombinedImage, new LinearLayout.LayoutParams(imageSize, imageSize));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(8), 0, 0, 0);
        navigationCombinedDistance = text(24f * scalePercent / 100f, Color.WHITE, true);
        navigationCombinedManeuver = text(16f * scalePercent / 100f, Color.LTGRAY, false);
        labels.addView(navigationCombinedDistance);
        labels.addView(navigationCombinedManeuver);
        card.addView(labels, new LinearLayout.LayoutParams(0, wrapContent(), 1f));
        return card;
    }

    @NonNull
    private View buildActionsPanel() {
        PanelElementConfigStore.Panel config = panelElementStore.load(LauncherLayoutStore.ACTIONS);
        showActionTiles = config.isEnabled(PanelElementConfigStore.ACTION_TILES);
        showActionAdd = config.isEnabled(PanelElementConfigStore.ACTION_ADD);
        actionsTileScalePercent = config.scale(PanelElementConfigStore.ACTION_TILES);
        actionsAddScalePercent = config.scale(PanelElementConfigStore.ACTION_ADD);
        shortcutScroll = new ScrollView(this);
        shortcutScroll.setFillViewport(true);
        shortcutScroll.setVerticalScrollBarEnabled(false);
        shortcutGrid = new GridLayout(this);
        shortcutGrid.setPadding(dp(7), dp(7), dp(7), dp(7));
        shortcutScroll.addView(shortcutGrid, new ScrollView.LayoutParams(
                matchWidth(), wrapContent()));
        shortcutGrid.post(this::refreshShortcutGrid);
        return shortcutScroll;
    }

    @NonNull
    private View buildClimatePanel() {
        climatePanel = new ClimatePanelView(this, carIntegration,
                new ClimatePanelConfigStore(preferences));
        return climatePanel;
    }

    private void refreshShortcutGrid() {
        if (shortcutGrid == null || shortcutStore == null) return;
        shortcutGrid.removeAllViews();
        carShortcutBindings.clear();
        smartHomeShortcutBindings.clear();
        smartHomeRules = loadSmartHomeRules();
        int columns = Math.max(1, Math.min(6, preferences.launcherActionsColumns.get()));
        List<ShortcutPlacement> placements = new ArrayList<>();
        List<boolean[]> occupied = new ArrayList<>();
        if (showActionTiles) {
            for (LauncherShortcutStore.Shortcut shortcut : shortcutStore.all()) {
                if (!shortcut.enabled) continue;
                placements.add(place(shortcut, columns, occupied));
            }
        }
        if (showActionAdd) {
            LauncherShortcutStore.Shortcut add = new LauncherShortcutStore.Shortcut();
            add.title = "Добавить";
            add.icon = "apps";
            add.backgroundColor = "#553A465B";
            placements.add(place(add, columns, occupied));
        }

        int rows = Math.max(1, occupied.size());
        shortcutGrid.setColumnCount(columns);
        shortcutGrid.setRowCount(rows);
        int availableWidth = shortcutGrid.getWidth();
        if (availableWidth <= dp(20) && shortcutScroll != null) {
            availableWidth = shortcutScroll.getWidth();
        }
        int cellWidth = Math.max(dp(72), (Math.max(dp(72), availableWidth) - dp(14)) / columns);
        int groupScale = showActionTiles ? actionsTileScalePercent : actionsAddScalePercent;
        int cellHeight = Math.max(dp(56), cellWidth * groupScale / 100);
        shortcutGrid.setMinimumHeight(rows * cellHeight + dp(14));
        for (int index = 0; index < placements.size(); index++) {
            ShortcutPlacement placement = placements.get(index);
            boolean addButton = showActionAdd && index == placements.size() - 1;
            View tile = buildShortcutTile(placement.shortcut, addButton);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cellWidth * placement.columnSpan - dp(8);
            lp.height = cellHeight * placement.rowSpan - dp(8);
            lp.columnSpec = GridLayout.spec(placement.column, placement.columnSpan);
            lp.rowSpec = GridLayout.spec(placement.row, placement.rowSpan);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            shortcutGrid.addView(tile, lp);
        }
        resubscribeCarControls();
        applySmartHomeStates();
    }

    @NonNull
    private Map<String, IntentActionRule> loadSmartHomeRules() {
        try {
            Map<String, IntentActionRule> result = new HashMap<>();
            for (IntentActionRule rule : new IntentActionRuleStore(preferences).loadStrict()) {
                result.put(rule.id, rule);
            }
            return result;
        } catch (IllegalArgumentException invalid) {
            Log.w(TAG, "Could not load smart-home rules for live HOME state", invalid);
            return Collections.emptyMap();
        }
    }

    @NonNull
    private ShortcutPlacement place(@NonNull LauncherShortcutStore.Shortcut shortcut, int columns,
                                    @NonNull List<boolean[]> occupied) {
        int width = Math.max(1, Math.min(columns, shortcut.columnSpan));
        int height = Math.max(1, Math.min(4, shortcut.rowSpan));
        for (int row = 0; ; row++) {
            while (occupied.size() < row + height) occupied.add(new boolean[columns]);
            for (int column = 0; column <= columns - width; column++) {
                boolean fits = true;
                for (int y = row; y < row + height && fits; y++) {
                    for (int x = column; x < column + width; x++) {
                        if (occupied.get(y)[x]) { fits = false; break; }
                    }
                }
                if (!fits) continue;
                for (int y = row; y < row + height; y++) {
                    for (int x = column; x < column + width; x++) occupied.get(y)[x] = true;
                }
                return new ShortcutPlacement(shortcut, row, column, width, height);
            }
        }
    }

    @NonNull
    private View buildShortcutTile(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                   boolean addButton) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setFocusable(true);
        try { card.setCardBackgroundColor(Color.parseColor(shortcut.backgroundColor)); }
        catch (IllegalArgumentException ignored) { card.setCardBackgroundColor(Color.argb(180, 34, 39, 51)); }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(6), dp(6), dp(6), dp(6));
        ImageView icon = new ImageView(this);
        if (addButton) {
            icon.setImageResource(R.drawable.ic_add);
            icon.setColorFilter(Color.WHITE);
        } else {
            icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        }
        int contentScale = addButton ? actionsAddScalePercent : actionsTileScalePercent;
        int iconSize = Math.max(24, shortcut.iconSizePx) * contentScale / 100;
        content.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        if (shortcut.showTitle || addButton) {
            TextView label = text(12f * contentScale / 100f, Color.WHITE, true);
            try { label.setTextColor(Color.parseColor(shortcut.textColor)); }
            catch (IllegalArgumentException ignored) { label.setTextColor(Color.WHITE); }
            label.setGravity(Gravity.CENTER);
            label.setText(addButton ? "+  Добавить" : shortcut.title);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(matchWidth(), wrapContent());
            labelLp.topMargin = dp(4);
            content.addView(label, labelLp);
        }
        TextView stateLabel = null;
        if (!addButton && (shortcut.kind == LauncherShortcutStore.Kind.CAR
                || shortcut.kind == LauncherShortcutStore.Kind.RULE)
                && shortcut.showState) {
            stateLabel = text(11, Color.LTGRAY, true);
            stateLabel.setGravity(Gravity.CENTER);
            stateLabel.setText("…");
            stateLabel.setMaxLines(1);
            stateLabel.setPadding(dp(5), 0, dp(5), 0);
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(Color.argb(150, 0, 0, 0));
            badge.setCornerRadius(dp(9));
            stateLabel.setBackground(badge);
        }
        card.addView(content, new MaterialCardView.LayoutParams(matchWidth(), matchHeight()));
        if (stateLabel != null) {
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    wrapContent(), dp(22), Gravity.TOP | Gravity.END);
            badgeLp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.addView(stateLabel, badgeLp);
        }
        if (addButton) {
            card.setOnClickListener(v -> startActivity(new Intent(this,
                    LauncherShortcutSettingsActivity.class)
                    .putExtra(LauncherShortcutSettingsActivity.EXTRA_ADD_NEW, true)));
        } else {
            card.setOnClickListener(v -> executeShortcut(shortcut));
            card.setOnLongClickListener(v -> {
                if (shortcut.hasLongAction) {
                    LauncherShortcutStore.Shortcut action = shortcut.copy();
                    action.kind = shortcut.longKind;
                    action.target = shortcut.longTarget;
                    action.packageName = shortcut.longPackageName;
                    action.command = shortcut.longCommand;
                    action.commandValue = shortcut.longCommandValue;
                    executeShortcut(action);
                } else {
                    startActivity(new Intent(this, LauncherShortcutSettingsActivity.class));
                }
                return true;
            });
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                ShortcutTileBinding binding = new ShortcutTileBinding(shortcut.copy(), card,
                        icon, stateLabel);
                carShortcutBindings.put(shortcut.id, binding);
                applyCarState(binding, carControlStates.get(shortcut.target));
            } else if (shortcut.kind == LauncherShortcutStore.Kind.RULE) {
                ShortcutTileBinding binding = new ShortcutTileBinding(shortcut.copy(), card,
                        icon, stateLabel);
                smartHomeShortcutBindings.put(shortcut.id, binding);
                applySmartHomeState(binding);
            }
        }
        return card;
    }

    private void executeShortcut(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        try {
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                if (!pendingCarControls.add(shortcut.target)) return;
                CarControlCommand command = new CarControlCommand(shortcut.target,
                        shortcut.command, shortcut.commandValue);
                carIntegration.executeControl(command, (success, message) -> {
                    pendingCarControls.remove(shortcut.target);
                    if (!success) {
                        Toast.makeText(this, message == null ? "Команда не выполнена" : message,
                                Toast.LENGTH_LONG).show();
                    }
                });
                return;
            }
            if (shortcut.kind == LauncherShortcutStore.Kind.APP) {
                ComponentName component = ComponentName.unflattenFromString(shortcut.target);
                if (component == null) throw new IllegalArgumentException("component");
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                return;
            }
            if (shortcut.kind == LauncherShortcutStore.Kind.INTENT) {
                Intent command = new Intent(shortcut.target);
                if (!shortcut.packageName.isEmpty()) command.setPackage(shortcut.packageName);
                sendBroadcast(command);
                Toast.makeText(this, "Intent отправлен", Toast.LENGTH_SHORT).show();
                return;
            }
            if (shortcut.kind == LauncherShortcutStore.Kind.RULE) {
                executeSavedRule(shortcut.target);
                return;
            }
            executeBuiltin(LauncherShortcutStore.Builtin.fromKey(shortcut.target));
        } catch (RuntimeException error) {
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                pendingCarControls.remove(shortcut.target);
            }
            Toast.makeText(this, "Действие не выполнено: " + shortcut.title,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void resubscribeCarControls() {
        if (!activityStarted || carIntegration == null || shortcutStore == null) return;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (LauncherShortcutStore.Shortcut shortcut : shortcutStore.all()) {
            if (shortcut.enabled && shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                ids.add(shortcut.target);
            }
        }
        if (ids.isEmpty()) {
            carIntegration.unsubscribeControlStates(carStateListener);
        } else {
            carIntegration.subscribeControlStates(ids, carStateListener);
        }
    }

    private void applyCarState(@NonNull ShortcutTileBinding binding,
                               @Nullable CarControlState state) {
        LauncherShortcutStore.Shortcut shortcut = binding.shortcut;
        boolean confirmed = state != null && state.available && state.known;
        boolean active = confirmed && state.active;
        if (confirmed && shortcut.command == CarControlCommand.Operation.SET) {
            active = Math.abs(state.value - shortcut.commandValue) < .01d;
        }
        String background = active ? shortcut.activeBackgroundColor : shortcut.backgroundColor;
        try { binding.card.setCardBackgroundColor(Color.parseColor(background)); }
        catch (IllegalArgumentException ignored) {
            binding.card.setCardBackgroundColor(Color.argb(180, 34, 39, 51));
        }
        String tint = active ? shortcut.activeIconColor : shortcut.iconColor;
        if (active && shortcut.useVehicleStateColor && state.suggestedColor != null) {
            tint = state.suggestedColor;
        }
        binding.icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut, tint));
        binding.card.setAlpha(state == null ? .62f : state.available ? 1f : .42f);
        if (binding.stateLabel != null) {
            binding.stateLabel.setText(state == null ? "…" : state.valueLabel);
            try { binding.stateLabel.setTextColor(Color.parseColor(tint)); }
            catch (IllegalArgumentException ignored) { binding.stateLabel.setTextColor(Color.LTGRAY); }
        }
        binding.card.setContentDescription(shortcut.title + (state == null
                ? ", состояние неизвестно" : ", " + state.valueLabel));
    }

    private void applySmartHomeValues(@NonNull Collection<ConnectorValue> values) {
        smartHomeValueIndex.clear();
        for (ConnectorValue value : values) {
            smartHomeValueIndex.put(smartHomeValueKey(value), value);
        }
        applySmartHomeStates();
    }

    private void applySmartHomeChanges(@NonNull Collection<ConnectorValue> changed) {
        if (!activityStarted) return;
        for (ConnectorValue value : changed) {
            // Registry removals are reported as stale values. Retaining that last-known value is
            // more truthful than flashing an active state and is replaced on the next snapshot.
            smartHomeValueIndex.put(smartHomeValueKey(value), value);
        }
        applySmartHomeStates();
    }

    private void applySmartHomeStates() {
        for (ShortcutTileBinding binding :
                new ArrayList<>(smartHomeShortcutBindings.values())) {
            applySmartHomeState(binding);
        }
    }

    private void applySmartHomeState(@NonNull ShortcutTileBinding binding) {
        LauncherShortcutStore.Shortcut shortcut = binding.shortcut;
        IntentActionRule rule = smartHomeRules.get(shortcut.target);
        SprutHubController sprut = SprutHubController.active();
        SourceBinding source = SmartHomeShortcutStateBindingPolicy.resolve(shortcut, rule,
                sprut == null ? null : sprut.catalog());
        ConnectorValue value = source == null ? null
                : smartHomeValueIndex.get(smartHomeValueKey(source));
        SmartHomeShortcutStatePolicy.State state =
                SmartHomeShortcutStatePolicy.resolveValue(shortcut, rule, source, value);
        boolean active = state.present && state.fresh && state.available
                && state.activeKnown && state.active;
        String background = active
                ? shortcut.activeBackgroundColor : shortcut.backgroundColor;
        try { binding.card.setCardBackgroundColor(Color.parseColor(background)); }
        catch (IllegalArgumentException ignored) {
            binding.card.setCardBackgroundColor(Color.argb(180, 34, 39, 51));
        }
        String tint = active ? shortcut.activeIconColor : shortcut.iconColor;
        LauncherShortcutStore.Shortcut visual = shortcut.copy();
        visual.icon = state.iconKey;
        binding.icon.setImageDrawable(LauncherIconResolver.resolve(this, visual, tint));
        binding.card.setAlpha(!state.present ? .62f
                : !state.available ? .42f : !state.fresh ? .68f : 1f);
        if (binding.stateLabel != null) {
            binding.stateLabel.setText(state.valueLabel);
            try { binding.stateLabel.setTextColor(Color.parseColor(tint)); }
            catch (IllegalArgumentException ignored) {
                binding.stateLabel.setTextColor(Color.LTGRAY);
            }
        }
        binding.card.setContentDescription(shortcut.title + ", " + state.valueLabel);
        // State is presentation only. Never disable or replace the card listener: even an
        // unavailable device may recover exactly when the user retries its action.
        binding.card.setClickable(true);
    }

    @NonNull
    private static String smartHomeValueKey(@NonNull ConnectorValue value) {
        return value.connectorType.jsonName() + '\u0000' + value.connectorId + '\u0000'
                + value.resourceId;
    }

    @NonNull
    private static String smartHomeValueKey(@NonNull SourceBinding binding) {
        return binding.connectorType.jsonName() + '\u0000' + binding.connectorId + '\u0000'
                + binding.resourceId;
    }

    private void executeSavedRule(@NonNull String ruleId) {
        List<IntentActionRule> rules = new IntentActionRuleStore(preferences).loadStrict();
        for (IntentActionRule rule : rules) {
            if (!rule.enabled || !rule.id.equals(ruleId)) continue;
            Intent trigger = new Intent(this, ScenarioTriggerReceiver.class)
                    .setAction(ScenarioTriggerReceiver.ACTION_TRIGGER)
                    .putExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_ID, rule.id)
                    .putExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_TOKEN, rule.triggerToken);
            sendBroadcast(trigger);
            return;
        }
        throw new IllegalArgumentException("Saved action is missing");
    }

    private void executeBuiltin(@NonNull LauncherShortcutStore.Builtin action) {
        switch (action) {
            case MAPS_WINDOW: launchYandex(YandexWindowLauncher.Product.MAPS, false); break;
            case MAPS_FULL: launchYandex(YandexWindowLauncher.Product.MAPS, true); break;
            case NAVIGATOR_WINDOW: launchYandex(YandexWindowLauncher.Product.NAVIGATOR, false); break;
            case NAVIGATOR_FULL: launchYandex(YandexWindowLauncher.Product.NAVIGATOR, true); break;
            case MEDIA_PLAY_PAUSE: mediaController.playPause(); break;
            case MEDIA_PREVIOUS: mediaController.previous(); break;
            case MEDIA_NEXT: mediaController.next(); break;
            case EDIT_HOME: setEditMode(true); break;
            case HOME_SETTINGS: startActivity(new Intent(this, LauncherSettingsActivity.class)); break;
            case WIDGET_SETTINGS: startActivity(new Intent(this, MainActivity.class)); break;
            case POPUP_SETTINGS: startActivity(new Intent(this, PopupSettingsActivity.class)); break;
            case AUTOMATION_SETTINGS: startActivity(new Intent(this, AutomationSettingsActivity.class)); break;
            case SCENARIOS: startActivity(new Intent(this, ScenarioSettingsActivity.class)); break;
            case INTENT_SCENARIOS: startActivity(new Intent(this, IntentScenarioSettingsActivity.class)); break;
            case NOTIFICATION_ACCESS:
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                break;
            case ALL_APPS:
            default: showAllApps(); break;
        }
    }

    private void setEditMode(boolean enabled) {
        if (enabled && navigationContentEditMode) setNavigationContentEditMode(false);
        if (enabled && mediaContentEditMode) setMediaContentEditMode(false);
        editMode = enabled;
        int snap = Math.max(4, preferences.launcherSnapPx.get());
        editorGrid.setStepPx(snap);
        editorGrid.setVisibility(enabled && preferences.launcherShowGrid.get()
                ? View.VISIBLE : View.GONE);
        doneButton.setText("Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || navigationContentEditMode || mediaContentEditMode
                ? View.VISIBLE : View.GONE);
        for (LauncherElementFrame frame : panels.values()) frame.setEditMode(enabled, snap);
        updateLauncherSafeArea();
        updateNavigation();
        if (vehicleInfoPanel != null && preferences.launcherVehicleInfoVisible.get()) {
            setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO,
                    enabled || vehicleInfoPanel.hasDisplayableSample());
        }
        Toast.makeText(this, enabled
                ? "Тащите панель; размер меняется за любой из четырёх углов"
                : "Компоновка сохранена", Toast.LENGTH_SHORT).show();
    }

    private void setNavigationContentEditMode(boolean enabled) {
        if (enabled && editMode) setEditMode(false);
        if (enabled && mediaContentEditMode) setMediaContentEditMode(false);
        navigationContentEditMode = enabled;
        if (navigationContentEditOverlay != null) {
            navigationContentEditOverlay.setEditing(enabled);
        }
        editorGrid.setVisibility(View.GONE);
        doneButton.setText(enabled
                ? "Готово · сохранить элементы навигации"
                : "Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || editMode || mediaContentEditMode
                ? View.VISIBLE : View.GONE);
        updateLauncherSafeArea();
        if (enabled) {
            setPanelVisibility(LauncherLayoutStore.NAVIGATION, true);
            if (favoriteRoutesPanel != null) favoriteRoutesPanel.setVisibility(View.GONE);
            if (navigationRouteContent != null) {
                navigationRouteContent.setVisibility(View.VISIBLE);
            }
        }
        NavigationDataRepository.Snapshot snapshot = lastNavigationSnapshot;
        if (snapshot != null) renderNavigation(snapshot); else updateNavigation();
        if (!enabled) updateCombinedNavigationFrameVisibility();
        Toast.makeText(this, enabled
                ? "Тащите элементы; потяните выделенный правый нижний угол для размера"
                : "Сетка навигации сохранена", Toast.LENGTH_SHORT).show();
    }

    /**
     * Edits the actual rendered media grid while the outer HOME frame stays fixed. Keeping this
     * separate from {@link #setEditMode(boolean)} is essential: LauncherElementFrame deliberately
     * intercepts every touch while moving/resizing an outer panel.
     */
    private void setMediaContentEditMode(boolean enabled) {
        if (enabled && editMode) setEditMode(false);
        if (enabled && navigationContentEditMode) setNavigationContentEditMode(false);
        mediaContentEditMode = enabled;
        if (mediaPanel != null) {
            if (enabled) mediaPanel.reloadConfig();
            mediaPanel.setInPlaceEditMode(enabled);
        }
        editorGrid.setVisibility(View.GONE);
        doneButton.setText(enabled
                ? "Готово · сохранить элементы медиапанели"
                : "Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || editMode || navigationContentEditMode
                ? View.VISIBLE : View.GONE);
        if (enabled) {
            setPanelVisibility(LauncherLayoutStore.MEDIA, true);
        } else {
            setPanelVisibility(LauncherLayoutStore.MEDIA,
                    preferences.launcherMediaVisible.get() && hasMediaPanelContent());
        }
        reconcileMediaController();
        updateLauncherSafeArea();
        Toast.makeText(this, enabled
                ? "Тащите элементы; правый нижний угол изменяет размер"
                : "Сетка медиапанели сохранена", Toast.LENGTH_SHORT).show();
    }

    private void updateMedia(@NonNull LauncherMediaController.Snapshot state) {
        if (mediaPanel != null) mediaPanel.setSnapshot(state);
    }

    private void updateNavigation() {
        // HA1048 regressed by doing this read before posted panel initialization. The repository
        // parses several JSON values and may decode four navigation PNGs; doing so on the shared
        // main Looper can freeze both HOME and the status row. Coalesce bursts on a worker.
        if (!panelsInitialized || isFinishing() || isDestroyed()
                || (!isCombinedNavigationEnabled() && !editMode
                && !navigationContentEditMode)) return;
        if (!navigationRefresh.request()) return;
        submitNavigationRead();
    }

    private void submitNavigationRead() {
        try {
            launcherWorker.execute(() -> {
                NavigationDataRepository.Snapshot state = null;
                RuntimeException error = null;
                try { state = NavigationDataRepository.read(getApplicationContext()); }
                catch (RuntimeException failure) { error = failure; }
                NavigationDataRepository.Snapshot completedState = state;
                RuntimeException completedError = error;
                navigationUiHandler.post(() -> {
                    boolean runAgain = navigationRefresh.complete();
                    if (activityStarted && !isDestroyed() && !isFinishing()
                            && panelsInitialized) {
                        if (completedError == null && completedState != null) {
                            renderNavigation(completedState);
                        } else if (completedError != null) {
                            Log.e(TAG, "Navigation snapshot could not be read", completedError);
                        }
                    }
                    if (runAgain && activityStarted && !launcherWorker.isShutdown()) {
                        submitNavigationRead();
                    } else if (!activityStarted) {
                        navigationRefresh.cancel();
                        // load() may have populated the static bitmap cache after onStop trimmed
                        // it. With no drawable HOME, discard those references again.
                        NavigationDataRepository.trimGraphicMemoryCache();
                    }
                });
            });
        } catch (RejectedExecutionException failure) {
            navigationRefresh.cancel();
        }
    }

    private void renderNavigation(@NonNull NavigationDataRepository.Snapshot state) {
        lastNavigationSnapshot = state;
        navigationDynamicRefresh = false;
        boolean showFavorites = !navigationContentEditMode
                && CombinedNavigationPanelPolicy.showFavorites(
                state.routeActive, favoriteRoutesAvailable);
        if (favoriteRoutesPanel != null) {
            favoriteRoutesPanel.setVisibility(showFavorites ? View.VISIBLE : View.GONE);
        }
        if (navigationRouteContent != null) {
            navigationRouteContent.setVisibility(showFavorites ? View.GONE : View.VISIBLE);
        }
        boolean phaseHasContent = CombinedNavigationPanelPolicy.hasVisibleContent(
                state.routeActive, favoriteRoutesAvailable,
                navigationLiveContentAvailable, navigationInactive != null);
        setPanelVisibility(LauncherLayoutStore.NAVIGATION,
                (navigationContentEditMode || isCombinedNavigationEnabled())
                        && (editMode || navigationContentEditMode || phaseHasContent));
        if (navigationArrival == null && navigationDuration == null
                && navigationDistance == null && navigationManeuverImage == null
                && navigationManeuverDistance == null && navigationManeuver == null
                && navigationTripInfo == null && navigationCombined == null
                && navigationSpeedLimit == null
                && navigationTrafficLights == null && navigationLanesImage == null
                && navigationLaneInfo == null && navigationJamImage == null
                && navigationRainbowImage == null
                && navigationInactive == null) return;
        boolean laneTextAvailable = state.laneAvailable && (!state.lanes.isEmpty()
                || !state.laneDistance.isEmpty() || Double.isFinite(state.laneDistanceMeters));
        navigationDynamicRefresh = state.routeActive && (state.trafficAvailable
                || state.jamImage != null
                || state.lanesImage != null || state.rainbowImage != null || laneTextAvailable);
        if (!state.routeActive) {
            navigationLaunchProduct = YandexWindowLauncher.Product.NAVIGATOR;
            clearNavigationRouteViews();
            if (navigationInactive != null) {
                navigationInactive.setVisibility(View.VISIBLE);
                navigationInactive.setText(favoriteRoutesAvailable
                        ? "Маршрут не запущен"
                        : "Маршрут не запущен\nДобавьте избранные маршруты в настройках");
            }
            if (navigationContentEditMode) showNavigationEditorSamples();
            return;
        }
        navigationLaunchProduct = NavigationDataRepository.PRODUCT_MAPS.equals(state.sourceProduct)
                ? YandexWindowLauncher.Product.MAPS
                : YandexWindowLauncher.Product.NAVIGATOR;
        if (navigationInactive != null) {
            navigationInactive.setText("");
            navigationInactive.setVisibility(View.GONE);
        }
        if (navigationArrival != null) {
            navigationArrival.setVisibility(state.available ? View.VISIBLE : View.GONE);
            navigationArrival.setText(!state.available ? ""
                    : state.arrival.isEmpty() ? "Маршрут активен"
                    : "Время прибытия: " + state.arrival);
        }
        if (navigationDuration != null) {
            navigationDuration.setVisibility(state.available && !state.duration.isEmpty()
                    ? View.VISIBLE : View.GONE);
            navigationDuration.setText(state.duration.isEmpty() ? "" : "Осталось: " + state.duration);
        }
        if (navigationDistance != null) {
            navigationDistance.setVisibility(state.available && !state.distance.isEmpty()
                    ? View.VISIBLE : View.GONE);
            navigationDistance.setText(state.distance);
        }
        showNavigationImage(navigationManeuverImage,
                state.available ? state.maneuverImage : null);
        if (navigationManeuverDistance != null) {
            navigationManeuverDistance.setVisibility(state.available
                    && !state.maneuverTitle.isEmpty() ? View.VISIBLE : View.GONE);
            navigationManeuverDistance.setText(state.maneuverTitle);
        }
        if (navigationManeuver != null) {
            String maneuver = state.maneuverText.isEmpty()
                    ? state.maneuverTitle : state.maneuverText;
            navigationManeuver.setVisibility(state.available && !maneuver.isEmpty()
                    ? View.VISIBLE : View.GONE);
            navigationManeuver.setText(maneuver);
        }
        if (navigationTripInfo != null) {
            navigationTripInfo.setVisibility(state.available && !state.maneuverSubtext.isEmpty()
                    ? View.VISIBLE : View.GONE);
            navigationTripInfo.setText(state.maneuverSubtext);
        }
        if (navigationCombined != null) {
            String combinedTitle = state.available ? state.maneuverTitle : "";
            String combinedManeuver = state.available
                    ? (state.maneuverText.isEmpty()
                    ? state.maneuverSubtext : state.maneuverText) : "";
            Bitmap combinedBitmap = state.available ? state.maneuverImage : null;
            boolean combinedVisible = combinedBitmap != null
                    || (state.available && (!combinedTitle.isEmpty()
                    || !combinedManeuver.isEmpty()));
            navigationCombined.setVisibility(combinedVisible ? View.VISIBLE : View.GONE);
            if (navigationCombinedImage != null) {
                navigationCombinedImage.setImageBitmap(combinedBitmap);
                navigationCombinedImage.setVisibility(combinedBitmap == null
                        ? View.GONE : View.VISIBLE);
            }
            if (navigationCombinedDistance != null) {
                navigationCombinedDistance.setText(combinedTitle);
                navigationCombinedDistance.setVisibility(combinedTitle.isEmpty()
                        ? View.GONE : View.VISIBLE);
            }
            if (navigationCombinedManeuver != null) {
                navigationCombinedManeuver.setText(combinedManeuver);
                navigationCombinedManeuver.setVisibility(combinedManeuver.isEmpty()
                        ? View.GONE : View.VISIBLE);
            }
        }
        if (navigationSpeedLimit != null) {
            navigationSpeedLimit.setVisibility(state.available && !state.speedLimit.isEmpty()
                    ? View.VISIBLE : View.GONE);
            navigationSpeedLimit.setText(state.speedLimit.isEmpty()
                    ? "" : "Ограничение: " + state.speedLimit);
        }
        if (navigationTrafficLights != null) {
            navigationTrafficLights.removeAllViews();
            if (state.trafficAvailable) {
                if (state.trafficLights.isEmpty()) {
                    addTrafficLightRow(state.trafficColor, state.trafficCountdown,
                            state.trafficArrow, -1);
                } else {
                    for (NavigationDataRepository.TrafficLight light : state.trafficLights) {
                        addTrafficLightRow(light.color, light.countdown, light.arrow,
                                light.position);
                    }
                }
            }
            navigationTrafficLights.setVisibility(state.trafficAvailable
                    && navigationTrafficLights.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        }
        showNavigationImage(navigationLanesImage,
                state.laneAvailable ? state.lanesImage : null);
        if (navigationLaneInfo != null) {
            StringBuilder value = new StringBuilder();
            if (!state.lanes.isEmpty()) value.append(state.lanes.replace(";", " · "));
            if (!state.laneDistance.isEmpty()) {
                if (value.length() > 0) value.append("  ·  ");
                value.append(state.laneDistance);
            } else if (Double.isFinite(state.laneDistanceMeters)) {
                if (value.length() > 0) value.append("  ·  ");
                value.append(formatLaneMeters(state.laneDistanceMeters));
            }
            navigationLaneInfo.setText(value.toString());
            navigationLaneInfo.setVisibility(state.laneAvailable && value.length() > 0
                    ? View.VISIBLE : View.GONE);
        }
        showNavigationImage(navigationJamImage, state.available ? state.jamImage : null);
        showNavigationImage(navigationRainbowImage,
                state.available ? state.rainbowImage : null);
    }

    /** Clears every live field before idle/stale rendering so no old route can flash back. */
    private void clearNavigationRouteViews() {
        clearNavigationText(navigationArrival);
        clearNavigationText(navigationDuration);
        clearNavigationText(navigationDistance);
        hideNavigationImage(navigationManeuverImage);
        clearNavigationText(navigationManeuverDistance);
        clearNavigationText(navigationManeuver);
        clearNavigationText(navigationTripInfo);
        if (navigationCombined != null) navigationCombined.setVisibility(View.GONE);
        hideNavigationImage(navigationCombinedImage);
        clearNavigationText(navigationCombinedDistance);
        clearNavigationText(navigationCombinedManeuver);
        clearNavigationText(navigationSpeedLimit);
        if (navigationTrafficLights != null) {
            navigationTrafficLights.removeAllViews();
            navigationTrafficLights.setVisibility(View.GONE);
        }
        // Arrow and lane guidance are intentionally cleared independently.
        hideNavigationImage(navigationLanesImage);
        clearNavigationText(navigationLaneInfo);
        hideNavigationImage(navigationJamImage);
        hideNavigationImage(navigationRainbowImage);
    }

    private void clearNavigationText(@Nullable TextView view) {
        if (view == null) return;
        view.setText("");
        view.setVisibility(View.GONE);
    }

    /** Uses labels only in edit mode; bitmaps remain cleared so stale pixels are never previews. */
    private void showNavigationEditorSamples() {
        showNavigationSample(navigationArrival, "Прибытие 18:45");
        showNavigationSample(navigationDuration, "Осталось 24 мин");
        showNavigationSample(navigationDistance, "12,4 км");
        showNavigationSample(navigationManeuverDistance, "Через 350 м");
        showNavigationSample(navigationManeuver, "Поверните направо");
        showNavigationSample(navigationTripInfo, "Затем держитесь левее");
        if (navigationCombined != null) navigationCombined.setVisibility(View.VISIBLE);
        showNavigationSample(navigationCombinedDistance, "Через 350 м");
        showNavigationSample(navigationCombinedManeuver, "Поверните направо");
        showNavigationSample(navigationSpeedLimit, "Ограничение: 60");
        if (navigationTrafficLights != null) {
            navigationTrafficLights.removeAllViews();
            addTrafficLightRow("GREEN", "12", "", -1);
            navigationTrafficLights.setVisibility(View.VISIBLE);
        }
        showNavigationSample(navigationLaneInfo, "Левая полоса · 500 м");
        showNavigationSample(navigationInactive, "Маршрут не запущен");
    }

    private void showNavigationSample(@Nullable TextView view, @NonNull String value) {
        if (view == null) return;
        view.setText(value);
        view.setVisibility(View.VISIBLE);
    }

    private void scheduleNavigationRefresh() {
        navigationUiHandler.removeCallbacks(navigationUiRefresh);
        if (!activityStarted || !panelsInitialized
                || (!isCombinedNavigationEnabled() && !editMode
                && !navigationContentEditMode)) return;
        navigationUiHandler.postDelayed(navigationUiRefresh, navigationDynamicRefresh
                ? NAVIGATION_DYNAMIC_REFRESH_MS : NAVIGATION_UI_REFRESH_MS);
    }

    private void showNavigationImage(@Nullable ImageView view, @Nullable Bitmap bitmap) {
        if (view == null) return;
        view.setImageBitmap(bitmap);
        view.setVisibility(bitmap == null ? View.GONE : View.VISIBLE);
    }

    private void hideNavigationImage(@Nullable ImageView view) {
        if (view == null) return;
        view.setImageDrawable(null);
        view.setVisibility(View.GONE);
    }

    /** Releases multi-megabyte route bitmaps whenever HOME is no longer drawable. */
    private void releaseNavigationGraphics() {
        hideNavigationImage(navigationManeuverImage);
        hideNavigationImage(navigationLanesImage);
        hideNavigationImage(navigationJamImage);
        hideNavigationImage(navigationRainbowImage);
        if (navigationCombinedImage != null) {
            navigationCombinedImage.setImageDrawable(null);
            navigationCombinedImage.setVisibility(View.GONE);
        }
        NavigationDataRepository.trimGraphicMemoryCache();
    }

    private void addTrafficLightRow(String color, String countdown, String arrow, int position) {
        if (navigationTrafficLights == null || color == null || color.isEmpty()) return;
        int tint;
        String label;
        switch (color.toUpperCase(Locale.ROOT)) {
            case "GREEN": label = "Зелёный"; tint = Color.rgb(80, 220, 120); break;
            case "YELLOW": label = "Жёлтый"; tint = Color.rgb(255, 210, 60); break;
            case "RED": label = "Красный"; tint = Color.rgb(255, 90, 90); break;
            default: label = color; tint = Color.WHITE; break;
        }
        TextView row = text(18f * navigationTrafficScalePercent / 100f, tint, true);
        String prefix = position >= 0 ? "Светофор " + (position + 1) + ": " : "Светофор: ";
        String suffix = countdown == null || countdown.isEmpty()
                ? "" : " · " + countdown + " с";
        if (arrow != null && !arrow.isEmpty()) suffix += " · " + arrow;
        row.setText(prefix + label + suffix);
        navigationTrafficLights.addView(row);
    }

    private static String formatLaneMeters(double meters) {
        if (meters >= 1_000d) {
            return String.format(Locale.getDefault(), meters >= 10_000d ? "%.0f км" : "%.1f км",
                    meters / 1_000d);
        }
        return String.format(Locale.getDefault(), "%.0f м", meters);
    }

    private void launchYandex(YandexWindowLauncher.Product product, boolean full) {
        if (!YandexWindowLauncher.launch(this, product, full)) {
            Toast.makeText(this, "Яндекс-приложение не найдено", Toast.LENGTH_SHORT).show();
        }
    }

    private void showAllApps() {
        dismissAllAppsDialog();
        GridView grid = new GridView(this);
        grid.setNumColumns(5);
        grid.setPadding(dp(16), dp(16), dp(16), dp(16));
        grid.setVerticalSpacing(dp(8));
        AppAdapter adapter = new AppAdapter(appCatalog.all(), false);
        grid.setAdapter(adapter);
        boolean overlay = Permissions.checkOverlayPermission(this);
        Context dialogContext = overlay ? getApplicationContext() : this;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(dialogContext,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Все приложения · удержание добавляет в избранное")
                .setView(grid)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        Window dialogWindow = dialog.getWindow();
        if (overlay && dialogWindow != null) {
            // A normal activity dialog remains below an already-open Yandex freeform window.
            // The launcher already owns overlay permission for Status Widget; using the same
            // interactive window tier keeps the app stack above Navigator, Maps and any app.
            dialogWindow.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        }
        allAppsDialog = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (allAppsDialog == dialog) allAppsDialog = null;
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            dismissAllAppsDialog();
            launchApp((AppEntry) parent.getItemAtPosition(position));
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            AppEntry entry = (AppEntry) parent.getItemAtPosition(position);
            appCatalog.toggleFavorite(entry.packageName);
            refreshFavorites();
            Toast.makeText(this, "Избранное обновлено", Toast.LENGTH_SHORT).show();
            return true;
        });
        try {
            dialog.show();
        } catch (RuntimeException failure) {
            allAppsDialog = null;
            Log.e(TAG, "Could not show all-apps overlay", failure);
            Toast.makeText(this, overlay
                    ? "Не удалось показать список поверх приложений"
                    : "Не удалось открыть список приложений",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void dismissAllAppsDialog() {
        android.app.AlertDialog dialog = allAppsDialog;
        allAppsDialog = null;
        if (dialog == null) return;
        try { dialog.dismiss(); }
        catch (RuntimeException ignored) {}
    }

    private void refreshFavorites() {
        if (favoritesGrid != null && appCatalog != null) {
            favoritesGrid.setAdapter(new AppAdapter(appCatalog.favorites(), true));
        }
    }

    private void launchApp(@NonNull AppEntry entry) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(entry.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(intent);
        } catch (RuntimeException error) {
            Toast.makeText(this, "Не удалось открыть " + entry.label, Toast.LENGTH_SHORT).show();
        }
    }

    @NonNull private LinearLayout verticalContainer() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(12));
        return root;
    }

    @NonNull private TextView heading(String value) {
        TextView text = text(18, Color.WHITE, true);
        text.setText(value);
        text.setGravity(Gravity.CENTER_VERTICAL);
        return text;
    }

    @NonNull private TextView text(float size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setMaxLines(2);
        return text;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(matchWidth(), matchHeight());
    }

    private static int matchWidth() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int matchHeight() { return ViewGroup.LayoutParams.MATCH_PARENT; }
    private static int wrapContent() { return ViewGroup.LayoutParams.WRAP_CONTENT; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static int blend(int first, int second, float amount) {
        float inverse = 1f - amount;
        return Color.rgb(Math.round(Color.red(first) * inverse + Color.red(second) * amount),
                Math.round(Color.green(first) * inverse + Color.green(second) * amount),
                Math.round(Color.blue(first) * inverse + Color.blue(second) * amount));
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final ComponentName component;
        @Nullable private volatile Drawable cachedIcon;

        AppEntry(String label, String packageName, ComponentName component) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
        }

        Drawable icon(Context context, boolean cache) {
            Drawable value = cache ? cachedIcon : null;
            if (value != null) return value;
            value = HighResolutionAppIconLoader.load(context, component);
            if (value == null) value = ContextCompat.getDrawable(
                    context, R.drawable.ic_launcher_apps);
            if (cache) cachedIcon = value;
            return value;
        }

        void clearIcon() { cachedIcon = null; }
    }

    private static final class ShortcutPlacement {
        final LauncherShortcutStore.Shortcut shortcut;
        final int row;
        final int column;
        final int columnSpan;
        final int rowSpan;

        ShortcutPlacement(LauncherShortcutStore.Shortcut shortcut, int row, int column,
                          int columnSpan, int rowSpan) {
            this.shortcut = shortcut;
            this.row = row;
            this.column = column;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }
    }

    private static final class ShortcutTileBinding {
        final LauncherShortcutStore.Shortcut shortcut;
        final MaterialCardView card;
        final ImageView icon;
        @Nullable final TextView stateLabel;

        ShortcutTileBinding(LauncherShortcutStore.Shortcut shortcut, MaterialCardView card,
                            ImageView icon, @Nullable TextView stateLabel) {
            this.shortcut = shortcut;
            this.card = card;
            this.icon = icon;
            this.stateLabel = stateLabel;
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> values;
        private final boolean cacheIcons;
        private final Map<String, FavoriteAppConfig> appearances;
        AppAdapter(List<AppEntry> values, boolean cacheIcons) {
            this.values = values;
            this.cacheIcons = cacheIcons;
            // One JSON parse for this adapter instead of one full parse for every recycled cell.
            this.appearances = favoriteAppsConfigStore.appearanceSnapshot();
        }
        @Override public int getCount() { return values.size(); }
        @Override public AppEntry getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            LinearLayout cell = reusable instanceof LinearLayout
                    ? (LinearLayout) reusable : new LinearLayout(LauncherActivity.this);
            cell.removeAllViews();
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            cell.setPadding(dp(4), dp(5), dp(4), dp(5));
            AppEntry entry = getItem(position);
            FavoriteAppConfig appearance = appearances.get(entry.packageName);
            if (appearance == null) appearance = new FavoriteAppConfig(entry.packageName);
            ImageView icon = new ImageView(LauncherActivity.this);
            icon.setImageDrawable(entry.icon(LauncherActivity.this, cacheIcons));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // iconSizePx is intentionally stored in physical pixels for head-unit layouts.
            int iconSize = Math.max(24,
                    appearance.iconSizePx * appsGridScalePercent / 100);
            cell.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
            if (appearance.showLabel) {
                TextView label = text(appearance.labelSizeSp * appsGridScalePercent / 100f,
                        Color.WHITE, false);
                label.setGravity(Gravity.CENTER);
                label.setText(entry.label);
                label.setMaxLines(1);
                cell.addView(label, new LinearLayout.LayoutParams(matchWidth(),
                        Math.max(dp(18), dp(25) * appsGridScalePercent / 100)));
            }
            return cell;
        }
    }

    private final class AppCatalog {
        private final Context context;
        private final List<AppEntry> apps = new ArrayList<>();
        AppCatalog(Context context) { this.context = context; }

        void reload() {
            apps.clear();
            PackageManager manager = context.getPackageManager();
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = manager.queryIntentActivities(query, 0);
            Set<String> components = new LinkedHashSet<>();
            for (ResolveInfo info : resolved) {
                if (info.activityInfo == null) continue;
                ComponentName component = new ComponentName(info.activityInfo.packageName,
                        info.activityInfo.name);
                if (!components.add(component.flattenToString())) continue;
                apps.add(new AppEntry(String.valueOf(info.loadLabel(manager)),
                        info.activityInfo.packageName, component));
            }
            apps.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
            ensureDefaultFavorites();
            // Preload only the handful of icons shown on HOME. The full application list keeps
            // lazy icons so dozens of adaptive drawables do not remain resident permanently.
            for (AppEntry favorite : favorites()) favorite.icon(context, true);
        }

        List<AppEntry> all() { return new ArrayList<>(apps); }

        boolean isEmpty() { return apps.isEmpty(); }

        void clearIcons() {
            for (AppEntry app : apps) app.clearIcon();
        }

        List<AppEntry> favorites() {
            Set<String> wanted = favoritePackages();
            List<AppEntry> result = new ArrayList<>();
            for (String packageName : wanted) {
                for (AppEntry app : apps) {
                    if (packageName.equals(app.packageName)) {
                        result.add(app);
                        break;
                    }
                }
            }
            return result;
        }

        void toggleFavorite(String packageName) {
            if (favoriteAppsConfigStore.contains(packageName)) {
                favoriteAppsConfigStore.remove(packageName);
            } else {
                favoriteAppsConfigStore.add(packageName);
            }
        }

        private void ensureDefaultFavorites() {
            if (!preferences.launcherFavoritePackages.get().trim().isEmpty()) return;
            String[] preferred = {"ru.yandex.yandexmaps", "ru.yandex.yandexnavi",
                    "ru.yandex.music", "com.yandex.music", "com.android.settings",
                    getPackageName()};
            LinkedHashSet<String> initial = new LinkedHashSet<>();
            for (String wanted : preferred) {
                for (AppEntry app : apps) if (wanted.equals(app.packageName)) initial.add(wanted);
            }
            for (AppEntry app : apps) {
                if (initial.size() >= 9) break;
                initial.add(app.packageName);
            }
            preferences.launcherFavoritePackages.set(String.join(",", initial));
        }

        @NonNull
        private Set<String> favoritePackages() {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (FavoriteAppConfig value : favoriteAppsConfigStore.load()) {
                values.add(value.packageName);
            }
            return values;
        }
    }
}
