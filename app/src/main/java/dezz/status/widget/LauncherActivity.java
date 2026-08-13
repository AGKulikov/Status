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
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import dezz.status.widget.launcher.EcarxBtPhoneBridge;
import dezz.status.widget.launcher.AppDrawerTileView;
import dezz.status.widget.launcher.AppDrawerUninstallPolicy;
import dezz.status.widget.launcher.AppUninstallLauncher;
import dezz.status.widget.launcher.HighResolutionAppIconLoader;
import dezz.status.widget.launcher.InformationShortcutView;
import dezz.status.widget.launcher.LauncherBackdropStore;
import dezz.status.widget.launcher.LauncherBackdropView;
import dezz.status.widget.launcher.LauncherActionsGridConfig;
import dezz.status.widget.launcher.LauncherActionsGridConfigStore;
import dezz.status.widget.launcher.LauncherAppCatalog;
import dezz.status.widget.launcher.LauncherAppTileRenderer;
import dezz.status.widget.launcher.LauncherElementFrame;
import dezz.status.widget.launcher.LauncherGlobalElementLayoutStore;
import dezz.status.widget.launcher.LauncherGlobalElementProxyView;
import dezz.status.widget.launcher.LauncherGlobalElementTag;
import dezz.status.widget.launcher.LauncherGridView;
import dezz.status.widget.launcher.HorizontalGroupLayout;
import dezz.status.widget.launcher.LauncherHorizontalGroupStore;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.LauncherIconResolver;
import dezz.status.widget.launcher.LauncherRuntimeProbePolicy;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.LauncherWidgetCatalog;
import dezz.status.widget.launcher.LauncherSafeAreaPolicy;
import dezz.status.widget.launcher.LauncherSafeAreaResolver;
import dezz.status.widget.launcher.LauncherWorkspaceView;
import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.launcher.SingleFlightRefresh;
import dezz.status.widget.launcher.SmartHomeShortcutStateBindingPolicy;
import dezz.status.widget.launcher.SmartHomeShortcutStatePolicy;
import dezz.status.widget.popup.SmartHomeTileColorPolicy;
import dezz.status.widget.launcher.YandexWindowLauncher;
import dezz.status.widget.launcher.apps.FavoriteAppConfig;
import dezz.status.widget.launcher.apps.FavoriteAppsConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelConfig;
import dezz.status.widget.launcher.climate.ClimatePanelConfigStore;
import dezz.status.widget.launcher.climate.ClimatePanelView;
import dezz.status.widget.launcher.media.MediaPanelConfig;
import dezz.status.widget.launcher.media.MediaPanelConfigStore;
import dezz.status.widget.launcher.media.MediaPanelView;
import dezz.status.widget.launcher.information.InformationPanelConfigStore;
import dezz.status.widget.launcher.information.InformationPanelView;
import dezz.status.widget.launcher.navigation.NavigationPanelConfig;
import dezz.status.widget.launcher.navigation.NavigationPanelConfigStore;
import dezz.status.widget.launcher.panels.PanelContentEditOverlay;
import dezz.status.widget.launcher.panels.PanelElementConfigStore;
import dezz.status.widget.launcher.panels.PanelGridLayout;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;
import dezz.status.widget.launcher.routes.FavoriteRoutesPanelView;
import dezz.status.widget.launcher.routes.FavoriteRouteConfig;
import dezz.status.widget.launcher.routes.YandexRouteLauncher;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfigStore;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelView;
import dezz.status.widget.car.CarControlCommand;
import dezz.status.widget.car.CarControlState;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.TrunkControlSafety;
import dezz.status.widget.automation.ScenarioTriggerReceiver;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.scenario.IntentActionRule;
import dezz.status.widget.scenario.IntentActionRuleStore;
import dezz.status.widget.settings.AppleColorPickerDialog;
import dezz.status.widget.shell.PrivilegedShell;
import dezz.status.widget.sprut.SprutHubController;

/** Full HOME implementation that coexists with the original Status Widget settings activity. */
public final class LauncherActivity extends AppCompatActivity {
    private static final String TAG = "LauncherActivity";
    public static final String EXTRA_EDIT_MODE = "dezz.status.widget.extra.EDIT_HOME";
    public static final String EXTRA_SHOW_WIDGET_CATALOG =
            "dezz.status.widget.extra.SHOW_HOME_WIDGET_CATALOG";
    public static final String EXTRA_EDIT_NAVIGATION_CONTENT =
            "dezz.status.widget.extra.EDIT_NAVIGATION_CONTENT";
    public static final String EXTRA_EDIT_MEDIA_CONTENT =
            "dezz.status.widget.extra.EDIT_MEDIA_CONTENT";
    public static final String EXTRA_EDIT_ACTIONS_CONTENT =
            "dezz.status.widget.extra.EDIT_ACTIONS_CONTENT";
    private static final long NAVIGATION_DYNAMIC_REFRESH_MS = 5_000L;
    private static final long APP_CATALOG_REFRESH_MS = 10L * 60L * 1_000L;
    private final Map<String, LauncherElementFrame> panels = new LinkedHashMap<>();
    private final Map<String, LauncherElementFrame> globalElementFrames =
            new LinkedHashMap<>();
    private final Map<String, LauncherGlobalElementProxyView> globalElementProxies =
            new LinkedHashMap<>();
    private final Map<String, View> globalElementSources = new LinkedHashMap<>();
    private final Map<String, LauncherElementFrame> backdropFrames = new LinkedHashMap<>();
    private final Map<String, LauncherBackdropView> backdropViews = new LinkedHashMap<>();
    private final Map<String, LauncherElementFrame> horizontalGroupFrames =
            new LinkedHashMap<>();
    private final Handler navigationUiHandler = new Handler(Looper.getMainLooper());
    /** One serial background lane bounds boot CPU while letting every module submit immediately. */
    private final ExecutorService launcherWorker = Executors.newSingleThreadExecutor(runnable -> {
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
    private boolean globalElementRefreshPosted;
    private final Runnable globalElementRefresh = () -> {
        globalElementRefreshPosted = false;
        if (!activityStarted || !globalElementsActivated
                || isFinishing() || isDestroyed()) return;
        refreshGlobalElementVisibility();
    };
    private final BroadcastReceiver navigationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            updateNavigation();
            scheduleNavigationRefresh();
        }
    };

    private Preferences preferences;
    private LauncherLayoutStore layoutStore;
    private LauncherGlobalElementLayoutStore globalElementLayoutStore;
    private LauncherBackdropStore backdropStore;
    private LauncherHorizontalGroupStore horizontalGroupStore;
    private PanelElementConfigStore panelElementStore;
    private LauncherActionsGridConfigStore actionsGridConfigStore;
    private NavigationPanelConfigStore navigationPanelConfigStore;
    private FavoriteAppsConfigStore favoriteAppsConfigStore;
    private FavoriteRoutesConfigStore favoriteRoutesConfigStore;
    private VehicleInfoPanelConfigStore vehicleInfoConfigStore;
    private InformationPanelConfigStore informationConfigStore;
    private FrameLayout launcherRoot;
    private LauncherWorkspaceView workspace;
    private LauncherGridView editorGrid;
    private MaterialButton doneButton;
    private MaterialButton widgetCatalogButton;
    private boolean editMode;
    private boolean navigationContentEditMode;
    private boolean mediaContentEditMode;
    private boolean actionsContentEditMode;
    private int systemLeftInset;
    private int systemTopInset;
    private int systemRightInset;
    private int systemBottomInset;
    @Nullable private LauncherSafeAreaPolicy.Insets appliedSafeInsets;
    private boolean panelsInitialized;
    private boolean launcherBootstrapStarted;
    private boolean launcherBootstrapReady;
    private int launcherBootstrapGeneration;
    @Nullable private LauncherBootstrapState pendingLauncherBootstrapState;
    @Nullable private RuntimeException pendingLauncherBootstrapFailure;
    private boolean globalElementsActivated;
    private boolean initialBackdropsSynchronized;
    private boolean initialBackdropLoadInFlight;
    private int initialBackdropLoadGeneration;
    private boolean panelsInitializing;
    private boolean panelGeometryReady;
    private int panelInitializationGeneration;
    private int panelInitializationStage;
    private boolean navigationReceiverRegistered;
    private boolean navigationDynamicRefresh;
    private boolean navigationLiveContentAvailable;
    private boolean deferredLauncherRuntimeStarted;
    private int deferredLauncherRuntimeStage;
    private boolean carIntegrationLoadInFlight;
    private int carIntegrationLoadGeneration;
    private boolean homeRootInvocation;
    private boolean pendingAutomaticNavigatorLaunch;
    private boolean ecarxPhoneWakeSentForStart;
    @Nullable private View navigationRouteContent;
    @Nullable private PanelGridLayout navigationGrid;
    @Nullable private PanelContentEditOverlay navigationContentEditOverlay;
    @Nullable private NavigationPanelConfig navigationPanelConfig;
    @Nullable private NavigationDataRepository.Snapshot lastNavigationSnapshot;
    private LauncherMediaController mediaController;
    private MediaPanelView mediaPanel;
    @Nullable private android.app.AlertDialog allAppsDialog;
    @Nullable private GridView allAppsGrid;
    @Nullable private TextView allAppsTitle;
    @Nullable private MaterialButton allAppsDone;
    private boolean allAppsEditMode;
    private boolean allAppsUninstallInProgress;
    private boolean allAppsUninstallReceiverRegistered;
    private final BroadcastReceiver allAppsUninstallReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            finishAllAppsUninstallFlow();
        }
    };
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
    @Nullable private PanelGridLayout shortcutGrid;
    @Nullable private PanelContentEditOverlay actionsContentEditOverlay;
    @Nullable private LauncherActionsGridConfig actionsGridConfig;
    @Nullable private CarIntegration carIntegration;
    private final Map<String, CarControlState> carControlStates = new HashMap<>();
    private final Map<String, ShortcutTileBinding> carShortcutBindings = new HashMap<>();
    private final List<InformationShortcutView> informationShortcutViews = new ArrayList<>();
    private final Map<String, ShortcutTileBinding> smartHomeShortcutBindings = new HashMap<>();
    private Map<String, IntentActionRule> smartHomeRules = Collections.emptyMap();
    private final Map<String, ConnectorValue> smartHomeValueIndex = new HashMap<>();
    @Nullable private WidgetService smartHomeValueService;
    private int runtimeBindingProbeFailures;
    private boolean runtimeBindingProbePosted;
    private final Set<String> pendingCarControls = new LinkedHashSet<>();
    private boolean activityStarted;
    private ClimatePanelView climatePanel;
    private FavoriteRoutesPanelView favoriteRoutesPanel;
    private boolean favoriteRoutesAvailable;
    private VehicleInfoPanelView vehicleInfoPanel;
    private InformationPanelView informationPanel;
    @Nullable private String appliedPanelElementsJson;
    @Nullable private String appliedNavigationConfigJson;
    @Nullable private String appliedActionsGridJson;
    @Nullable private String appliedGlobalElementsJson;
    @Nullable private String appliedLauncherBackdropsJson;
    @Nullable private String appliedLauncherHorizontalGroupsJson;
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
        requestGlobalElementRefresh();
    };
    private final ConnectorValueRegistry.Listener smartHomeValueListener = changed -> {
        List<ConnectorValue> copy = new ArrayList<>(changed);
        navigationUiHandler.post(() -> applySmartHomeChanges(copy));
    };
    private boolean safeAreaRefreshPosted;
    private final Runnable safeAreaRefresh = () -> {
        safeAreaRefreshPosted = false;
        if (!activityStarted || !launcherBootstrapReady
                || isFinishing() || isDestroyed()) return;
        updateLauncherSafeArea();
    };
    private final Runnable ensureSmartHomeValueSubscription = new Runnable() {
        @Override public void run() {
            runtimeBindingProbePosted = false;
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
                } else {
                    applySmartHomeValues(Collections.emptyList());
                }
            }
            // WindowInsets and lifecycle changes are event-driven. This low-rate fallback covers
            // WidgetService/climate overlay geometry, whose legacy APIs expose no shared listener.
            requestLauncherSafeAreaRefresh();
            boolean ready = current != null;
            int failedAttempt = runtimeBindingProbeFailures;
            runtimeBindingProbeFailures = ready ? 0
                    : Math.min(Integer.MAX_VALUE - 1, runtimeBindingProbeFailures + 1);
            long delay = LauncherRuntimeProbePolicy.nextDelayMillis(ready, failedAttempt);
            runtimeBindingProbePosted = true;
            navigationUiHandler.postDelayed(this, delay);
        }
    };
    private final Runnable visibleIntegrationHostHandoff = new Runnable() {
        @Override public void run() {
            if (!activityStarted || isFinishing() || isDestroyed()) return;
            long delay = StartupWorkCoordinator.pendingIntegrationHostDelayMillis(
                    LauncherActivity.this);
            if (delay < 0L) return;
            if (delay > 0L) {
                navigationUiHandler.postDelayed(this, delay);
                return;
            }
            StartupWorkCoordinator.dispatchPendingIntegrationHostIfDue(
                    LauncherActivity.this);
        }
    };
    private boolean launcherFirstDrawCompleted;
    private final Runnable panelInitializationStep = this::continuePanelInitialization;
    private final Runnable deferredLauncherRuntimeStart = new Runnable() {
        @Override public void run() {
            if (!activityStarted || !launcherBootstrapReady
                    || isFinishing() || isDestroyed()) return;
            if (!panelsInitialized) {
                return;
            }
            if (deferredLauncherRuntimeStarted) return;
            deferredLauncherRuntimeStarted = true;
            deferredLauncherRuntimeStage = 0;
            navigationUiHandler.post(deferredLauncherRuntimeStep);
        }
    };
    private final Runnable deferredLauncherRuntimeStep = new Runnable() {
        @Override public void run() {
            if (!activityStarted || !deferredLauncherRuntimeStarted
                    || isFinishing() || isDestroyed()) return;
            switch (deferredLauncherRuntimeStage++) {
                case 0:
                    if (!ecarxPhoneWakeSentForStart) {
                        ecarxPhoneWakeSentForStart = true;
                        EcarxBtPhoneBridge.onLauncherVisible(LauncherActivity.this);
                    }
                    break;
                case 1:
                    reconcileMediaController();
                    break;
                case 2:
                    startLauncherCarRuntimeAsync();
                    break;
                case 3:
                    scheduleRuntimeBindingProbe();
                    break;
                case 4:
                    updateNavigation();
                    scheduleNavigationRefresh();
                    break;
                case 5:
                    if (pendingAutomaticNavigatorLaunch) {
                        pendingAutomaticNavigatorLaunch = false;
                        launchYandex(YandexWindowLauncher.Product.NAVIGATOR, false);
                        return;
                    }
                    break;
                case 6:
                    if (preferences.launcherAppsVisible.get()) reloadAppCatalogAsync(true);
                    return;
                default:
                    return;
            }
            // Yield to input/traversal without imposing an artificial wall-clock delay. Heavy
            // reads and vendor construction submit to the one serial background lane above.
            navigationUiHandler.post(this);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        homeRootInvocation = isHomeInvocation(getIntent());
        // Opening SharedPreferences is asynchronous, but the default constructor immediately
        // performs JSON migrations and synchronous commit(). HOME needs none of that to attach its
        // shell, so the one shared Preferences graph is submitted to launcherWorker immediately.
        preferences = new Preferences(this, false);
        configureWindow();
        View root = buildRoot();
        setContentView(root);
        // Submit every nonvisual startup command now. The serial worker is background-priority and
        // the Service admission is merely queued on main; neither can lengthen this onCreate stack.
        startLauncherBootstrapNow();
        startLauncherCarRuntimeAsync();
        navigationUiHandler.post(this::startImmediateHomeRuntime);
        root.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
            private boolean recorded;
            @Override public void onDraw() {
                if (recorded) return;
                recorded = true;
                launcherFirstDrawCompleted = true;
                StartupPerformanceTrace.mark("launcher_first_draw");
                StatusWidgetApplication.notifyFirstUsefulSurface(LauncherActivity.this);
                root.post(() -> {
                    if (root.getViewTreeObserver().isAlive()) {
                        root.getViewTreeObserver().removeOnDrawListener(this);
                    }
                    applyPendingLauncherBootstrapAfterFirstDraw();
                    startImmediateHomeRuntime();
                    scheduleInitialLauncherBackdrops();
                });
            }
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            systemLeftInset = Math.max(0, insets.getSystemWindowInsetLeft());
            systemTopInset = Math.max(0, insets.getSystemWindowInsetTop());
            systemRightInset = Math.max(0, insets.getSystemWindowInsetRight());
            systemBottomInset = Math.max(0, insets.getSystemWindowInsetBottom());
            requestLauncherSafeAreaRefresh();
            return insets;
        });
        root.requestApplyInsets();
        workspace.setDescendantInvalidationListener(
                this::onWorkspaceDescendantInvalidated);
        workspace.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right <= left || bottom <= top) return;
            // Large custom backdrop JSON belongs to the post-draw background lane. Wallpaper plus
            // the empty workspace are already a complete first HOME frame.
            if (!initialBackdropsSynchronized) {
                scheduleInitialLauncherBackdrops();
            }
            if (launcherBootstrapReady && !panelsInitialized && !panelsInitializing) {
                initializePanels();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isHomeInvocation(intent)) homeRootInvocation = true;
        handleStagedOrHomeNavigation(intent);
        if (panelsInitialized) {
            workspace.post(() -> {
                activateGlobalElements();
                applyRequestedHomeEditor(intent);
            });
        }
    }

    private static boolean requestsAnyHomeEditor(@Nullable Intent intent) {
        return intent != null && (intent.getBooleanExtra(EXTRA_EDIT_MODE, false)
                || intent.getBooleanExtra(EXTRA_SHOW_WIDGET_CATALOG, false)
                || intent.getBooleanExtra(EXTRA_EDIT_MEDIA_CONTENT, false)
                || intent.getBooleanExtra(EXTRA_EDIT_NAVIGATION_CONTENT, false)
                || intent.getBooleanExtra(EXTRA_EDIT_ACTIONS_CONTENT, false));
    }

    private static boolean isHomeInvocation(@Nullable Intent intent) {
        return intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    /** Settings and the in-editor plus button converge on this exact catalog method. */
    private void applyRequestedHomeEditor(@Nullable Intent intent) {
        if (!requestsAnyHomeEditor(intent)) return;
        if (intent != null && intent.getBooleanExtra(EXTRA_EDIT_MEDIA_CONTENT, false)) {
            intent.removeExtra(EXTRA_EDIT_MEDIA_CONTENT);
            setMediaContentEditMode(true);
        } else if (intent != null
                && intent.getBooleanExtra(EXTRA_EDIT_NAVIGATION_CONTENT, false)) {
            intent.removeExtra(EXTRA_EDIT_NAVIGATION_CONTENT);
            setNavigationContentEditMode(true);
        } else if (intent != null
                && intent.getBooleanExtra(EXTRA_EDIT_ACTIONS_CONTENT, false)) {
            intent.removeExtra(EXTRA_EDIT_ACTIONS_CONTENT);
            setActionsContentEditMode(true);
        } else {
            setEditMode(true);
        }
        if (intent != null && intent.getBooleanExtra(
                EXTRA_SHOW_WIDGET_CATALOG, false)) {
            intent.removeExtra(EXTRA_SHOW_WIDGET_CATALOG);
            workspace.post(this::showLauncherWidgetCatalog);
        }
    }

    private void handleStagedOrHomeNavigation(@Nullable Intent intent) {
        if (intent == null || !launcherBootstrapReady) return;
        String staged = intent.getStringExtra(YandexWindowLauncher.EXTRA_STAGED_PRODUCT);
        if (staged != null && !staged.trim().isEmpty()) {
            intent.removeExtra(YandexWindowLauncher.EXTRA_STAGED_PRODUCT);
            boolean full = intent.getBooleanExtra(
                    YandexWindowLauncher.EXTRA_STAGED_FULLSCREEN, false);
            intent.removeExtra(YandexWindowLauncher.EXTRA_STAGED_FULLSCREEN);
            final YandexWindowLauncher.Product product;
            try {
                product = YandexWindowLauncher.Product.valueOf(staged);
            } catch (IllegalArgumentException error) {
                return;
            }
            navigationUiHandler.post(() -> launchYandex(product, full));
            return;
        }
        boolean homeIntent = Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
        if (homeIntent && preferences.launcherHomeOpensWindowedNavigator.get()) {
            pendingAutomaticNavigatorLaunch = true;
            if (activityStarted) scheduleDeferredLauncherRuntimeStart();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        applyPendingLauncherBootstrapAfterFirstDraw();
        if (launcherBootstrapReady && !panelsInitialized) {
            navigationUiHandler.removeCallbacks(panelInitializationStep);
            if (launcherFirstDrawCompleted && panelsInitializing && panelGeometryReady) {
                navigationUiHandler.post(panelInitializationStep);
            } else if (launcherFirstDrawCompleted && !panelsInitializing) {
                initializePanels();
            }
        }
        if (launcherFirstDrawCompleted) {
            navigationUiHandler.post(this::startImmediateHomeRuntime);
        }
        scheduleDeferredLauncherRuntimeStart();
        requestLauncherSafeAreaRefresh();
        scheduleRuntimeBindingProbe();
        if (panelsInitialized) refreshFavorites();
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        // If HOME lost foreground before its first traversal (or between onDraw and its posted
        // handoff), leave only a delayed AlarmManager owner. This does no controller/vendor work
        // while the next application is taking focus and is idempotent with the normal draw path.
        if (launcherBootstrapReady && !WidgetService.isRunning()
                && WidgetServiceStarter.requiresAutomaticIntegrationHost(preferences)
                && StartupWorkCoordinator.pendingIntegrationHostDelayMillis(this) < 0L) {
            StartupWorkCoordinator.ensureIntegrationHostScheduledAfter(this,
                    StartupLoadPolicy.COLD_BOOT_RUNTIME_TARGET_ELAPSED_MS);
        }
        // Never inflate optional panels behind another foreground application.
        navigationUiHandler.removeCallbacks(panelInitializationStep);
        navigationUiHandler.removeCallbacks(navigationUiRefresh);
        navigationUiHandler.removeCallbacks(safeAreaRefresh);
        navigationUiHandler.removeCallbacks(globalElementRefresh);
        navigationUiHandler.removeCallbacks(ensureSmartHomeValueSubscription);
        globalElementRefreshPosted = false;
        safeAreaRefreshPosted = false;
        runtimeBindingProbePosted = false;
        runtimeBindingProbeFailures = 0;
        navigationUiHandler.removeCallbacks(visibleIntegrationHostHandoff);
        initialBackdropLoadGeneration++;
        initialBackdropLoadInFlight = false;
        navigationUiHandler.removeCallbacks(deferredLauncherRuntimeStart);
        navigationUiHandler.removeCallbacks(deferredLauncherRuntimeStep);
        deferredLauncherRuntimeStarted = false;
        deferredLauncherRuntimeStage = 0;
        ecarxPhoneWakeSentForStart = false;
        carIntegrationLoadGeneration++;
        carIntegrationLoadInFlight = false;
        if (!allAppsUninstallInProgress) dismissAllAppsDialog();
        if (smartHomeValueService != null) {
            smartHomeValueService.removeConnectorValueListener(smartHomeValueListener);
            smartHomeValueService = null;
        }
        applySmartHomeValues(Collections.emptyList());
        if (climatePanel != null) climatePanel.stop();
        if (vehicleInfoPanel != null) vehicleInfoPanel.stop();
        if (informationPanel != null) informationPanel.stop();
        for (InformationShortcutView shortcut : informationShortcutViews) shortcut.stop();
        if (carIntegration != null) carIntegration.unsubscribeControlStates(carStateListener);
        if (mediaController != null) mediaController.stop();
        releaseNavigationGraphics();
        unregisterNavigationReceiver();
        unregisterAllAppsUninstallReceiver();
        super.onStop();
    }

    /** Starts migration immediately, but never applies its result ahead of HOME's shell frame. */
    private void startLauncherBootstrapNow() {
        if (launcherBootstrapStarted || isFinishing() || isDestroyed()) return;
        launcherBootstrapStarted = true;
        final int generation = ++launcherBootstrapGeneration;
        try {
            launcherWorker.execute(() -> {
                RuntimeException migrationFailure = null;
                try {
                    preferences.completeDeferredStartupMigrations();
                } catch (RuntimeException failure) {
                    migrationFailure = failure;
                }
                LauncherBootstrapState loaded = new LauncherBootstrapState(preferences);
                RuntimeException completedFailure = migrationFailure;
                navigationUiHandler.post(() -> finishLauncherBootstrap(
                        generation, loaded, completedFailure));
            });
        } catch (RejectedExecutionException failure) {
            launcherBootstrapStarted = false;
            Log.e(TAG, "Could not schedule launcher preference bootstrap", failure);
        }
    }

    private void finishLauncherBootstrap(
            int generation,
            @NonNull LauncherBootstrapState loaded,
            @Nullable RuntimeException migrationFailure) {
        if (generation != launcherBootstrapGeneration || isDestroyed()) return;
        if (!launcherFirstDrawCompleted || !activityStarted) {
            pendingLauncherBootstrapState = loaded;
            pendingLauncherBootstrapFailure = migrationFailure;
            return;
        }
        applyLauncherBootstrapState(loaded, migrationFailure);
    }

    private void applyPendingLauncherBootstrapAfterFirstDraw() {
        LauncherBootstrapState loaded = pendingLauncherBootstrapState;
        if (loaded == null || launcherBootstrapReady
                || !launcherFirstDrawCompleted || !activityStarted) return;
        RuntimeException failure = pendingLauncherBootstrapFailure;
        pendingLauncherBootstrapState = null;
        pendingLauncherBootstrapFailure = null;
        applyLauncherBootstrapState(loaded, failure);
    }

    private void applyLauncherBootstrapState(
            @NonNull LauncherBootstrapState loaded,
            @Nullable RuntimeException migrationFailure) {
        if (migrationFailure != null) {
            // All migrations are idempotent. Keep HOME usable with the untouched preference graph;
            // WidgetService's background barrier can retry the same migration later.
            Log.w(TAG, "Launcher preference migration did not complete", migrationFailure);
        }
        layoutStore = loaded.layoutStore;
        globalElementLayoutStore = loaded.globalElementLayoutStore;
        backdropStore = loaded.backdropStore;
        horizontalGroupStore = loaded.horizontalGroupStore;
        panelElementStore = loaded.panelElementStore;
        actionsGridConfigStore = loaded.actionsGridConfigStore;
        navigationPanelConfigStore = loaded.navigationPanelConfigStore;
        favoriteAppsConfigStore = loaded.favoriteAppsConfigStore;
        favoriteRoutesConfigStore = loaded.favoriteRoutesConfigStore;
        vehicleInfoConfigStore = loaded.vehicleInfoConfigStore;
        informationConfigStore = loaded.informationConfigStore;
        inflateEditorChromeAfterFirstDraw();
        launcherBootstrapReady = true;
        applyConfiguredWindowMode();
        if (launcherRoot != null) launcherRoot.setBackground(buildBackground());
        handleStagedOrHomeNavigation(getIntent());
        requestLauncherSafeAreaRefresh();
        scheduleInitialLauncherBackdrops();
        StartupPerformanceTrace.mark("launcher_preferences_ready");
        if (!activityStarted) return;
        startImmediateHomeRuntime();
        initializePanels();
        scheduleDeferredLauncherRuntimeStart();
    }

    /** Hidden Material editor controls are not part of the normal HOME first frame. */
    private void inflateEditorChromeAfterFirstDraw() {
        if (launcherRoot == null || editorGrid != null) return;
        editorGrid = new LauncherGridView(this);
        editorGrid.setStepPx(preferences.launcherSnapPx.get());
        editorGrid.setVisibility(View.GONE);
        launcherRoot.addView(editorGrid, 0, match());

        doneButton = new MaterialButton(this);
        doneButton.setText("Готово · закрепить компоновку");
        doneButton.setOnClickListener(v -> finishActiveEditor());
        doneButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams doneLp = new FrameLayout.LayoutParams(dp(420), dp(56),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        doneLp.topMargin = dp(12);
        launcherRoot.addView(doneButton, doneLp);

        widgetCatalogButton = new MaterialButton(this);
        widgetCatalogButton.setText("＋ Виджет");
        widgetCatalogButton.setAllCaps(false);
        widgetCatalogButton.setOnClickListener(view -> showLauncherWidgetCatalog());
        widgetCatalogButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams catalogLp = new FrameLayout.LayoutParams(
                dp(220), dp(56), Gravity.TOP | Gravity.END);
        catalogLp.topMargin = dp(12);
        catalogLp.rightMargin = dp(18);
        launcherRoot.addView(widgetCatalogButton, catalogLp);
    }

    private void applyConfiguredWindowMode() {
        if (preferences.launcherImmersive.get()) {
            applyImmersive();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        getWindow().getDecorView().requestApplyInsets();
    }

    private void scheduleRuntimeBindingProbe() {
        if (!activityStarted || runtimeBindingProbePosted) return;
        runtimeBindingProbePosted = true;
        navigationUiHandler.post(ensureSmartHomeValueSubscription);
    }

    private void requestLauncherSafeAreaRefresh() {
        if (!activityStarted || !launcherBootstrapReady || safeAreaRefreshPosted) return;
        safeAreaRefreshPosted = true;
        navigationUiHandler.post(safeAreaRefresh);
    }

    private void requestGlobalElementRefresh() {
        if (!activityStarted || !globalElementsActivated || globalElementRefreshPosted) return;
        globalElementRefreshPosted = true;
        navigationUiHandler.post(globalElementRefresh);
    }

    private void onWorkspaceDescendantInvalidated(@NonNull View target) {
        if (!globalElementsActivated) return;
        for (View source : globalElementSources.values()) {
            if (isSelfOrDescendant(target, source)) {
                requestGlobalElementRefresh();
                return;
            }
        }
    }

    private static boolean isSelfOrDescendant(@NonNull View candidate,
                                               @NonNull View ancestor) {
        View current = candidate;
        while (true) {
            if (current == ancestor) return true;
            android.view.ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return false;
            current = (View) parent;
        }
    }

    private static final class LauncherBootstrapState {
        final LauncherLayoutStore layoutStore;
        final LauncherGlobalElementLayoutStore globalElementLayoutStore;
        final LauncherBackdropStore backdropStore;
        final LauncherHorizontalGroupStore horizontalGroupStore;
        final PanelElementConfigStore panelElementStore;
        final LauncherActionsGridConfigStore actionsGridConfigStore;
        final NavigationPanelConfigStore navigationPanelConfigStore;
        final FavoriteAppsConfigStore favoriteAppsConfigStore;
        final FavoriteRoutesConfigStore favoriteRoutesConfigStore;
        final VehicleInfoPanelConfigStore vehicleInfoConfigStore;
        final InformationPanelConfigStore informationConfigStore;

        LauncherBootstrapState(@NonNull Preferences preferences) {
            layoutStore = new LauncherLayoutStore(preferences);
            globalElementLayoutStore = new LauncherGlobalElementLayoutStore(preferences);
            backdropStore = new LauncherBackdropStore(preferences);
            horizontalGroupStore = new LauncherHorizontalGroupStore(preferences);
            panelElementStore = new PanelElementConfigStore(preferences);
            actionsGridConfigStore = new LauncherActionsGridConfigStore(preferences);
            navigationPanelConfigStore = new NavigationPanelConfigStore(preferences);
            favoriteAppsConfigStore = new FavoriteAppsConfigStore(preferences);
            favoriteRoutesConfigStore = new FavoriteRoutesConfigStore(preferences);
            vehicleInfoConfigStore = new VehicleInfoPanelConfigStore(preferences);
            informationConfigStore = new InformationPanelConfigStore(preferences);
        }
    }

    private void scheduleDeferredLauncherRuntimeStart() {
        navigationUiHandler.removeCallbacks(deferredLauncherRuntimeStart);
        navigationUiHandler.removeCallbacks(deferredLauncherRuntimeStep);
        if (!launcherBootstrapReady || !activityStarted) return;
        navigationUiHandler.post(deferredLauncherRuntimeStart);
    }

    private void scheduleVisibleIntegrationHostHandoff() {
        navigationUiHandler.removeCallbacks(visibleIntegrationHostHandoff);
        if (!activityStarted) return;
        long delay = StartupWorkCoordinator.pendingIntegrationHostDelayMillis(this);
        if (delay >= 0L) {
            navigationUiHandler.postDelayed(visibleIntegrationHostHandoff, delay);
        }
    }

    /** Issues all startup work immediately; only optional UI attachment waits for shell draw. */
    private void startImmediateHomeRuntime() {
        if (!activityStarted || isFinishing() || isDestroyed()) return;
        if (!ecarxPhoneWakeSentForStart) {
            ecarxPhoneWakeSentForStart = true;
            EcarxBtPhoneBridge.onLauncherVisible(this);
        }
        WidgetServiceStarter.startVisibleSurfaceImmediatelyAutomatically(this);
        scheduleRuntimeBindingProbe();
        if (!launcherBootstrapReady) {
            scheduleVisibleIntegrationHostHandoff();
            return;
        }
        registerNavigationReceiver();
        registerAllAppsUninstallReceiver();
        requestGlobalElementRefresh();
        scheduleRuntimeBindingProbe();
        if (!panelsInitialized && !panelsInitializing) initializePanels();
        // A running QuickBoot survivor still owns credential/surface reconciliation. Pending
        // generation state, not WidgetService.isRunning(), decides whether this handoff is needed.
        scheduleVisibleIntegrationHostHandoff();
    }

    private boolean launcherRuntimeStageReached(int completedStages) {
        return deferredLauncherRuntimeStarted
                && deferredLauncherRuntimeStage >= completedStages;
    }

    @Override
    protected void onDestroy() {
        dismissAllAppsDialog();
        launcherBootstrapGeneration++;
        panelInitializationGeneration++;
        carIntegrationLoadGeneration++;
        pendingLauncherBootstrapState = null;
        pendingLauncherBootstrapFailure = null;
        if (workspace != null) workspace.setDescendantInvalidationListener(null);
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
        if (!launcherBootstrapReady) {
            getWindow().getDecorView().requestApplyInsets();
            return;
        }
        if (preferences.launcherImmersive.get()) {
            applyImmersive();
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        getWindow().getDecorView().requestApplyInsets();
        updateLauncherSafeArea();
        if (shortcutStore != null) shortcutStore.load();
        applyLauncherPreferences();
        if (appCatalog != null) reloadAppCatalogAsync(false);
        if (allAppsUninstallInProgress) finishAllAppsUninstallFlow();
        if (shortcutStore != null) {
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

    private void registerAllAppsUninstallReceiver() {
        if (allAppsUninstallReceiverRegistered) return;
        try {
            ContextCompat.registerReceiver(this, allAppsUninstallReceiver,
                    new IntentFilter(AppUninstallLauncher.ACTION_FINISHED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            allAppsUninstallReceiverRegistered = true;
        } catch (RuntimeException failure) {
            allAppsUninstallReceiverRegistered = false;
            Log.w(TAG, "Could not register All Apps uninstall receiver", failure);
        }
    }

    private void unregisterAllAppsUninstallReceiver() {
        if (!allAppsUninstallReceiverRegistered) return;
        allAppsUninstallReceiverRegistered = false;
        try {
            unregisterReceiver(allAppsUninstallReceiver);
        } catch (RuntimeException failure) {
            Log.w(TAG, "All Apps uninstall receiver was already removed", failure);
        }
    }

    private void finishAllAppsUninstallFlow() {
        if (!allAppsUninstallInProgress) return;
        allAppsUninstallInProgress = false;
        setAllAppsConfirmationActive(false);
        lastAppCatalogLoadElapsed = 0L;
        if (appCatalog != null) reloadAppCatalogAsync(true);
        refreshAllAppsDrawerContents();
    }

    private void reconcileMediaController() {
        if (mediaController == null) return;
        boolean runtimeAllowed = launcherRuntimeStageReached(2) || mediaContentEditMode;
        boolean needed = activityStarted && runtimeAllowed
                && preferences.launcherMediaVisible.get()
                && hasMediaPanelContent();
        if (activityStarted && mediaContentEditMode && hasMediaPanelContent()) needed = true;
        if (needed) mediaController.start(); else mediaController.stop();
    }

    private void reloadAppCatalogAsync(boolean force) {
        if (appCatalogLoadInFlight || launcherWorker.isShutdown()) return;
        if (!preferences.launcherAppsVisible.get() && !editMode) return;
        if ((!launcherRuntimeStageReached(6) && !editMode)
                || StartupWorkCoordinator.remainingQuietMillis(this) > 0L) {
            // Broad PackageManager queries are one of the largest Binder bursts in the launcher.
            // The small saved HOME layout does not depend on them, so keep the boot lane quiet.
            scheduleDeferredLauncherRuntimeStart();
            return;
        }
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
                    refreshAllAppsDrawerContents();
                });
            });
        } catch (RejectedExecutionException failure) {
            appCatalogLoadInFlight = false;
        }
    }

    private void applyLauncherPreferences() {
        if (!panelsInitialized) return;
        reconcileGlobalElementLayoutPreference();
        scheduleInitialLauncherBackdrops();
        syncLauncherHorizontalGroups();
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
        setPanelVisibility(LauncherLayoutStore.ACTIONS, actionsContentEditMode
                || (preferences.launcherActionsVisible.get()
                && hasSimplePanelContent(LauncherLayoutStore.ACTIONS)));
        boolean climateVisible = preferences.launcherClimateVisible.get()
                && hasClimatePanelContent();
        setPanelVisibility(LauncherLayoutStore.CLIMATE, climateVisible);
        if (climatePanel != null) {
            climatePanel.reloadConfig();
            if (activityStarted && launcherRuntimeStageReached(3) && climateVisible) {
                climatePanel.start();
            }
            else climatePanel.stop();
        }
        if (mediaPanel != null) mediaPanel.reloadConfig();
        boolean vehicleInfoVisible = preferences.launcherVehicleInfoVisible.get();
        setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO, vehicleInfoVisible);
        if (vehicleInfoPanel != null) {
            vehicleInfoPanel.reloadConfig();
            if (activityStarted && launcherRuntimeStageReached(3) && vehicleInfoVisible) {
                vehicleInfoPanel.start();
            }
            else vehicleInfoPanel.stop();
        }
        boolean informationVisible = preferences.launcherInformationVisible.get();
        if (informationPanel != null) {
            informationPanel.reloadConfig();
            boolean hasInformation = informationPanel.hasConfiguredItems();
            setPanelVisibility(LauncherLayoutStore.INFORMATION,
                    informationVisible && (editMode || hasInformation));
            if (activityStarted && launcherRuntimeStageReached(3)
                    && informationVisible && hasInformation) {
                informationPanel.start();
            } else {
                informationPanel.stop();
            }
        }

        layoutStore.load(workspace.getWidth(), workspace.getHeight());
        applyStoredPanelGeometry();
        refreshSimplePanelContentsIfNeeded();
        syncGlobalElements();
        requestGlobalElementRefresh();
        updateNavigation();
        scheduleNavigationRefresh();
    }

    private void reconcileGlobalElementLayoutPreference() {
        if (!globalElementsActivated) return;
        String raw = preferences.launcherGlobalElementsJson.get();
        if (Objects.equals(appliedGlobalElementsJson, raw)) return;
        if (raw == null || raw.trim().isEmpty()) {
            Set<String> ids = new LinkedHashSet<>(globalElementFrames.keySet());
            ids.addAll(globalElementProxies.keySet());
            for (String id : ids) removeGlobalElement(id);
            globalElementSources.clear();
            globalElementsActivated = false;
            suppressSourcePanels();
            workspace.post(this::activateGlobalElements);
        } else {
            applyStoredGlobalGeometry();
        }
        appliedGlobalElementsJson = raw;
    }

    /** Applies inner-element edits after returning from the visual panel editor. */
    private void refreshSimplePanelContentsIfNeeded() {
        String raw = preferences.launcherPanelElementsJson.get();
        String navigationRaw = preferences.launcherNavigationConfigJson.get();
        String actionsGridRaw = preferences.launcherActionsGridJson.get();
        int appsColumns = preferences.launcherAppsColumns.get();
        int actionsColumns = preferences.launcherActionsColumns.get();
        boolean simpleChanged = !Objects.equals(appliedPanelElementsJson, raw)
                || appliedAppsColumns != appsColumns
                || appliedActionsColumns != actionsColumns;
        boolean navigationChanged = !Objects.equals(appliedNavigationConfigJson, navigationRaw);
        boolean actionsGridChanged = !Objects.equals(appliedActionsGridJson, actionsGridRaw);
        if (!simpleChanged && !navigationChanged && !actionsGridChanged) return;
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
        if (actionsGridChanged) {
            appliedActionsGridJson = actionsGridRaw;
            refreshShortcutGrid();
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
        } else if (actionsContentEditMode) {
            setActionsContentEditMode(false);
        } else if (editMode) {
            setEditMode(false);
        } else if (!isTaskRoot() || !homeRootInvocation) {
            super.onBackPressed();
        } else {
            // A launcher at the root of HOME consumes Back. Finishing it exposes the OEM's empty
            // application surface until another HOME intent happens to be delivered.
        }
    }

    private void configureWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
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
        launcherRoot = new FrameLayout(this);
        // A fixed drawable avoids the first SharedPreferences read (which can wait for disk) while
        // still producing a complete opaque HOME frame. The saved gradient replaces it after draw.
        launcherRoot.setBackground(buildShellBackground());

        workspace = new LauncherWorkspaceView(this);
        workspace.setClipChildren(false);
        // Layout mode is deliberately entered only from Settings or an explicit EDIT_HOME
        // launcher action. A long press on the wallpaper must never unexpectedly expose editor
        // chrome while driving.
        workspace.setLongClickable(false);
        launcherRoot.addView(workspace, match());
        return launcherRoot;
    }

    @NonNull
    private Drawable buildShellBackground() {
        return gradientBackground(Color.rgb(16, 24, 39));
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
        if (widgetCatalogButton != null) {
            FrameLayout.LayoutParams catalogParams =
                    (FrameLayout.LayoutParams) widgetCatalogButton.getLayoutParams();
            catalogParams.topMargin = safe.top + dp(12);
            catalogParams.rightMargin = safe.right + dp(18);
            catalogParams.bottomMargin = safe.bottom;
            widgetCatalogButton.setLayoutParams(catalogParams);
        }
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
        applyStoredGlobalGeometry();
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

    /** Activates independent screen-space placement without reparenting live integration views. */
    private void activateGlobalElements() {
        if (workspace == null || globalElementLayoutStore == null
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        if (!globalElementsActivated) {
            String raw = preferences.launcherGlobalElementsJson.get();
            if (!Objects.equals(appliedGlobalElementsJson, raw)) {
                globalElementLayoutStore.load(workspace.getWidth(), workspace.getHeight());
                appliedGlobalElementsJson = raw;
            }
            globalElementsActivated = true;
        }
        if (initialBackdropsSynchronized) syncLauncherBackdrops();
        else scheduleInitialLauncherBackdrops();
        syncGlobalElements();
        syncLauncherHorizontalGroups();
        suppressSourcePanels();
        refreshGlobalElementVisibility();
        appliedGlobalElementsJson = preferences.launcherGlobalElementsJson.get();
    }

    /** Parses the potentially 1 MiB decorative document off the first-frame/main-thread lane. */
    private void scheduleInitialLauncherBackdrops() {
        if (initialBackdropLoadInFlight || !launcherBootstrapReady
                || !launcherFirstDrawCompleted || !activityStarted
                || workspace == null || backdropStore == null
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0
                || launcherWorker.isShutdown()) return;
        final String raw = preferences.launcherBackdropsJson.get();
        if (initialBackdropsSynchronized
                && Objects.equals(appliedLauncherBackdropsJson, raw)) return;
        initialBackdropLoadInFlight = true;
        final int generation = ++initialBackdropLoadGeneration;
        final int width = workspace.getWidth();
        final int height = workspace.getHeight();
        try {
            launcherWorker.execute(() -> {
                LauncherBackdropStore loaded = new LauncherBackdropStore(preferences);
                loaded.load(width, height);
                loaded.all(); // Materialize defensive copies away from the UI lane.
                navigationUiHandler.post(() -> {
                    if (generation != initialBackdropLoadGeneration) return;
                    initialBackdropLoadInFlight = false;
                    if (!activityStarted || isFinishing() || isDestroyed()) return;
                    if (!Objects.equals(raw, preferences.launcherBackdropsJson.get())) {
                        scheduleInitialLauncherBackdrops();
                        return;
                    }
                    backdropStore = loaded;
                    appliedLauncherBackdropsJson = raw;
                    initialBackdropsSynchronized = true;
                    syncLauncherBackdrops();
                    StartupPerformanceTrace.mark("launcher_backdrops_ready");
                });
            });
        } catch (RuntimeException rejected) {
            initialBackdropLoadInFlight = false;
            Log.w(TAG, "Could not schedule launcher backdrop parsing", rejected);
        }
    }

    /** Keeps every decorative HOME surface below all live widgets and panel sources. */
    private void syncLauncherBackdrops() {
        if (workspace == null || backdropStore == null
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        String raw = preferences.launcherBackdropsJson.get();
        boolean reloaded = !Objects.equals(appliedLauncherBackdropsJson, raw);
        if (reloaded) {
            backdropStore.load(workspace.getWidth(), workspace.getHeight());
            appliedLauncherBackdropsJson = raw;
        }

        List<LauncherBackdropStore.Backdrop> values = backdropStore.all();
        Set<String> retained = new LinkedHashSet<>();
        for (LauncherBackdropStore.Backdrop value : values) retained.add(value.id);
        for (String stale : new ArrayList<>(backdropFrames.keySet())) {
            if (retained.contains(stale)) continue;
            LauncherElementFrame frame = backdropFrames.remove(stale);
            backdropViews.remove(stale);
            if (frame != null) workspace.removeView(frame);
        }

        int snap = Math.max(1, preferences.launcherSnapPx.get());
        int backdropIndex = 0;
        for (LauncherBackdropStore.Backdrop value : values) {
            LauncherElementFrame frame = backdropFrames.get(value.id);
            LauncherBackdropView surface = backdropViews.get(value.id);
            if (frame == null || surface == null) {
                String id = value.id;
                surface = new LauncherBackdropView(this, value);
                frame = new LauncherElementFrame(this, id, value.name,
                        (changedId, x, y, width, height) -> {
                            LauncherBackdropStore.Backdrop changed = backdropStore.get(changedId);
                            if (changed == null) return;
                            changed.x = x;
                            changed.y = y;
                            changed.width = width;
                            changed.height = height;
                            saveLauncherBackdrop(changed);
                        });
                frame.setMinimumGeometryPx(1, 1);
                frame.setCardBackgroundColor(Color.TRANSPARENT);
                frame.setCardElevation(0);
                frame.setRadius(0);
                frame.setPreserveAspectRatio(false);
                frame.setStayBehindSiblings(true);
                frame.setPassThroughTouchesOutsideEditMode(true);
                frame.setOnClickListener(view -> {
                    if (editMode) showLauncherBackdropEditor(id);
                });
                frame.setContent(surface);
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(value.width, value.height);
                params.leftMargin = value.x;
                params.topMargin = value.y;
                workspace.addView(frame, Math.min(backdropIndex, workspace.getChildCount()),
                        params);
                backdropFrames.put(id, frame);
                backdropViews.put(id, surface);
            } else {
                surface.setBackdrop(value);
                if (reloaded) {
                    FrameLayout.LayoutParams params =
                            (FrameLayout.LayoutParams) frame.getLayoutParams();
                    params.width = value.width;
                    params.height = value.height;
                    params.leftMargin = value.x;
                    params.topMargin = value.y;
                    frame.setLayoutParams(params);
                }
            }
            frame.setEditMode(editMode, snap);
            frame.setCardElevation(0);
            backdropIndex++;
        }
    }

    /**
     * Applies user-defined horizontal rows after atomic widgets exist. The row frame is visible
     * only in explicit layout mode; at runtime it contributes no surface and cannot consume taps.
     */
    private void syncLauncherHorizontalGroups() {
        if (workspace == null || horizontalGroupStore == null
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        String raw = preferences.launcherHorizontalGroupsJson.get();
        if (!Objects.equals(appliedLauncherHorizontalGroupsJson, raw)) {
            horizontalGroupStore.load(workspace.getWidth(), workspace.getHeight());
            appliedLauncherHorizontalGroupsJson = raw;
        }
        List<LauncherHorizontalGroupStore.Group> groups = horizontalGroupStore.all();
        Set<String> retained = new LinkedHashSet<>();
        for (LauncherHorizontalGroupStore.Group group : groups) retained.add(group.id);
        for (String stale : new ArrayList<>(horizontalGroupFrames.keySet())) {
            if (retained.contains(stale)) continue;
            LauncherElementFrame frame = horizontalGroupFrames.remove(stale);
            if (frame != null) workspace.removeView(frame);
        }

        int snap = Math.max(1, preferences.launcherSnapPx.get());
        for (LauncherHorizontalGroupStore.Group group : groups) {
            LauncherElementFrame frame = horizontalGroupFrames.get(group.id);
            if (frame == null) {
                String id = group.id;
                frame = new LauncherElementFrame(this, id, group.name,
                        (changedId, x, y, width, height) -> {
                            LauncherHorizontalGroupStore.Group changed =
                                    horizontalGroupStore.get(changedId);
                            if (changed == null) return;
                            changed.x = x;
                            changed.y = y;
                            changed.width = width;
                            changed.height = height;
                            saveLauncherHorizontalGroup(changed);
                        });
                frame.setMinimumGeometryPx(1, 1);
                frame.setCardBackgroundColor(Color.TRANSPARENT);
                frame.setCardElevation(0);
                frame.setRadius(0);
                frame.setPreserveAspectRatio(false);
                frame.setContent(new View(this));
                frame.setOnClickListener(view -> {
                    if (editMode) showLauncherHorizontalGroupEditor(id);
                });
                FrameLayout.LayoutParams params =
                        new FrameLayout.LayoutParams(group.width, group.height);
                params.leftMargin = group.x;
                params.topMargin = group.y;
                workspace.addView(frame, params);
                horizontalGroupFrames.put(group.id, frame);
            } else {
                FrameLayout.LayoutParams params =
                        (FrameLayout.LayoutParams) frame.getLayoutParams();
                params.width = group.width;
                params.height = group.height;
                params.leftMargin = group.x;
                params.topMargin = group.y;
                frame.setLayoutParams(params);
            }
            layoutLauncherHorizontalGroup(group);
            frame.setEditMode(editMode, snap);
            frame.setVisibility(editMode ? View.VISIBLE : View.GONE);
            if (editMode) frame.bringToFront();
        }
    }

    private void layoutLauncherHorizontalGroup(
            @NonNull LauncherHorizontalGroupStore.Group group) {
        List<LauncherElementFrame> members = new ArrayList<>();
        List<HorizontalGroupLayout.Size> sizes = new ArrayList<>();
        for (String id : group.memberIds) {
            LauncherElementFrame frame = globalElementFrames.get(id);
            if (frame == null) continue;
            LauncherGlobalElementLayoutStore.Geometry saved =
                    globalElementLayoutStore.get(id);
            int width = saved == null ? Math.max(1, frame.getWidth()) : saved.width;
            int height = saved == null ? Math.max(1, frame.getHeight()) : saved.height;
            members.add(frame);
            sizes.add(new HorizontalGroupLayout.Size(width, height));
        }
        List<HorizontalGroupLayout.Rect> placements = HorizontalGroupLayout.layout(
                group.x, group.y, group.width, group.height,
                group.paddingLeftPx, group.paddingTopPx,
                group.paddingRightPx, group.paddingBottomPx,
                group.gapPx, group.horizontalAlignment, group.verticalAlignment,
                group.distribution, sizes);
        for (int index = 0; index < members.size() && index < placements.size(); index++) {
            LauncherElementFrame member = members.get(index);
            HorizontalGroupLayout.Rect placement = placements.get(index);
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) member.getLayoutParams();
            params.leftMargin = placement.x;
            params.topMargin = placement.y;
            params.width = placement.width;
            params.height = placement.height;
            member.setLayoutParams(params);
        }
    }

    private void saveLauncherHorizontalGroup(
            @NonNull LauncherHorizontalGroupStore.Group group) {
        horizontalGroupStore.put(group);
        appliedLauncherHorizontalGroupsJson =
                preferences.launcherHorizontalGroupsJson.get();
        syncLauncherHorizontalGroups();
    }

    private boolean isLauncherWidgetGrouped(@NonNull String id) {
        return horizontalGroupStore != null && horizontalGroupStore.containsMember(id);
    }

    private void syncGlobalElements() {
        if (!globalElementsActivated || workspace == null) return;
        LinkedHashMap<String, View> discovered = new LinkedHashMap<>();
        LinkedHashMap<String, LauncherGlobalElementTag> tags = new LinkedHashMap<>();
        for (LauncherElementFrame panel : panels.values()) {
            collectGlobalElements(panel, discovered, tags);
        }

        Set<String> staleIds = new LinkedHashSet<>(globalElementFrames.keySet());
        staleIds.addAll(globalElementProxies.keySet());
        staleIds.removeAll(discovered.keySet());
        for (String staleId : staleIds) removeGlobalElement(staleId);

        globalElementSources.clear();
        globalElementSources.putAll(discovered);

        int snap = Math.max(1, preferences.launcherSnapPx.get());
        for (Map.Entry<String, View> entry : discovered.entrySet()) {
            String id = entry.getKey();
            View source = entry.getValue();
            LauncherElementFrame existing = globalElementFrames.get(id);
            if (existing == null) {
                LauncherGlobalElementLayoutStore.Geometry geometry =
                        globalElementLayoutStore.get(id);
                if (geometry == null) geometry = migrateSourceGeometry(source);
                if (geometry == null) continue;
                LauncherGlobalElementTag tag = tags.get(id);
                String label = tag == null ? id : tag.label;
                LauncherGlobalElementLayoutStore.Appearance appearance =
                        globalElementLayoutStore.getAppearance(id);
                LauncherGlobalElementProxyView proxy =
                        new LauncherGlobalElementProxyView(this,
                                () -> globalElementSources.get(id),
                                appearance);
                LauncherElementFrame frame = new LauncherElementFrame(this, id, label,
                        (changedId, x, y, width, height) ->
                                globalElementLayoutStore.put(changedId,
                                        new LauncherGlobalElementLayoutStore.Geometry(
                                                x, y, width, height)));
                frame.setMinimumGeometryPx(1, 1);
                frame.setCardBackgroundColor(Color.TRANSPARENT);
                frame.setCardElevation(0);
                frame.setRadius(0);
                frame.setPreserveAspectRatio(appearance.preserveAspectRatio);
                frame.setOnClickListener(view -> {
                    if (editMode) showLauncherWidgetEditor(id, label);
                });
                frame.setContent(proxy);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        geometry.width, geometry.height);
                params.leftMargin = geometry.x;
                params.topMargin = geometry.y;
                workspace.addView(frame, params);
                frame.setEditMode(editMode, snap);
                globalElementFrames.put(id, frame);
                globalElementProxies.put(id, proxy);
            } else {
                LauncherGlobalElementProxyView proxy = globalElementProxies.get(id);
                if (proxy != null) proxy.onSourceRebound();
            }
        }
        // A temporarily empty source (notably the asynchronous applications GridView) is still a
        // backing hierarchy, never presentation. It must not become visible while awaiting tags.
        suppressSourcePanels();
    }

    private void removeGlobalElement(@NonNull String id) {
        LauncherGlobalElementProxyView proxy = globalElementProxies.remove(id);
        if (proxy != null) proxy.dispose();
        LauncherElementFrame frame = globalElementFrames.remove(id);
        if (frame != null && workspace != null) workspace.removeView(frame);
        globalElementSources.remove(id);
    }

    private void suppressSourcePanels() {
        String sourceEditorPanel = activeContentEditorPanelId();
        for (Map.Entry<String, LauncherElementFrame> entry : panels.entrySet()) {
            boolean editorOwnsPanel = entry.getKey().equals(sourceEditorPanel);
            LauncherElementFrame panel = entry.getValue();
            panel.setAlpha(editorOwnsPanel ? 1f : 0f);
            panel.setContentTouchBlocked(!editorOwnsPanel);
            if (editorOwnsPanel) panel.bringToFront();
        }
    }

    @Nullable
    private String activeContentEditorPanelId() {
        if (mediaContentEditMode) return LauncherLayoutStore.MEDIA;
        if (navigationContentEditMode) return LauncherLayoutStore.NAVIGATION;
        if (actionsContentEditMode) return LauncherLayoutStore.ACTIONS;
        return null;
    }

    private void collectGlobalElements(
            @NonNull View current,
            @NonNull Map<String, View> views,
            @NonNull Map<String, LauncherGlobalElementTag> tags) {
        LauncherGlobalElementTag tag = LauncherGlobalElementTag.from(current);
        if (tag != null) {
            // Stable IDs are unique. A newly rebuilt view intentionally replaces its detached
            // predecessor while retaining the same saved global rectangle.
            views.put(tag.id, current);
            tags.put(tag.id, tag);
        }
        if (!(current instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) current;
        for (int index = 0; index < group.getChildCount(); index++) {
            collectGlobalElements(group.getChildAt(index), views, tags);
        }
    }

    @Nullable
    private LauncherGlobalElementLayoutStore.Geometry migrateSourceGeometry(
            @NonNull View source) {
        int width = Math.max(source.getWidth(), source.getMeasuredWidth());
        int height = Math.max(source.getHeight(), source.getMeasuredHeight());
        if (width <= 0 || height <= 0) return null;
        int[] sourceLocation = new int[2];
        int[] workspaceLocation = new int[2];
        source.getLocationOnScreen(sourceLocation);
        workspace.getLocationOnScreen(workspaceLocation);
        LauncherGlobalElementLayoutStore.Geometry geometry =
                new LauncherGlobalElementLayoutStore.Geometry(
                        sourceLocation[0] - workspaceLocation[0],
                        sourceLocation[1] - workspaceLocation[1],
                        width, height);
        globalElementLayoutStore.put(
                LauncherGlobalElementTag.from(source).id, geometry);
        return globalElementLayoutStore.get(LauncherGlobalElementTag.from(source).id);
    }

    private void refreshGlobalElementVisibility() {
        if (!globalElementsActivated) return;
        int snap = Math.max(1, preferences.launcherSnapPx.get());
        String sourceEditorPanel = activeContentEditorPanelId();
        for (Map.Entry<String, LauncherElementFrame> entry
                : globalElementFrames.entrySet()) {
            LauncherGlobalElementProxyView proxy = globalElementProxies.get(entry.getKey());
            View source = globalElementSources.get(entry.getKey());
            LauncherGlobalElementLayoutStore.Appearance appearance =
                    globalElementLayoutStore.getAppearance(entry.getKey());
            boolean sourceEditorOwnsElement = sourceEditorPanel != null
                    && entry.getKey().startsWith(sourceEditorPanel + "/");
            boolean visible = !sourceEditorOwnsElement && source != null
                    && !appearance.hidden
                    && (editMode || proxy != null && proxy.sourceIsShown());
            entry.getValue().setVisibility(visible ? View.VISIBLE : View.GONE);
            entry.getValue().setEditMode(
                    editMode && !isLauncherWidgetGrouped(entry.getKey()), snap);
            if (proxy != null && visible) proxy.refreshFromSource();
        }
    }

    private void applyStoredGlobalGeometry() {
        if (!globalElementsActivated || globalElementLayoutStore == null) return;
        globalElementLayoutStore.load(workspace.getWidth(), workspace.getHeight());
        for (Map.Entry<String, LauncherElementFrame> entry
                : globalElementFrames.entrySet()) {
            LauncherGlobalElementLayoutStore.Geometry geometry =
                    globalElementLayoutStore.get(entry.getKey());
            if (geometry == null) continue;
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) entry.getValue().getLayoutParams();
            params.width = geometry.width;
            params.height = geometry.height;
            params.leftMargin = geometry.x;
            params.topMargin = geometry.y;
            entry.getValue().setLayoutParams(params);
        }
    }

    /**
     * Deep editor for one atomic HOME widget. It is opened by a normal tap on the selected frame
     * while explicit layout mode is active and writes through the same store as drag/resize, so
     * no panel-local editor can subsequently overwrite its geometry or appearance.
     */
    private void showLauncherWidgetEditor(@NonNull String id, @NonNull String label) {
        if (globalElementLayoutStore == null || isFinishing() || isDestroyed()) return;
        LauncherGlobalElementLayoutStore.Appearance appearance =
                globalElementLayoutStore.getAppearance(id);
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(28));
        scroll.addView(form, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text(13, Color.LTGRAY, false);
        hint.setText("Размер и положение принадлежат только этому виджету. "
                + "Режим «Вписать» показывает содержимое целиком и не деформирует его.");
        hint.setPadding(0, 0, 0, dp(8));
        form.addView(hint);

        if (hasLauncherWidgetDetailEditor(id)) {
            MaterialButton details = widgetEditorButton(
                    "Дополнительные настройки содержимого");
            details.setOnClickListener(view -> openLauncherWidgetDetailEditor(id));
            form.addView(details, widgetEditorRow());
        }

        if ((LauncherLayoutStore.MEDIA + "/" + MediaPanelConfig.PROGRESS).equals(id)) {
            TextView progressSettings = text(17, Color.WHITE, true);
            progressSettings.setText("Полоса прогресса");
            form.addView(progressSettings, widgetEditorSection());
            MediaPanelConfig mediaConfig = new MediaPanelConfigStore(preferences).load();
            addWidgetEditorSlider(form, "Толщина полосы", 2, 40,
                    mediaConfig.element(MediaPanelConfig.PROGRESS).progressBarHeightDp,
                    " dp", value -> {
                        MediaPanelConfig updated =
                                new MediaPanelConfigStore(preferences).load();
                        updated.setProgressBarHeightDp(value);
                        new MediaPanelConfigStore(preferences).save(updated);
                        if (mediaPanel != null) mediaPanel.reloadConfig();
                        workspace.post(() -> {
                            syncGlobalElements();
                            requestGlobalElementRefresh();
                        });
                    });
        }

        MaterialSwitch preserve = new MaterialSwitch(this);
        preserve.setText("Сохранять пропорции при изменении размера");
        preserve.setChecked(appearance.preserveAspectRatio);
        preserve.setOnCheckedChangeListener((button, checked) -> {
            appearance.preserveAspectRatio = checked;
            saveLauncherWidgetAppearance(id, appearance);
        });
        form.addView(preserve, widgetEditorRow());

        MaterialButton scaleMode = widgetEditorButton("Масштабирование: "
                + widgetScaleModeLabel(appearance.scaleMode));
        scaleMode.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Масштабирование содержимого")
                .setItems(new String[]{"Вписать целиком", "Заполнить с обрезкой",
                        "Растянуть (может исказить)"}, (dialog, which) -> {
                    appearance.scaleMode = which == 0
                            ? LauncherGlobalElementLayoutStore.ScaleMode.FIT
                            : which == 1
                            ? LauncherGlobalElementLayoutStore.ScaleMode.CROP
                            : LauncherGlobalElementLayoutStore.ScaleMode.STRETCH;
                    scaleMode.setText("Масштабирование: "
                            + widgetScaleModeLabel(appearance.scaleMode));
                    saveLauncherWidgetAppearance(id, appearance);
                })
                .show());
        form.addView(scaleMode, widgetEditorRow());

        TextView typography = text(17, Color.WHITE, true);
        typography.setText("Текст и иконка");
        form.addView(typography, widgetEditorSection());
        addWidgetEditorSlider(form, "Размер текста · 0 = исходный",
                0, 120, appearance.textSizeSp, " sp", value -> {
                    appearance.textSizeSp = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });

        MaterialButton font = widgetEditorButton("Шрифт: "
                + widgetFontLabel(appearance.fontFamily));
        font.setOnClickListener(view -> {
            String[] labels = new String[Fonts.ALL.size() + 1];
            labels[0] = "Исходный шрифт элемента";
            for (int index = 0; index < Fonts.ALL.size(); index++) {
                labels[index + 1] = getString(Fonts.ALL.get(index).labelRes);
            }
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Шрифт виджета")
                    .setItems(labels, (dialog, which) -> {
                        appearance.fontFamily = which == 0
                                ? "" : Fonts.ALL.get(which - 1).key;
                        font.setText("Шрифт: " + widgetFontLabel(appearance.fontFamily));
                        saveLauncherWidgetAppearance(id, appearance);
                    })
                    .show();
        });
        form.addView(font, widgetEditorRow());

        MaterialSwitch bold = new MaterialSwitch(this);
        bold.setText("Жирный текст");
        bold.setChecked(appearance.textBold);
        bold.setOnCheckedChangeListener((button, checked) -> {
            appearance.textBold = checked;
            saveLauncherWidgetAppearance(id, appearance);
        });
        form.addView(bold, widgetEditorRow());

        MaterialSwitch italic = new MaterialSwitch(this);
        italic.setText("Курсив");
        italic.setChecked(appearance.textItalic);
        italic.setOnCheckedChangeListener((button, checked) -> {
            appearance.textItalic = checked;
            saveLauncherWidgetAppearance(id, appearance);
        });
        form.addView(italic, widgetEditorRow());

        addWidgetColorButton(form, "Цвет текста", appearance.textColor,
                value -> {
                    appearance.textColor = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });
        addWidgetColorButton(form, "Цвет иконки", appearance.iconColor,
                value -> {
                    appearance.iconColor = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });

        MaterialButton horizontal = widgetEditorButton("По горизонтали: "
                + widgetHorizontalLabel(appearance.horizontalAlignment));
        horizontal.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Выравнивание по горизонтали")
                .setItems(new String[]{"Как в элементе", "Слева", "По центру", "Справа"},
                        (dialog, which) -> {
                            appearance.horizontalAlignment = which - 1;
                            horizontal.setText("По горизонтали: "
                                    + widgetHorizontalLabel(
                                    appearance.horizontalAlignment));
                            saveLauncherWidgetAppearance(id, appearance);
                        })
                .show());
        form.addView(horizontal, widgetEditorRow());

        MaterialButton vertical = widgetEditorButton("По вертикали: "
                + widgetVerticalLabel(appearance.verticalAlignment));
        vertical.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Выравнивание по вертикали")
                .setItems(new String[]{"Как в элементе", "Сверху", "По центру", "Снизу"},
                        (dialog, which) -> {
                            appearance.verticalAlignment = which - 1;
                            vertical.setText("По вертикали: "
                                    + widgetVerticalLabel(appearance.verticalAlignment));
                            saveLauncherWidgetAppearance(id, appearance);
                        })
                .show());
        form.addView(vertical, widgetEditorRow());

        TextView spacing = text(17, Color.WHITE, true);
        spacing.setText("Отступы со всех сторон");
        form.addView(spacing, widgetEditorSection());
        addWidgetEditorSlider(form, "Слева", 0, 160,
                appearance.paddingLeftPx, " px", value -> {
                    appearance.paddingLeftPx = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });
        addWidgetEditorSlider(form, "Сверху", 0, 160,
                appearance.paddingTopPx, " px", value -> {
                    appearance.paddingTopPx = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });
        addWidgetEditorSlider(form, "Справа", 0, 160,
                appearance.paddingRightPx, " px", value -> {
                    appearance.paddingRightPx = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });
        addWidgetEditorSlider(form, "Снизу", 0, 160,
                appearance.paddingBottomPx, " px", value -> {
                    appearance.paddingBottomPx = value;
                    saveLauncherWidgetAppearance(id, appearance);
                });

        TextView behavior = text(17, Color.WHITE, true);
        behavior.setText("Поведение при нажатии");
        form.addView(behavior, widgetEditorSection());
        MaterialButton tap = widgetEditorButton("Нажатие: "
                + widgetTapActionLabel(appearance));
        tap.setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Действие виджета")
                .setItems(new String[]{"Исходное действие", "Без действия",
                        "Открыть приложение…"}, (dialog, which) -> {
                    if (which == 0) {
                        appearance.tapAction =
                                LauncherGlobalElementLayoutStore.TapAction.INHERIT;
                        appearance.appComponent = "";
                        tap.setText("Нажатие: " + widgetTapActionLabel(appearance));
                        saveLauncherWidgetAppearance(id, appearance);
                    } else if (which == 1) {
                        appearance.tapAction =
                                LauncherGlobalElementLayoutStore.TapAction.NONE;
                        appearance.appComponent = "";
                        tap.setText("Нажатие: " + widgetTapActionLabel(appearance));
                        saveLauncherWidgetAppearance(id, appearance);
                    } else {
                        chooseLauncherWidgetApp(id, appearance, tap);
                    }
                })
                .show());
        form.addView(tap, widgetEditorRow());

        androidx.appcompat.app.AlertDialog editor =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Виджет · " + label)
                        .setView(scroll)
                        .setPositiveButton("Готово", null)
                        .setNeutralButton("Сбросить", null)
                        .setNegativeButton("Удалить", null)
                        .create();
        editor.setOnShowListener(ignored -> {
            editor.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Сбросить настройки виджета?")
                            .setMessage("Положение и размер сохранятся; оформление и действие "
                                    + "вернутся к исходным.")
                            .setPositiveButton("Сбросить", (dialog, which) -> {
                                saveLauncherWidgetAppearance(id,
                                        new LauncherGlobalElementLayoutStore.Appearance());
                                editor.dismiss();
                            })
                            .setNegativeButton("Отмена", null)
                            .show());
            editor.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(view -> new androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Удалить виджет с HOME?")
                            .setMessage("Его настройки и положение сохранятся. Вернуть виджет "
                                    + "можно кнопкой «＋ Виджет».")
                            .setPositiveButton("Удалить", (dialog, which) -> {
                                appearance.hidden = true;
                                saveLauncherWidgetAppearance(id, appearance);
                                refreshGlobalElementVisibility();
                                editor.dismiss();
                            })
                            .setNegativeButton("Отмена", null)
                            .show());
        });
        editor.show();
    }

    private boolean hasLauncherWidgetDetailEditor(@NonNull String id) {
        return id.startsWith(LauncherLayoutStore.APPS + "/")
                || id.startsWith(LauncherLayoutStore.ACTIONS + "/")
                || id.startsWith(LauncherLayoutStore.MEDIA + "/")
                || id.startsWith(LauncherLayoutStore.NAVIGATION + "/")
                || id.startsWith(LauncherLayoutStore.CLIMATE + "/")
                || id.startsWith(LauncherLayoutStore.VEHICLE_INFO + "/")
                || id.startsWith(LauncherLayoutStore.INFORMATION + "/");
    }

    /**
     * Legacy feature-rich screens survive only as contextual editors reached from a concrete
     * widget. They are intentionally absent from the top-level flat Launcher settings catalog.
     */
    private void openLauncherWidgetDetailEditor(@NonNull String id) {
        Intent details;
        if (id.startsWith(LauncherLayoutStore.APPS + "/")) {
            details = new Intent(this, FavoriteAppsSettingsActivity.class);
        } else if (id.startsWith(LauncherLayoutStore.ACTIONS + "/")) {
            details = new Intent(this, LauncherShortcutSettingsActivity.class);
        } else if (id.startsWith(LauncherLayoutStore.MEDIA + "/")) {
            details = new Intent(this, MediaPanelSettingsActivity.class);
        } else if (id.startsWith(
                LauncherLayoutStore.NAVIGATION + "/favorite_route_")) {
            details = new Intent(this, FavoriteRoutesSettingsActivity.class);
        } else if (id.startsWith(LauncherLayoutStore.NAVIGATION + "/")) {
            details = new Intent(this, NavigationPanelSettingsActivity.class);
        } else if (id.startsWith(LauncherLayoutStore.CLIMATE + "/")) {
            details = new Intent(this, ClimatePanelSettingsActivity.class)
                    .putExtra(ClimatePanelSettingsActivity.EXTRA_LAUNCHER_ONLY, true);
        } else if (id.startsWith(LauncherLayoutStore.VEHICLE_INFO + "/")) {
            details = new Intent(this, VehicleInfoPanelSettingsActivity.class);
        } else if (id.startsWith(LauncherLayoutStore.INFORMATION + "/")) {
            details = new Intent(this, InformationPanelSettingsActivity.class);
        } else {
            return;
        }
        startActivity(details);
    }

    private void saveLauncherWidgetAppearance(
            @NonNull String id,
            @NonNull LauncherGlobalElementLayoutStore.Appearance appearance) {
        globalElementLayoutStore.putAppearance(id, appearance);
        LauncherGlobalElementProxyView proxy = globalElementProxies.get(id);
        if (proxy != null) proxy.setAppearance(appearance);
        LauncherElementFrame frame = globalElementFrames.get(id);
        if (frame != null) {
            frame.setPreserveAspectRatio(appearance.preserveAspectRatio);
        }
    }

    private void createLauncherBackdrop() {
        if (backdropStore == null || workspace == null) return;
        syncLauncherBackdrops();
        LauncherBackdropStore.Backdrop value = backdropStore.create();
        appliedLauncherBackdropsJson = preferences.launcherBackdropsJson.get();
        syncLauncherBackdrops();
        showLauncherBackdropEditor(value.id);
    }

    private void saveLauncherBackdrop(@NonNull LauncherBackdropStore.Backdrop value) {
        if (backdropStore == null) return;
        backdropStore.put(value);
        appliedLauncherBackdropsJson = preferences.launcherBackdropsJson.get();
        LauncherBackdropStore.Backdrop normalized = backdropStore.get(value.id);
        LauncherBackdropView surface = backdropViews.get(value.id);
        if (normalized != null && surface != null) surface.setBackdrop(normalized);
    }

    private void showLauncherBackdropEditor(@NonNull String id) {
        if (backdropStore == null || isFinishing() || isDestroyed()) return;
        LauncherBackdropStore.Backdrop value = backdropStore.get(id);
        if (value == null) return;

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(32));
        scroll.addView(form, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text(13, Color.LTGRAY, false);
        hint.setText("Подложка — отдельный свободно масштабируемый слой. "
                + "Она всегда остаётся ниже всех виджетов.");
        form.addView(hint);

        TextView fill = text(17, Color.WHITE, true);
        fill.setText("Заливка");
        form.addView(fill, widgetEditorSection());
        addWidgetColorButton(form, "Цвет подложки", value.fillColor, selected -> {
            value.fillColor = selected;
            saveLauncherBackdrop(value);
        });
        addWidgetEditorSlider(form, "Непрозрачность", 0, 100,
                value.fillOpacityPercent, " %", selected -> {
                    value.fillOpacityPercent = selected;
                    saveLauncherBackdrop(value);
                });
        addWidgetEditorSlider(form, "Скругление", 0, 300,
                value.cornerRadiusPx, " px", selected -> {
                    value.cornerRadiusPx = selected;
                    saveLauncherBackdrop(value);
                });

        TextView border = text(17, Color.WHITE, true);
        border.setText("Рамка");
        form.addView(border, widgetEditorSection());
        addWidgetColorButton(form, "Цвет рамки", value.borderColor, selected -> {
            value.borderColor = selected;
            saveLauncherBackdrop(value);
        });
        addWidgetEditorSlider(form, "Непрозрачность рамки", 0, 100,
                value.borderOpacityPercent, " %", selected -> {
                    value.borderOpacityPercent = selected;
                    saveLauncherBackdrop(value);
                });
        addWidgetEditorSlider(form, "Толщина рамки", 0, 80,
                value.borderWidthPx, " px", selected -> {
                    value.borderWidthPx = selected;
                    saveLauncherBackdrop(value);
                });

        TextView shadow = text(17, Color.WHITE, true);
        shadow.setText("Тень · только HOME");
        form.addView(shadow, widgetEditorSection());
        addWidgetColorButton(form, "Цвет тени", value.shadowColor, selected -> {
            value.shadowColor = selected;
            saveLauncherBackdrop(value);
        });
        addWidgetEditorSlider(form, "Непрозрачность тени", 0, 100,
                value.shadowOpacityPercent, " %", selected -> {
                    value.shadowOpacityPercent = selected;
                    saveLauncherBackdrop(value);
                });
        addWidgetEditorSlider(form, "Размытие тени", 0, 160,
                value.shadowRadiusPx, " px", selected -> {
                    value.shadowRadiusPx = selected;
                    saveLauncherBackdrop(value);
                });
        addWidgetEditorSlider(form, "Смещение тени X", -120, 120,
                value.shadowOffsetXPx, " px", selected -> {
                    value.shadowOffsetXPx = selected;
                    saveLauncherBackdrop(value);
                });
        addWidgetEditorSlider(form, "Смещение тени Y", -120, 120,
                value.shadowOffsetYPx, " px", selected -> {
                    value.shadowOffsetYPx = selected;
                    saveLauncherBackdrop(value);
                });

        androidx.appcompat.app.AlertDialog editor =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(value.name)
                        .setView(scroll)
                        .setPositiveButton("Готово", null)
                        .setNegativeButton("Удалить", null)
                        .create();
        editor.setOnShowListener(ignored ->
                editor.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                        .setOnClickListener(view ->
                                new androidx.appcompat.app.AlertDialog.Builder(this)
                                        .setTitle("Удалить подложку?")
                                        .setMessage("Подложка будет удалена, виджеты не изменятся.")
                                        .setPositiveButton("Удалить", (dialog, which) -> {
                                            backdropStore.remove(id);
                                            appliedLauncherBackdropsJson =
                                                    preferences.launcherBackdropsJson.get();
                                            LauncherElementFrame frame =
                                                    backdropFrames.remove(id);
                                            backdropViews.remove(id);
                                            if (frame != null) workspace.removeView(frame);
                                            editor.dismiss();
                                        })
                                        .setNegativeButton("Отмена", null)
                                        .show()));
        editor.show();
    }

    private void createLauncherHorizontalGroup() {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String id : globalElementFrames.keySet()) {
            if (globalElementLayoutStore.getAppearance(id).hidden) continue;
            ids.add(id);
            labels.add(launcherWidgetLabel(id));
        }
        if (ids.size() < 2) {
            Toast.makeText(this, "Для ряда нужны как минимум два виджета",
                    Toast.LENGTH_LONG).show();
            return;
        }
        boolean[] selected = new boolean[ids.size()];
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Горизонтальный ряд")
                        .setMultiChoiceItems(labels.toArray(new String[0]), selected,
                                (value, which, checked) -> selected[which] = checked)
                        .setPositiveButton("Создать", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            List<String> members = new ArrayList<>();
                            int left = Integer.MAX_VALUE;
                            int top = Integer.MAX_VALUE;
                            int right = 0;
                            int bottom = 0;
                            for (int index = 0; index < selected.length; index++) {
                                if (!selected[index]) continue;
                                members.add(ids.get(index));
                                LauncherElementFrame frame =
                                        globalElementFrames.get(ids.get(index));
                                if (frame == null) continue;
                                FrameLayout.LayoutParams params =
                                        (FrameLayout.LayoutParams) frame.getLayoutParams();
                                left = Math.min(left, params.leftMargin);
                                top = Math.min(top, params.topMargin);
                                right = Math.max(right, params.leftMargin
                                        + Math.max(1, params.width));
                                bottom = Math.max(bottom, params.topMargin
                                        + Math.max(1, params.height));
                            }
                            if (members.size() < 2) {
                                Toast.makeText(this,
                                        "Выберите как минимум два виджета",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            if (left == Integer.MAX_VALUE) {
                                left = 0;
                                top = 0;
                                right = Math.min(workspace.getWidth(), dp(620));
                                bottom = Math.min(workspace.getHeight(), dp(150));
                            }
                            LauncherHorizontalGroupStore.Group created =
                                    horizontalGroupStore.create(members, left, top,
                                            Math.max(1, right - left),
                                            Math.max(1, bottom - top));
                            appliedLauncherHorizontalGroupsJson =
                                    preferences.launcherHorizontalGroupsJson.get();
                            syncLauncherHorizontalGroups();
                            refreshGlobalElementVisibility();
                            dialog.dismiss();
                            showLauncherHorizontalGroupEditor(created.id);
                        }));
        dialog.show();
    }

    private void showLauncherHorizontalGroupEditor(@NonNull String id) {
        LauncherHorizontalGroupStore.Group group = horizontalGroupStore.get(id);
        if (group == null || isFinishing() || isDestroyed()) return;
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(10), dp(20), dp(30));
        scroll.addView(form, new ScrollView.LayoutParams(matchWidth(), wrapContent()));

        TextView hint = text(13, Color.LTGRAY, false);
        hint.setText("Элементы располагаются слева направо. Отступы и интервал могут быть "
                + "нулевыми; размер текста каждого виджета не меняется.");
        form.addView(hint);
        addWidgetEditorSlider(form, "Интервал", 0, 300, group.gapPx, " px", value -> {
            group.gapPx = value;
            saveLauncherHorizontalGroup(group);
        });
        addWidgetEditorSlider(form, "Отступ слева", 0, 300,
                group.paddingLeftPx, " px", value -> {
                    group.paddingLeftPx = value;
                    saveLauncherHorizontalGroup(group);
                });
        addWidgetEditorSlider(form, "Отступ сверху", 0, 300,
                group.paddingTopPx, " px", value -> {
                    group.paddingTopPx = value;
                    saveLauncherHorizontalGroup(group);
                });
        addWidgetEditorSlider(form, "Отступ справа", 0, 300,
                group.paddingRightPx, " px", value -> {
                    group.paddingRightPx = value;
                    saveLauncherHorizontalGroup(group);
                });
        addWidgetEditorSlider(form, "Отступ снизу", 0, 300,
                group.paddingBottomPx, " px", value -> {
                    group.paddingBottomPx = value;
                    saveLauncherHorizontalGroup(group);
                });

        MaterialButton distribution = widgetEditorButton("Распределение: "
                + (group.distribution == HorizontalGroupLayout.DISTRIBUTION_EQUAL
                ? "равные ячейки" : "компактно"));
        distribution.setOnClickListener(view ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Распределение в ряду")
                        .setItems(new String[]{"Компактно", "Равные ячейки"},
                                (choice, which) -> {
                                    group.distribution = which;
                                    distribution.setText("Распределение: "
                                            + (which == HorizontalGroupLayout.DISTRIBUTION_EQUAL
                                            ? "равные ячейки" : "компактно"));
                                    saveLauncherHorizontalGroup(group);
                                })
                        .show());
        form.addView(distribution, widgetEditorRow());

        MaterialButton horizontal = widgetEditorButton("По горизонтали: "
                + groupAlignmentLabel(group.horizontalAlignment));
        horizontal.setOnClickListener(view ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Положение содержимого")
                        .setItems(new String[]{"Слева", "По центру", "Справа"},
                                (choice, which) -> {
                                    group.horizontalAlignment = which;
                                    horizontal.setText("По горизонтали: "
                                            + groupAlignmentLabel(which));
                                    saveLauncherHorizontalGroup(group);
                                })
                        .show());
        form.addView(horizontal, widgetEditorRow());

        MaterialButton vertical = widgetEditorButton("По вертикали: "
                + groupVerticalAlignmentLabel(group.verticalAlignment));
        vertical.setOnClickListener(view ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Выравнивание элементов")
                        .setItems(new String[]{"Сверху", "По центру", "Снизу"},
                                (choice, which) -> {
                                    group.verticalAlignment = which;
                                    vertical.setText("По вертикали: "
                                            + groupVerticalAlignmentLabel(which));
                                    saveLauncherHorizontalGroup(group);
                                })
                        .show());
        form.addView(vertical, widgetEditorRow());

        MaterialButton members = widgetEditorButton(
                "Состав ряда · " + group.memberIds.size());
        members.setOnClickListener(view -> chooseLauncherHorizontalGroupMembers(group));
        form.addView(members, widgetEditorRow());
        for (String memberId : group.memberIds) {
            String memberLabel = launcherWidgetLabel(memberId);
            MaterialButton configure = widgetEditorButton(
                    "Настроить виджет · " + memberLabel);
            configure.setOnClickListener(view ->
                    showLauncherWidgetEditor(memberId, memberLabel));
            form.addView(configure, widgetEditorRow());
        }

        androidx.appcompat.app.AlertDialog editor =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(group.name)
                        .setView(scroll)
                        .setPositiveButton("Готово", null)
                        .setNegativeButton("Разгруппировать", null)
                        .create();
        editor.setOnShowListener(ignored ->
                editor.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)
                        .setOnClickListener(view ->
                                new androidx.appcompat.app.AlertDialog.Builder(this)
                                        .setTitle("Разгруппировать ряд?")
                                        .setMessage("Виджеты останутся на лаунчере и снова "
                                                + "будут редактироваться отдельно.")
                                        .setPositiveButton("Разгруппировать",
                                                (confirm, which) -> {
                                                    horizontalGroupStore.remove(id);
                                                    appliedLauncherHorizontalGroupsJson =
                                                            preferences
                                                                    .launcherHorizontalGroupsJson
                                                                    .get();
                                                    syncLauncherHorizontalGroups();
                                                    refreshGlobalElementVisibility();
                                                    editor.dismiss();
                                                })
                                        .setNegativeButton("Отмена", null)
                                        .show()));
        editor.show();
    }

    private void chooseLauncherHorizontalGroupMembers(
            @NonNull LauncherHorizontalGroupStore.Group group) {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String id : globalElementFrames.keySet()) {
            if (globalElementLayoutStore.getAppearance(id).hidden) continue;
            ids.add(id);
            labels.add(launcherWidgetLabel(id));
        }
        boolean[] selected = new boolean[ids.size()];
        for (int index = 0; index < ids.size(); index++) {
            selected[index] = group.memberIds.contains(ids.get(index));
        }
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Состав горизонтального ряда")
                        .setMultiChoiceItems(labels.toArray(new String[0]), selected,
                                (value, which, checked) -> selected[which] = checked)
                        .setPositiveButton("Применить", null)
                        .setNegativeButton("Отмена", null)
                        .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(view -> {
                            List<String> members = new ArrayList<>();
                            for (int index = 0; index < selected.length; index++) {
                                if (selected[index]) members.add(ids.get(index));
                            }
                            if (members.size() < 2) {
                                Toast.makeText(this,
                                        "В ряду должно остаться минимум два виджета",
                                        Toast.LENGTH_SHORT).show();
                                return;
                            }
                            group.memberIds.clear();
                            group.memberIds.addAll(members);
                            saveLauncherHorizontalGroup(group);
                            refreshGlobalElementVisibility();
                            dialog.dismiss();
                        }));
        dialog.show();
    }

    @NonNull
    private String launcherWidgetLabel(@NonNull String id) {
        View source = globalElementSources.get(id);
        LauncherGlobalElementTag tag =
                source == null ? null : LauncherGlobalElementTag.from(source);
        return tag == null ? id : tag.label;
    }

    @NonNull
    private static String groupAlignmentLabel(int value) {
        return value == 1 ? "по центру" : value == 2 ? "справа" : "слева";
    }

    @NonNull
    private static String groupVerticalAlignmentLabel(int value) {
        return value == 1 ? "по центру" : value == 2 ? "снизу" : "сверху";
    }

    private void showLauncherWidgetCatalog() {
        List<LauncherWidgetCatalog.Entry> entries = LauncherWidgetCatalog.available(
                hasRemovedLauncherWidgets(),
                panelElementStore.load(LauncherLayoutStore.CLOCK),
                new MediaPanelConfigStore(preferences).load(),
                navigationPanelConfigStore.load(),
                new ClimatePanelConfigStore(preferences).load());
        String[] labels = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            labels[index] = entries.get(index).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить виджет")
                .setItems(labels, (dialog, which) ->
                        addLauncherCatalogEntry(entries.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Executes one concrete entry from the shared flat HOME catalog without another submenu. */
    private void addLauncherCatalogEntry(@NonNull LauncherWidgetCatalog.Entry entry) {
        switch (entry.kind) {
            case BACKDROP:
                createLauncherBackdrop();
                return;
            case HORIZONTAL_GROUP:
                createLauncherHorizontalGroup();
                return;
            case RESTORE:
                showRemovedLauncherWidgets();
                return;
            case SHORTCUT:
                startActivity(new Intent(this, LauncherShortcutSettingsActivity.class)
                        .putExtra(LauncherShortcutSettingsActivity.EXTRA_ADD_NEW, true));
                return;
            case FAVORITE_APP:
                showFavoriteAppWidgetCatalog();
                return;
            case FAVORITE_ROUTE:
                startActivity(new Intent(this, FavoriteRoutesSettingsActivity.class));
                return;
            case SIMPLE:
                enableSimpleLauncherElement(entry.panelId, entry.elementId);
                return;
            case MEDIA:
                enableMediaLauncherElement(entry.elementId);
                return;
            case NAVIGATION:
                enableNavigationLauncherElement(entry.elementId);
                return;
            case CLIMATE:
                enableClimateLauncherElement(entry.elementId);
                return;
            case INFORMATION:
                preferences.launcherInformationVisible.set(true);
                startActivity(new Intent(this, InformationPanelSettingsActivity.class));
                return;
            case VEHICLE:
                preferences.launcherVehicleInfoVisible.set(true);
                startActivity(new Intent(this, VehicleInfoPanelSettingsActivity.class));
                return;
            default:
                return;
        }
    }

    private void enableSimpleLauncherElement(
            @NonNull String panelId, @NonNull String elementId) {
        PanelElementConfigStore.Panel updated = panelElementStore.load(panelId);
        updated.setEnabled(elementId, true);
        panelElementStore.save(updated);
        if (LauncherLayoutStore.CLOCK.equals(panelId)) {
            preferences.launcherClockVisible.set(true);
            replacePanelContent(panelId, buildClockPanel());
        } else if (LauncherLayoutStore.APPS.equals(panelId)) {
            preferences.launcherAppsVisible.set(true);
            replacePanelContent(panelId, buildAppsPanel());
        }
        setPanelVisibility(panelId, true);
        refreshGlobalElementsAfterWidgetChange();
    }

    private void enableMediaLauncherElement(@NonNull String elementId) {
        MediaPanelConfig updated = new MediaPanelConfigStore(preferences).load();
        if (!updated.setEnabled(elementId, true)) return;
        new MediaPanelConfigStore(preferences).save(updated);
        preferences.launcherMediaVisible.set(true);
        if (mediaPanel != null) mediaPanel.reloadConfig();
        setPanelVisibility(LauncherLayoutStore.MEDIA, true);
        refreshGlobalElementsAfterWidgetChange();
    }

    private void enableNavigationLauncherElement(@NonNull String elementId) {
        NavigationPanelConfig updated = navigationPanelConfigStore.load();
        if (!updated.setEnabled(elementId, true)) return;
        navigationPanelConfigStore.save(updated);
        preferences.launcherNavigationVisible.set(true);
        replacePanelContent(LauncherLayoutStore.NAVIGATION,
                buildCombinedNavigationPanel());
        setPanelVisibility(LauncherLayoutStore.NAVIGATION, true);
        updateNavigation();
        refreshGlobalElementsAfterWidgetChange();
    }

    private void enableClimateLauncherElement(@NonNull String elementId) {
        ClimatePanelConfig updated = new ClimatePanelConfigStore(preferences).load();
        updated.setElementEnabled(elementId, true);
        new ClimatePanelConfigStore(preferences).save(updated);
        preferences.launcherClimateVisible.set(true);
        if (climatePanel != null) climatePanel.reloadConfig();
        setPanelVisibility(LauncherLayoutStore.CLIMATE, true);
        refreshGlobalElementsAfterWidgetChange();
    }

    private boolean hasRemovedLauncherWidgets() {
        if (globalElementLayoutStore == null) return false;
        for (String id : globalElementFrames.keySet()) {
            if (globalElementLayoutStore.getAppearance(id).hidden) return true;
        }
        return false;
    }

    private void showRemovedLauncherWidgets() {
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String id : globalElementFrames.keySet()) {
            if (!globalElementLayoutStore.getAppearance(id).hidden) continue;
            ids.add(id);
            View source = globalElementSources.get(id);
            LauncherGlobalElementTag tag = source == null
                    ? null : LauncherGlobalElementTag.from(source);
            labels.add(tag == null ? id : tag.label);
        }
        if (ids.isEmpty()) {
            Toast.makeText(this, "Удалённых виджетов нет", Toast.LENGTH_SHORT).show();
            return;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Вернуть виджет")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    String id = ids.get(which);
                    LauncherGlobalElementLayoutStore.Appearance appearance =
                            globalElementLayoutStore.getAppearance(id);
                    appearance.hidden = false;
                    saveLauncherWidgetAppearance(id, appearance);
                    refreshGlobalElementVisibility();
                    Toast.makeText(this, "Виджет возвращён на прежнее место",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showFavoriteAppWidgetCatalog() {
        List<AppEntry> all = appCatalog == null
                ? Collections.emptyList() : appCatalog.all();
        List<AppEntry> available = new ArrayList<>();
        for (AppEntry app : all) {
            if (!favoriteAppsConfigStore.contains(app.packageName)) available.add(app);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, all.isEmpty()
                    ? "Список приложений ещё загружается"
                    : "Все приложения уже добавлены", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (AppEntry app : available) labels.add(app.label + "\n" + app.packageName);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить приложение")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    favoriteAppsConfigStore.add(available.get(which).packageName);
                    PanelElementConfigStore.Panel panel =
                            panelElementStore.load(LauncherLayoutStore.APPS);
                    panel.setEnabled(PanelElementConfigStore.APPS_GRID, true);
                    panelElementStore.save(panel);
                    preferences.launcherAppsVisible.set(true);
                    replacePanelContent(LauncherLayoutStore.APPS, buildAppsPanel());
                    setPanelVisibility(LauncherLayoutStore.APPS, true);
                    refreshFavorites();
                    refreshGlobalElementsAfterWidgetChange();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showSimpleWidgetCatalog(@NonNull String panelId, @NonNull String title) {
        List<PanelElementConfigStore.Definition> definitions =
                PanelElementConfigStore.definitions(panelId);
        PanelElementConfigStore.Panel current = panelElementStore.load(panelId);
        List<PanelElementConfigStore.Definition> available = new ArrayList<>();
        for (PanelElementConfigStore.Definition definition : definitions) {
            if (!current.isEnabled(definition.id)) available.add(definition);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Все элементы уже добавлены", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[available.size()];
        for (int index = 0; index < available.size(); index++) {
            labels[index] = available.get(index).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels, (dialog, which) -> {
                    PanelElementConfigStore.Panel updated = panelElementStore.load(panelId);
                    updated.setEnabled(available.get(which).id, true);
                    panelElementStore.save(updated);
                    if (LauncherLayoutStore.CLOCK.equals(panelId)) {
                        preferences.launcherClockVisible.set(true);
                        replacePanelContent(panelId, buildClockPanel());
                    } else if (LauncherLayoutStore.APPS.equals(panelId)) {
                        preferences.launcherAppsVisible.set(true);
                        replacePanelContent(panelId, buildAppsPanel());
                    }
                    setPanelVisibility(panelId, true);
                    refreshGlobalElementsAfterWidgetChange();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showMediaWidgetCatalog() {
        MediaPanelConfig current = new MediaPanelConfigStore(preferences).load();
        List<MediaPanelConfig.Spec> available = new ArrayList<>();
        for (MediaPanelConfig.Spec spec : MediaPanelConfig.SPECS) {
            if (!current.element(spec.id).enabled) available.add(spec);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Все элементы медиаплеера уже добавлены",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[available.size()];
        for (int index = 0; index < available.size(); index++) {
            labels[index] = available.get(index).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить элемент медиаплеера")
                .setItems(labels, (dialog, which) -> {
                    MediaPanelConfig updated =
                            new MediaPanelConfigStore(preferences).load();
                    updated.setEnabled(available.get(which).id, true);
                    new MediaPanelConfigStore(preferences).save(updated);
                    preferences.launcherMediaVisible.set(true);
                    if (mediaPanel != null) mediaPanel.reloadConfig();
                    setPanelVisibility(LauncherLayoutStore.MEDIA, true);
                    refreshGlobalElementsAfterWidgetChange();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showNavigationWidgetCatalog() {
        NavigationPanelConfig current = navigationPanelConfigStore.load();
        List<NavigationPanelConfig.Spec> available = new ArrayList<>();
        for (NavigationPanelConfig.Spec spec : NavigationPanelConfig.SPECS) {
            if (!current.element(spec.id).enabled) available.add(spec);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Все элементы навигации уже добавлены",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[available.size()];
        for (int index = 0; index < available.size(); index++) {
            labels[index] = available.get(index).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить элемент навигации")
                .setItems(labels, (dialog, which) -> {
                    NavigationPanelConfig updated = navigationPanelConfigStore.load();
                    updated.setEnabled(available.get(which).id, true);
                    navigationPanelConfigStore.save(updated);
                    preferences.launcherNavigationVisible.set(true);
                    replacePanelContent(LauncherLayoutStore.NAVIGATION,
                            buildCombinedNavigationPanel());
                    setPanelVisibility(LauncherLayoutStore.NAVIGATION, true);
                    updateNavigation();
                    refreshGlobalElementsAfterWidgetChange();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showClimateWidgetCatalog() {
        ClimatePanelConfig current = new ClimatePanelConfigStore(preferences).load();
        List<ClimatePanelConfig.Element> available = new ArrayList<>();
        for (ClimatePanelConfig.Element element : ClimatePanelConfig.ELEMENTS) {
            if (!current.isElementEnabled(element.id)) available.add(element);
        }
        if (available.isEmpty()) {
            Toast.makeText(this, "Все элементы климата уже добавлены",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[available.size()];
        for (int index = 0; index < available.size(); index++) {
            labels[index] = available.get(index).label;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Добавить элемент климата")
                .setItems(labels, (dialog, which) -> {
                    ClimatePanelConfig updated =
                            new ClimatePanelConfigStore(preferences).load();
                    updated.setElementEnabled(available.get(which).id, true);
                    new ClimatePanelConfigStore(preferences).save(updated);
                    preferences.launcherClimateVisible.set(true);
                    if (climatePanel != null) climatePanel.reloadConfig();
                    setPanelVisibility(LauncherLayoutStore.CLIMATE, true);
                    refreshGlobalElementsAfterWidgetChange();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refreshGlobalElementsAfterWidgetChange() {
        workspace.post(() -> {
            syncGlobalElements();
            syncLauncherHorizontalGroups();
            refreshGlobalElementVisibility();
        });
    }

    private void addWidgetEditorSlider(
            @NonNull LinearLayout parent, @NonNull String label,
            int minimum, int maximum, int current, @NonNull String suffix,
            @NonNull java.util.function.IntConsumer listener) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        TextView value = text(14, Color.LTGRAY, false);
        value.setText(label + ": " + current + suffix);
        block.addView(value);
        SeekBar seek = new SeekBar(this);
        seek.setMax(Math.max(0, maximum - minimum));
        seek.setProgress(Math.max(0, Math.min(maximum - minimum, current - minimum)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                if (!fromUser) return;
                int selected = minimum + progress;
                value.setText(label + ": " + selected + suffix);
                listener.accept(selected);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        block.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        parent.addView(block, widgetEditorRow());
    }

    private void addWidgetColorButton(
            @NonNull LinearLayout parent, @NonNull String title,
            @Nullable String current,
            @NonNull java.util.function.Consumer<String> listener) {
        String initial = current == null || current.trim().isEmpty()
                ? "#00000000" : current;
        MaterialButton button = widgetEditorButton(title + ": "
                + (current == null || current.trim().isEmpty() ? "исходный" : current));
        button.setOnClickListener(view -> AppleColorPickerDialog.show(
                this, title, initial, AppleColorPickerDialog.Options.standard(),
                new AppleColorPickerDialog.Listener() {
                    @Override public void onPreview(@Nullable String value) {
                        if (value == null) return;
                        listener.accept(value);
                        button.setText(title + ": " + value);
                    }

                    @Override public void onSelected(@Nullable String value) {
                        if (value == null) return;
                        listener.accept(value);
                        button.setText(title + ": " + value);
                    }
                }));
        parent.addView(button, widgetEditorRow());
    }

    private void chooseLauncherWidgetApp(
            @NonNull String id,
            @NonNull LauncherGlobalElementLayoutStore.Appearance appearance,
            @NonNull MaterialButton actionButton) {
        List<AppEntry> apps = appCatalog == null
                ? Collections.emptyList() : appCatalog.all();
        if (apps.isEmpty()) {
            Toast.makeText(this, "Список приложений ещё загружается",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> labels = new ArrayList<>(apps.size());
        for (AppEntry app : apps) labels.add(app.label + "\n" + app.packageName);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, labels);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Приложение при нажатии")
                .setAdapter(adapter, (dialog, which) -> {
                    AppEntry app = apps.get(which);
                    appearance.tapAction =
                            LauncherGlobalElementLayoutStore.TapAction.APP;
                    appearance.appComponent = app.component.flattenToString();
                    actionButton.setText("Нажатие: открыть " + app.label);
                    saveLauncherWidgetAppearance(id, appearance);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @NonNull
    private MaterialButton widgetEditorButton(@NonNull String title) {
        MaterialButton button = new MaterialButton(this);
        button.setAllCaps(false);
        button.setText(title);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return button;
    }

    @NonNull
    private LinearLayout.LayoutParams widgetEditorRow() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(7);
        return params;
    }

    @NonNull
    private LinearLayout.LayoutParams widgetEditorSection() {
        LinearLayout.LayoutParams params = widgetEditorRow();
        params.topMargin = dp(18);
        return params;
    }

    @NonNull
    private static String widgetScaleModeLabel(
            @NonNull LauncherGlobalElementLayoutStore.ScaleMode mode) {
        switch (mode) {
            case CROP: return "заполнить с обрезкой";
            case STRETCH: return "растянуть";
            case FIT:
            default: return "вписать целиком";
        }
    }

    @NonNull
    private String widgetFontLabel(@Nullable String family) {
        if (family == null || family.trim().isEmpty()) return "исходный";
        return getString(Fonts.findByKey(family).labelRes);
    }

    @NonNull
    private static String widgetHorizontalLabel(int value) {
        return value < 0 ? "как в элементе"
                : value == 0 ? "слева" : value == 1 ? "по центру" : "справа";
    }

    @NonNull
    private static String widgetVerticalLabel(int value) {
        return value < 0 ? "как в элементе"
                : value == 0 ? "сверху" : value == 1 ? "по центру" : "снизу";
    }

    @NonNull
    private String widgetTapActionLabel(
            @NonNull LauncherGlobalElementLayoutStore.Appearance appearance) {
        if (appearance.tapAction == LauncherGlobalElementLayoutStore.TapAction.NONE) {
            return "без действия";
        }
        if (appearance.tapAction != LauncherGlobalElementLayoutStore.TapAction.APP) {
            return "исходное действие";
        }
        ComponentName component = ComponentName.unflattenFromString(
                appearance.appComponent);
        if (component == null) return "открыть приложение";
        if (appCatalog != null) {
            for (AppEntry app : appCatalog.all()) {
                if (component.equals(app.component)) return "открыть " + app.label;
            }
        }
        return "открыть " + component.getPackageName();
    }

    private void finishActiveEditor() {
        if (mediaContentEditMode) {
            setMediaContentEditMode(false);
        } else if (navigationContentEditMode) {
            setNavigationContentEditMode(false);
        } else if (actionsContentEditMode) {
            setActionsContentEditMode(false);
        } else {
            setEditMode(false);
        }
    }

    private Drawable buildBackground() {
        int base;
        try { base = Color.parseColor(preferences.launcherBackgroundColor.get()); }
        catch (IllegalArgumentException ignored) { base = Color.rgb(16, 24, 39); }
        return gradientBackground(base);
    }

    @NonNull
    private Drawable gradientBackground(int base) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{base, blend(base, Color.rgb(22, 77, 110), .38f), Color.BLACK});
        drawable.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return drawable;
    }

    private void initializePanels() {
        if (!launcherBootstrapReady || !launcherFirstDrawCompleted || !activityStarted
                || panelsInitialized || panelsInitializing
                || workspace.getWidth() <= 0 || workspace.getHeight() <= 0) return;
        panelsInitializing = true;
        panelGeometryReady = false;
        final int generation = ++panelInitializationGeneration;
        final int width = workspace.getWidth();
        final int height = workspace.getHeight();
        final String globalRaw = preferences.launcherGlobalElementsJson.get();
        final String groupRaw = preferences.launcherHorizontalGroupsJson.get();
        try {
            launcherWorker.execute(() -> {
                RuntimeException loadFailure = null;
                try {
                    layoutStore.load(width, height);
                    globalElementLayoutStore.load(width, height);
                    horizontalGroupStore.load(width, height);
                } catch (RuntimeException failure) {
                    loadFailure = failure;
                }
                RuntimeException completedFailure = loadFailure;
                navigationUiHandler.post(() -> finishPanelGeometryLoad(
                        generation, globalRaw, groupRaw, completedFailure));
            });
        } catch (RejectedExecutionException failure) {
            panelsInitializing = false;
            Log.e(TAG, "HOME geometry could not be scheduled", failure);
        }
    }

    private void finishPanelGeometryLoad(
            int generation,
            @NonNull String globalRaw,
            @NonNull String groupRaw,
            @Nullable RuntimeException failure) {
        if (generation != panelInitializationGeneration || panelsInitialized || isDestroyed()) {
            return;
        }
        if (failure != null) Log.w(TAG, "HOME geometry load used safe defaults", failure);
        panelGeometryReady = true;
        appliedGlobalElementsJson = globalRaw;
        appliedLauncherHorizontalGroupsJson = groupRaw;
        migrateLegacyNavigationPanel();
        // Build the first HOME frame with an empty catalog. PackageManager queries and icon loads
        // run on a worker, then atomically swap the completed catalog onto the UI thread.
        appCatalog = new AppCatalog(getApplicationContext());
        shortcutStore = new LauncherShortcutStore(preferences);
        panelInitializationStage = 0;
        if (activityStarted) navigationUiHandler.post(panelInitializationStep);
    }

    private void continuePanelInitialization() {
        if (!panelsInitializing || !panelGeometryReady || panelsInitialized
                || !activityStarted || isFinishing() || isDestroyed()) return;
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
                            () -> actionsContentEditMode
                                    || preferences.launcherActionsVisible.get()
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
                case 7:
                    addPanelSafely(LauncherLayoutStore.INFORMATION, "Информация",
                            this::buildInformationPanel,
                            () -> preferences.launcherInformationVisible.get()
                                    && informationConfigStore.load().hasEnabledItems());
                    makePanelTransparent(LauncherLayoutStore.INFORMATION);
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
        // Each stage starts at the next available main-loop turn. This keeps input/traversal
        // responsive without imposing the old fixed 32-ms startup delay per panel.
        navigationUiHandler.post(panelInitializationStep);
    }

    private void makePanelTransparent(@NonNull String id) {
        LauncherElementFrame frame = panels.get(id);
        if (frame == null) return;
        frame.setCardBackgroundColor(Color.TRANSPARENT);
        frame.setCardElevation(0);
    }

    private void finishPanelInitialization() {

        mediaController = new LauncherMediaController(this, preferences, this::updateMedia);
        panelsInitialized = true;
        panelsInitializing = false;
        panelGeometryReady = false;
        // initializePanels() is posted from onCreate. If the activity was stopped before that
        // callback runs, starting here would leave a MediaSession listener alive off-screen.
        // onStart() will start it normally when HOME becomes active again.
        scheduleDeferredLauncherRuntimeStart();
        refreshFavorites();
        appliedPanelElementsJson = preferences.launcherPanelElementsJson.get();
        appliedNavigationConfigJson = preferences.launcherNavigationConfigJson.get();
        appliedActionsGridJson = preferences.launcherActionsGridJson.get();
        appliedAppsColumns = preferences.launcherAppsColumns.get();
        appliedActionsColumns = preferences.launcherActionsColumns.get();
        // Wait for the just-added live children to receive exact pixel bounds, then migrate each
        // one from its old panel-local rectangle into the shared screen coordinate space.
        workspace.post(() -> {
            activateGlobalElements();
            applyRequestedHomeEditor(getIntent());
        });
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
        vehicleInfoPanel = new VehicleInfoPanelView(this, this::requireLauncherCarIntegration,
                vehicleInfoConfigStore);
        vehicleInfoPanel.setContentVisibilityListener(contentVisible -> {
                setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO,
                        preferences.launcherVehicleInfoVisible.get()
                                && (editMode || contentVisible));
                requestGlobalElementRefresh();
        });
        return vehicleInfoPanel;
    }

    @NonNull
    private View buildInformationPanel() {
        informationPanel = new InformationPanelView(this, this::requireLauncherCarIntegration,
                informationConfigStore);
        informationPanel.setContentListener(hasItems -> {
                setPanelVisibility(LauncherLayoutStore.INFORMATION,
                        preferences.launcherInformationVisible.get()
                                && (editMode || hasItems));
                requestGlobalElementRefresh();
        });
        return informationPanel;
    }

    private void addPanel(@NonNull String id, @NonNull String label, @NonNull View content,
                          boolean visible) {
        LauncherElementFrame frame = new LauncherElementFrame(this, id, label,
                (changedId, x, y, width, height) -> layoutStore.put(changedId,
                        new LauncherLayoutStore.Geometry(x, y, width, height)));
        frame.setContent(content);
        // Source panels must be measured and kept alive for the screen-space proxies, but no raw
        // source pixel or duplicate touch target may ever reach a composed HOME frame.
        frame.setAlpha(0f);
        frame.setContentTouchBlocked(true);
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
                LauncherGlobalElementTag.attach(heading, LauncherLayoutStore.APPS,
                        element.id, "Заголовок приложений");
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
                    @Override public void seekTo(long positionMs) {
                        if (mediaController != null) mediaController.seekTo(positionMs);
                    }
                    @Override public void finishSeek(long positionMs) {
                        if (mediaController != null) mediaController.finishSeek(positionMs);
                    }
                    @Override public boolean openPlayer() {
                        return mediaController != null && mediaController.openTargetPlayer();
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
            LauncherGlobalElementTag.attach(value, LauncherLayoutStore.CLOCK,
                    element.id, PanelElementConfigStore.CLOCK_TIME.equals(element.id)
                            ? "Время" : "Дата");
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
        NavigationPanelConfig.Spec spec = NavigationPanelConfig.spec(element.id);
        LauncherGlobalElementTag.attach(cell, LauncherLayoutStore.NAVIGATION,
                element.id, spec == null ? element.id : spec.label);
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

        FrameLayout host = new FrameLayout(this);
        host.setPadding(dp(7), dp(7), dp(7), dp(7));
        shortcutGrid = new PanelGridLayout(this);
        host.addView(shortcutGrid, match());

        actionsContentEditOverlay = new PanelContentEditOverlay(this);
        actionsContentEditOverlay.setModel(new PanelContentEditOverlay.Model() {
            @Override public int columns() {
                LauncherActionsGridConfig value = actionsGridConfig;
                return value == null ? LauncherActionsGridConfig.DEFAULT_COLUMNS : value.columns;
            }

            @Override public int rows() {
                LauncherActionsGridConfig value = actionsGridConfig;
                return value == null ? LauncherActionsGridConfig.MIN_ROWS : value.rows;
            }

            @NonNull
            @Override public List<PanelContentEditOverlay.Item> items() {
                List<PanelContentEditOverlay.Item> result = new ArrayList<>();
                LauncherActionsGridConfig value = actionsGridConfig;
                if (value == null || shortcutStore == null) return result;
                if (showActionTiles) {
                    for (LauncherShortcutStore.Shortcut shortcut : shortcutStore.all()) {
                        if (!shortcut.enabled) continue;
                        LauncherActionsGridConfig.Placement placement =
                                value.placement(shortcut.id);
                        if (placement == null) continue;
                        result.add(new PanelContentEditOverlay.Item(shortcut.id,
                                shortcut.title + " · " + shortcut.iconSizePx + " px",
                                placement.column, placement.row,
                                placement.columnSpan, placement.rowSpan));
                    }
                }
                if (showActionAdd) {
                    LauncherActionsGridConfig.Placement placement =
                            value.placement(LauncherActionsGridConfig.ADD_TILE_ID);
                    if (placement != null) {
                        result.add(new PanelContentEditOverlay.Item(
                                LauncherActionsGridConfig.ADD_TILE_ID, "Добавить",
                                placement.column, placement.row,
                                placement.columnSpan, placement.rowSpan));
                    }
                }
                return result;
            }

            @Override public boolean setPlacement(@NonNull String id, int column, int row,
                                                  int columnSpan, int rowSpan) {
                LauncherActionsGridConfig value = actionsGridConfig;
                return value != null && value.setPlacement(id, column, row,
                        columnSpan, rowSpan);
            }
        }, new PanelContentEditOverlay.Listener() {
            @Override public void onPlacementChanged(@NonNull String id, boolean finished) {
                applyActionsGridPlacements();
                if (finished && actionsGridConfig != null) {
                    actionsGridConfigStore.save(actionsGridConfig);
                    mirrorActionSpansToLegacyShortcut(id);
                    appliedActionsGridJson = preferences.launcherActionsGridJson.get();
                }
            }

            @Override public void onItemClicked(@NonNull String id) {
                if (LauncherActionsGridConfig.ADD_TILE_ID.equals(id)) {
                    showLauncherWidgetCatalog();
                } else {
                    showShortcutIconSizeEditor(id);
                }
            }
        });
        host.addView(actionsContentEditOverlay, match());
        actionsContentEditOverlay.setEditing(actionsContentEditMode);
        shortcutGrid.post(this::refreshShortcutGrid);
        return host;
    }

    @NonNull
    private View buildClimatePanel() {
        climatePanel = new ClimatePanelView(this, this::requireLauncherCarIntegration,
                new ClimatePanelConfigStore(preferences));
        return climatePanel;
    }

    private void refreshShortcutGrid() {
        if (shortcutGrid == null || shortcutStore == null) return;
        shortcutGrid.removeAllViews();
        informationShortcutViews.clear();
        carShortcutBindings.clear();
        smartHomeShortcutBindings.clear();
        smartHomeRules = loadSmartHomeRules();
        List<LauncherShortcutStore.Shortcut> shortcuts = shortcutStore.all();
        actionsGridConfig = actionsGridConfigStore.load(shortcuts);
        LauncherActionsGridConfig gridConfig = actionsGridConfig;
        shortcutGrid.setGridSize(gridConfig.columns, gridConfig.rows);
        shortcutGrid.setCellGapPx(gridConfig.gapPx);
        if (showActionTiles) {
            for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
                if (!shortcut.enabled) continue;
                LauncherActionsGridConfig.Placement placement =
                        gridConfig.placement(shortcut.id);
                if (placement == null) continue;
                View tile = buildShortcutTile(shortcut, false);
                tile.setTag(shortcut.id);
                LauncherGlobalElementTag.attach(tile, LauncherLayoutStore.ACTIONS,
                        shortcut.id, shortcut.title);
                shortcutGrid.addView(tile, new PanelGridLayout.LayoutParams(
                        placement.column, placement.row,
                        placement.columnSpan, placement.rowSpan));
            }
        }
        if (showActionAdd) {
            LauncherShortcutStore.Shortcut add = new LauncherShortcutStore.Shortcut();
            add.id = LauncherActionsGridConfig.ADD_TILE_ID;
            add.title = "Добавить";
            add.icon = "apps";
            add.backgroundColor = "#553A465B";
            LauncherActionsGridConfig.Placement placement =
                    gridConfig.placement(LauncherActionsGridConfig.ADD_TILE_ID);
            if (placement != null) {
                View tile = buildShortcutTile(add, true);
                tile.setTag(LauncherActionsGridConfig.ADD_TILE_ID);
                LauncherGlobalElementTag.attach(tile, LauncherLayoutStore.ACTIONS,
                        LauncherActionsGridConfig.ADD_TILE_ID, "Добавить действие");
                shortcutGrid.addView(tile, new PanelGridLayout.LayoutParams(
                        placement.column, placement.row,
                        placement.columnSpan, placement.rowSpan));
            }
        }
        if (actionsContentEditOverlay != null) actionsContentEditOverlay.invalidate();
        appliedActionsGridJson = preferences.launcherActionsGridJson.get();
        appliedActionsColumns = gridConfig.columns;
        resubscribeCarControls();
        reconcileInformationShortcutRuntime();
        applySmartHomeStates();
    }

    private void applyActionsGridPlacements() {
        PanelGridLayout grid = shortcutGrid;
        LauncherActionsGridConfig config = actionsGridConfig;
        if (grid == null || config == null) return;
        grid.setGridSize(config.columns, config.rows);
        grid.setCellGapPx(config.gapPx);
        for (LauncherActionsGridConfig.Placement placement : config.placements()) {
            grid.updatePlacement(placement.id, placement.column, placement.row,
                    placement.columnSpan, placement.rowSpan);
        }
        if (actionsContentEditOverlay != null) actionsContentEditOverlay.invalidate();
    }

    /** Mirrors only legacy spans so HA1079 rollback keeps the closest possible tile sizes. */
    private void mirrorActionSpansToLegacyShortcut(@NonNull String id) {
        if (shortcutStore == null || actionsGridConfig == null
                || LauncherActionsGridConfig.ADD_TILE_ID.equals(id)) return;
        LauncherActionsGridConfig.Placement placement = actionsGridConfig.placement(id);
        if (placement == null) return;
        for (LauncherShortcutStore.Shortcut shortcut : shortcutStore.all()) {
            if (!shortcut.id.equals(id)) continue;
            if (shortcut.columnSpan == placement.columnSpan
                    && shortcut.rowSpan == placement.rowSpan) return;
            shortcut.columnSpan = placement.columnSpan;
            shortcut.rowSpan = placement.rowSpan;
            shortcutStore.upsert(shortcut);
            return;
        }
    }

    /** Precise per-icon control opened by tapping an item in the real HOME grid editor. */
    private void showShortcutIconSizeEditor(@NonNull String id) {
        if (shortcutStore == null) return;
        LauncherShortcutStore.Shortcut selected = null;
        for (LauncherShortcutStore.Shortcut shortcut : shortcutStore.all()) {
            if (shortcut.id.equals(id)) {
                selected = shortcut;
                break;
            }
        }
        if (selected == null) return;
        LauncherShortcutStore.Shortcut shortcut = selected;

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), dp(10));
        TextView hint = text(14, Color.LTGRAY, false);
        hint.setText("Размер меняет только сам значок. Размер плитки меняется "
                + "перетаскиванием любого из четырёх углов по сетке.");
        form.addView(hint, new LinearLayout.LayoutParams(matchWidth(), wrapContent()));
        TextView value = text(16, Color.WHITE, true);
        value.setText("Размер иконки: " + shortcut.iconSizePx + " px");
        value.setPadding(0, dp(14), 0, 0);
        form.addView(value);
        ImageView preview = new ImageView(this);
        preview.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                shortcut.iconSizePx, shortcut.iconSizePx);
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.topMargin = dp(8);
        form.addView(preview, previewParams);
        SeekBar size = new SeekBar(this);
        size.setMax(LauncherShortcutStore.MAX_ICON_SIZE_PX
                - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        size.setProgress(shortcut.iconSizePx - LauncherShortcutStore.MIN_ICON_SIZE_PX);
        final int[] selectedSize = {shortcut.iconSizePx};
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress,
                                                    boolean fromUser) {
                selectedSize[0] = LauncherShortcutStore.MIN_ICON_SIZE_PX + progress;
                value.setText("Размер иконки: " + selectedSize[0] + " px");
                ViewGroup.LayoutParams params = preview.getLayoutParams();
                params.width = selectedSize[0];
                params.height = selectedSize[0];
                preview.setLayoutParams(params);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(size, new LinearLayout.LayoutParams(matchWidth(), dp(52)));
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(shortcut.title)
                .setView(form)
                .setPositiveButton("Применить", (dialog, which) -> {
                    shortcut.iconSizePx = selectedSize[0];
                    shortcutStore.upsert(shortcut);
                    refreshShortcutGrid();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
    private View buildShortcutTile(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                   boolean addButton) {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(dp(2));
        card.setClickable(true);
        card.setFocusable(true);
        try { card.setCardBackgroundColor(Color.parseColor(shortcut.backgroundColor)); }
        catch (IllegalArgumentException ignored) { card.setCardBackgroundColor(Color.argb(180, 34, 39, 51)); }
        if (!addButton && shortcut.kind == LauncherShortcutStore.Kind.INFO) {
            card.setClickable(false);
            card.setLongClickable(false);
            card.setFocusable(false);
            card.setCardElevation(0);
            InformationShortcutView information = new InformationShortcutView(this,
                    preferences, shortcut, this::requireLauncherCarIntegration);
            informationShortcutViews.add(information);
            card.addView(information,
                    new MaterialCardView.LayoutParams(matchWidth(), matchHeight()));
            return card;
        }
        if (!addButton && shortcut.kind == LauncherShortcutStore.Kind.DIVIDER) {
            card.setClickable(false);
            card.setLongClickable(false);
            card.setFocusable(false);
            card.setCardElevation(0);
            card.setCardBackgroundColor(Color.TRANSPARENT);
            View line = InformationShortcutView.divider(this,
                    shortcut.backgroundColor, shortcut.dividerThicknessPx);
            FrameLayout holder = new FrameLayout(this);
            holder.addView(line, new FrameLayout.LayoutParams(
                    matchWidth(), shortcut.dividerThicknessPx, Gravity.CENTER));
            card.addView(holder, new MaterialCardView.LayoutParams(
                    matchWidth(), matchHeight()));
            return card;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        // Atomic widgets are edge-to-edge. Any visual breathing room belongs to the explicit
        // per-widget outer-padding setting, not an invisible inset baked into this source tile.
        content.setPadding(0, 0, 0, 0);
        ImageView icon = new ImageView(this);
        if (addButton) {
            icon.setImageResource(R.drawable.ic_add);
            icon.setColorFilter(Color.WHITE);
        } else {
            icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut));
        }
        int contentScale = addButton ? actionsAddScalePercent : actionsTileScalePercent;
        // Per-icon size is exact and independent. The legacy group scale still controls labels
        // (and the synthetic Add icon) but can no longer make every user icon grow together.
        int iconSize = addButton
                ? Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX,
                shortcut.iconSizePx * contentScale / 100)
                : Math.max(LauncherShortcutStore.MIN_ICON_SIZE_PX, shortcut.iconSizePx);
        content.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView titleLabel = null;
        if (shortcut.showTitle || addButton) {
            TextView label = text(12f * contentScale / 100f, Color.WHITE, true);
            try { label.setTextColor(Color.parseColor(shortcut.textColor)); }
            catch (IllegalArgumentException ignored) { label.setTextColor(Color.WHITE); }
            label.setGravity(Gravity.CENTER);
            label.setText(addButton ? "+  Добавить" : shortcut.title);
            label.setMaxLines(2);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(matchWidth(), wrapContent());
            labelLp.topMargin = 0;
            content.addView(label, labelLp);
            titleLabel = label;
        }
        TextView stateLabel = null;
        if (!addButton && (shortcut.kind == LauncherShortcutStore.Kind.CAR
                || shortcut.kind == LauncherShortcutStore.Kind.RULE)
                && shortcut.showState) {
            stateLabel = text(11, Color.LTGRAY, true);
            stateLabel.setGravity(Gravity.CENTER);
            stateLabel.setText("…");
            stateLabel.setSingleLine(false);
            stateLabel.setMaxLines(Integer.MAX_VALUE);
            stateLabel.setEllipsize(null);
            stateLabel.setPadding(0, 0, 0, 0);
            GradientDrawable badge = new GradientDrawable();
            badge.setColor(Color.argb(150, 0, 0, 0));
            badge.setCornerRadius(dp(9));
            stateLabel.setBackground(badge);
        }
        card.addView(content, new MaterialCardView.LayoutParams(matchWidth(), matchHeight()));
        card.setSoundEffectsEnabled(true);
        content.setSoundEffectsEnabled(true);
        icon.setSoundEffectsEnabled(true);
        if (stateLabel != null) {
            FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(
                    matchWidth(), wrapContent(), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            badgeLp.setMargins(0, 0, 0, 0);
            card.addView(stateLabel, badgeLp);
        }
        if (addButton) {
            card.setOnClickListener(v -> showLauncherWidgetCatalog());
        } else {
            card.setOnClickListener(v -> executeShortcut(shortcut));
            if (shortcut.hasLongAction) {
                card.setOnLongClickListener(v -> {
                    LauncherShortcutStore.Shortcut action = shortcut.copy();
                    action.kind = shortcut.longKind;
                    action.target = shortcut.longTarget;
                    action.packageName = shortcut.longPackageName;
                    action.command = shortcut.longCommand;
                    action.commandValue = shortcut.longCommandValue;
                    action.commandCycleValues = new ArrayList<>(
                            shortcut.longCommandCycleValues);
                    executeShortcut(action);
                    return true;
                });
            }
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                ShortcutTileBinding binding = new ShortcutTileBinding(shortcut.copy(), card,
                        icon, titleLabel, stateLabel);
                carShortcutBindings.put(shortcut.id, binding);
                applyCarState(binding, carControlStates.get(shortcut.target));
            } else if (shortcut.kind == LauncherShortcutStore.Kind.RULE) {
                ShortcutTileBinding binding = new ShortcutTileBinding(shortcut.copy(), card,
                        icon, titleLabel, stateLabel);
                smartHomeShortcutBindings.put(shortcut.id, binding);
                applySmartHomeState(binding);
            }
        }
        return card;
    }

    private void executeShortcut(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        try {
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                CarControlCommand requested = new CarControlCommand(shortcut.target,
                        shortcut.command, shortcut.commandValue,
                        shortcut.commandCycleValues);
                CarControlCommand command = TrunkControlSafety.resolve(requested,
                        carControlStates.get(shortcut.target));
                Runnable execute = () -> executeCarControl(shortcut, command);
                if (TrunkControlSafety.confirmOpeningIfNeeded(this, command, execute)) return;
                execute.run();
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
            if (shortcut.kind == LauncherShortcutStore.Kind.INFO
                    || shortcut.kind == LauncherShortcutStore.Kind.DIVIDER) return;
            executeBuiltin(LauncherShortcutStore.Builtin.fromKey(shortcut.target),
                    shortcut.target);
        } catch (RuntimeException error) {
            if (shortcut.kind == LauncherShortcutStore.Kind.CAR) {
                pendingCarControls.remove(shortcut.target);
            }
            Toast.makeText(this, "Действие не выполнено: " + shortcut.title,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void executeCarControl(@NonNull LauncherShortcutStore.Shortcut shortcut,
                                   @NonNull CarControlCommand command) {
        if (!pendingCarControls.add(shortcut.target)) return;
        requireLauncherCarIntegration().executeControl(command, (success, message) -> {
            pendingCarControls.remove(shortcut.target);
            if (!success) {
                Toast.makeText(this, message == null ? "Команда не выполнена" : message,
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Constructs the process-wide vendor bridge on the serial background lane. */
    private void startLauncherCarRuntimeAsync() {
        if (carIntegration != null) {
            activateLauncherCarRuntime();
            return;
        }
        if (carIntegrationLoadInFlight || launcherWorker.isShutdown()) return;
        carIntegrationLoadInFlight = true;
        final int generation = ++carIntegrationLoadGeneration;
        try {
            launcherWorker.execute(() -> {
                CarIntegration loaded = null;
                RuntimeException error = null;
                try {
                    loaded = CarIntegrations.get(getApplicationContext());
                } catch (RuntimeException failure) {
                    error = failure;
                }
                CarIntegration completed = loaded;
                RuntimeException completedError = error;
                navigationUiHandler.post(() -> {
                    if (generation != carIntegrationLoadGeneration) return;
                    carIntegrationLoadInFlight = false;
                    if (completedError != null) {
                        Log.w(TAG, "Vehicle runtime did not initialize", completedError);
                        return;
                    }
                    carIntegration = completed;
                    if (activityStarted && launcherRuntimeStageReached(3)) {
                        activateLauncherCarRuntime();
                    }
                });
            });
        } catch (RejectedExecutionException failure) {
            carIntegrationLoadInFlight = false;
            Log.w(TAG, "Vehicle runtime could not be scheduled", failure);
        }
    }

    private void activateLauncherCarRuntime() {
        if (carIntegration == null || !activityStarted || !launcherRuntimeStageReached(3)) return;
        resubscribeCarControls();
        if (climatePanel != null && preferences.launcherClimateVisible.get()
                && hasClimatePanelContent()) climatePanel.start();
        if (vehicleInfoPanel != null && preferences.launcherVehicleInfoVisible.get()) {
            vehicleInfoPanel.start();
        }
        if (informationPanel != null && preferences.launcherInformationVisible.get()
                && informationPanel.hasConfiguredItems()) {
            informationPanel.start();
        }
        reconcileInformationShortcutRuntime();
    }

    /** Resolves ECARX after automatic background warm-up, or from an explicit car command. */
    @NonNull
    private CarIntegration requireLauncherCarIntegration() {
        CarIntegration current = carIntegration;
        if (current != null) return current;
        current = CarIntegrations.get(this);
        carIntegration = current;
        return current;
    }

    private void reconcileInformationShortcutRuntime() {
        boolean start = activityStarted && launcherRuntimeStageReached(3);
        for (InformationShortcutView shortcut : informationShortcutViews) {
            if (start) shortcut.start(); else shortcut.stop();
        }
    }

    private void resubscribeCarControls() {
        if (!activityStarted || !launcherRuntimeStageReached(3)
                || carIntegration == null || shortcutStore == null) return;
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
        if (TrunkControlSafety.isTrunk(shortcut.target)) {
            binding.icon.setImageDrawable(LauncherIconResolver.resolvePreset(this,
                    TrunkControlSafety.iconKey(shortcut.icon, state), tint));
        } else {
            binding.icon.setImageDrawable(LauncherIconResolver.resolve(this, shortcut, tint));
        }
        if (binding.titleLabel != null) {
            try { binding.titleLabel.setTextColor(Color.parseColor(tint)); }
            catch (IllegalArgumentException ignored) {
                binding.titleLabel.setTextColor(Color.WHITE);
            }
        }
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
        requestGlobalElementRefresh();
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
        String contentColor = SmartHomeTileColorPolicy.contentColor(
                source, shortcut.textColor, tint);
        LauncherShortcutStore.Shortcut visual = shortcut.copy();
        visual.icon = state.iconKey;
        binding.icon.setImageDrawable(LauncherIconResolver.resolve(this, visual, tint));
        if (binding.titleLabel != null) {
            try { binding.titleLabel.setTextColor(Color.parseColor(contentColor)); }
            catch (IllegalArgumentException ignored) {
                binding.titleLabel.setTextColor(Color.WHITE);
            }
        }
        binding.card.setAlpha(!state.present ? .62f
                : !state.available ? .42f : !state.fresh ? .68f : 1f);
        if (binding.stateLabel != null) {
            binding.stateLabel.setText(state.valueLabel);
            try { binding.stateLabel.setTextColor(Color.parseColor(contentColor)); }
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

    private void executeBuiltin(@NonNull LauncherShortcutStore.Builtin action,
                                @NonNull String rawTarget) {
        switch (action) {
            case HOME:
                startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
                break;
            case BACK:
                if (!WidgetAccessibilityService.performGlobalBack()) onBackPressed();
                break;
            case RECENTS:
                if (!WidgetAccessibilityService.performGlobalRecents(
                        accepted -> {
                            if (!accepted) openRecentsWithShell();
                        })) openRecentsWithShell();
                break;
            case STOCK_CLIMATE:
                dezz.status.widget.driver.DriverPanelService.triggerStockClimate(this);
                break;
            case FAVORITES:
                dezz.status.widget.driver.DriverPanelService.showFavorites(this,
                        LauncherShortcutStore.driverFavoritesPanelId(rawTarget));
                break;
            case FAVORITE_ROUTE:
                boolean found = false;
                for (FavoriteRouteConfig route : favoriteRoutesConfigStore.load()) {
                    if (!route.enabled || !route.id.equals(
                            LauncherShortcutStore.favoriteRouteId(rawTarget))) continue;
                    found = true;
                    YandexRouteLauncher.launch(this, route);
                    break;
                }
                if (!found) Toast.makeText(this, "Избранная точка не найдена",
                        Toast.LENGTH_SHORT).show();
                break;
            case MAPS_WINDOW: launchYandex(YandexWindowLauncher.Product.MAPS, false); break;
            case MAPS_FULL: launchYandex(YandexWindowLauncher.Product.MAPS, true); break;
            case NAVIGATOR_WINDOW: launchYandex(YandexWindowLauncher.Product.NAVIGATOR, false); break;
            case NAVIGATOR_FULL: launchYandex(YandexWindowLauncher.Product.NAVIGATOR, true); break;
            case MEDIA_PLAY_PAUSE: mediaController.playPause(); break;
            case MEDIA_PREVIOUS: mediaController.previous(); break;
            case MEDIA_NEXT: mediaController.next(); break;
            case EDIT_HOME: setEditMode(true); break;
            case HOME_SETTINGS:
                startActivity(SettingsHubActivity.intent(this,
                        dezz.status.widget.settings.SettingsDestinationCatalog.Group.HOME));
                break;
            case WIDGET_SETTINGS:
                startActivity(SettingsHubActivity.intent(this,
                        dezz.status.widget.settings.SettingsDestinationCatalog.Group.STATUS));
                break;
            case POPUP_SETTINGS:
                startActivity(SettingsHubActivity.intent(this,
                        dezz.status.widget.settings.SettingsDestinationCatalog.Group.PANELS));
                break;
            case AUTOMATION_SETTINGS:
                startActivity(SettingsHubActivity.intent(this,
                        dezz.status.widget.settings.SettingsDestinationCatalog.Group.SMART_HOME));
                break;
            case SCENARIOS:
            case INTENT_SCENARIOS:
                startActivity(SettingsHubActivity.intent(this,
                        dezz.status.widget.settings.SettingsDestinationCatalog.Group.AUTOMATION));
                break;
            case NOTIFICATION_ACCESS:
                startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
                break;
            case ALL_APPS:
            default: showAllApps(); break;
        }
    }

    private void openRecentsWithShell() {
        PrivilegedShell.get(this).runCommand("input keyevent 187",
                (output, error) -> {
                    if (error != null) runOnUiThread(() -> Toast.makeText(this,
                            "Включите спецвозможности для списка приложений",
                            Toast.LENGTH_LONG).show());
                });
    }

    private void setEditMode(boolean enabled) {
        if (enabled && navigationContentEditMode) setNavigationContentEditMode(false);
        if (enabled && mediaContentEditMode) setMediaContentEditMode(false);
        if (enabled && actionsContentEditMode) setActionsContentEditMode(false);
        editMode = enabled;
        if (mediaPanel != null) mediaPanel.setGlobalEditPreview(enabled);
        if (climatePanel != null) climatePanel.setEditorPreviewMode(enabled);
        if (vehicleInfoPanel != null) vehicleInfoPanel.setPreviewMode(enabled);
        if (informationPanel != null) informationPanel.setEditorPreviewMode(enabled);
        if (favoriteRoutesPanel != null) favoriteRoutesPanel.setPreviewMode(enabled);
        int snap = Math.max(1, preferences.launcherSnapPx.get());
        editorGrid.setStepPx(snap);
        editorGrid.setVisibility(enabled && preferences.launcherShowGrid.get()
                ? View.VISIBLE : View.GONE);
        doneButton.setText("Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || navigationContentEditMode || mediaContentEditMode
                || actionsContentEditMode ? View.VISIBLE : View.GONE);
        widgetCatalogButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        for (LauncherElementFrame frame : panels.values()) frame.setEditMode(false, snap);
        suppressSourcePanels();
        refreshGlobalElementVisibility();
        updateLauncherSafeArea();
        updateNavigation();
        if (vehicleInfoPanel != null && preferences.launcherVehicleInfoVisible.get()) {
            setPanelVisibility(LauncherLayoutStore.VEHICLE_INFO,
                    enabled || vehicleInfoPanel.hasDisplayableSample());
        }
        if (informationPanel != null && preferences.launcherInformationVisible.get()) {
            setPanelVisibility(LauncherLayoutStore.INFORMATION,
                    enabled || informationPanel.hasConfiguredItems());
        }
        if (enabled) showNavigationEditorSamples();
        workspace.post(() -> {
            activateGlobalElements();
            syncGlobalElements();
            syncLauncherHorizontalGroups();
            refreshGlobalElementVisibility();
        });
        Toast.makeText(this, enabled
                ? "Тащите любой элемент по всему HOME; размер меняется за четыре угла"
                : "Компоновка сохранена", Toast.LENGTH_SHORT).show();
    }

    private void setNavigationContentEditMode(boolean enabled) {
        if (enabled && editMode) setEditMode(false);
        if (enabled && mediaContentEditMode) setMediaContentEditMode(false);
        if (enabled && actionsContentEditMode) setActionsContentEditMode(false);
        navigationContentEditMode = enabled;
        if (navigationContentEditOverlay != null) {
            navigationContentEditOverlay.setEditing(enabled);
        }
        editorGrid.setVisibility(View.GONE);
        doneButton.setText(enabled
                ? "Готово · сохранить элементы навигации"
                : "Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || editMode || mediaContentEditMode
                || actionsContentEditMode ? View.VISIBLE : View.GONE);
        widgetCatalogButton.setVisibility(View.GONE);
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
        suppressSourcePanels();
        refreshGlobalElementVisibility();
        Toast.makeText(this, enabled
                ? "Тащите элементы; потяните любой выделенный угол для размера"
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
        if (enabled && actionsContentEditMode) setActionsContentEditMode(false);
        mediaContentEditMode = enabled;
        if (mediaPanel != null) {
            if (enabled) mediaPanel.reloadConfig();
            mediaPanel.setInPlaceEditMode(enabled);
        }
        editorGrid.setVisibility(View.GONE);
        doneButton.setText(enabled
                ? "Готово · сохранить элементы медиаблока"
                : "Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || editMode || navigationContentEditMode
                || actionsContentEditMode ? View.VISIBLE : View.GONE);
        widgetCatalogButton.setVisibility(View.GONE);
        if (enabled) {
            setPanelVisibility(LauncherLayoutStore.MEDIA, true);
        } else {
            setPanelVisibility(LauncherLayoutStore.MEDIA,
                    preferences.launcherMediaVisible.get() && hasMediaPanelContent());
        }
        reconcileMediaController();
        updateLauncherSafeArea();
        suppressSourcePanels();
        refreshGlobalElementVisibility();
        Toast.makeText(this, enabled
                ? "Тащите элементы; любой из четырёх углов изменяет размер"
                : "Сетка медиаблока сохранена", Toast.LENGTH_SHORT).show();
    }

    /** Edits the actual mixed buttons/smart-home grid without touching the outer panel rectangle. */
    private void setActionsContentEditMode(boolean enabled) {
        if (enabled && editMode) setEditMode(false);
        if (enabled && navigationContentEditMode) setNavigationContentEditMode(false);
        if (enabled && mediaContentEditMode) setMediaContentEditMode(false);
        actionsContentEditMode = enabled;
        if (enabled && shortcutStore != null) {
            shortcutStore.load();
            refreshShortcutGrid();
        }
        if (actionsContentEditOverlay != null) {
            actionsContentEditOverlay.setEditing(enabled);
        }
        editorGrid.setVisibility(View.GONE);
        doneButton.setText(enabled
                ? "Готово · сохранить сетку кнопок"
                : "Готово · закрепить компоновку");
        doneButton.setVisibility(enabled || editMode || navigationContentEditMode
                || mediaContentEditMode ? View.VISIBLE : View.GONE);
        widgetCatalogButton.setVisibility(View.GONE);
        if (enabled) {
            setPanelVisibility(LauncherLayoutStore.ACTIONS, true);
        } else {
            setPanelVisibility(LauncherLayoutStore.ACTIONS,
                    preferences.launcherActionsVisible.get()
                            && hasSimplePanelContent(LauncherLayoutStore.ACTIONS));
        }
        updateLauncherSafeArea();
        suppressSourcePanels();
        refreshGlobalElementVisibility();
        Toast.makeText(this, enabled
                ? "Тащите плитки по сетке; потяните любой из четырёх углов. "
                + "Нажатие на плитку меняет размер её иконки."
                : "Сетка кнопок сохранена", Toast.LENGTH_SHORT).show();
    }

    private void updateMedia(@NonNull LauncherMediaController.Snapshot state) {
        if (mediaPanel != null) mediaPanel.setSnapshot(state);
        requestGlobalElementRefresh();
    }

    private void updateNavigation() {
        // HA1048 regressed by doing this read before posted panel initialization. The repository
        // parses several JSON values and may decode four navigation PNGs; doing so on the shared
        // main Looper can freeze both HOME and the status row. Coalesce bursts on a worker.
        if (!panelsInitialized || isFinishing() || isDestroyed()
                || (!launcherRuntimeStageReached(5) && !navigationContentEditMode)
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
        // Coalesced after this stack returns, so every field changed below is projected together.
        requestGlobalElementRefresh();
        navigationDynamicRefresh = false;
        boolean showFavorites = !editMode && !navigationContentEditMode
                && CombinedNavigationPanelPolicy.showFavorites(
                state.routeActive, favoriteRoutesAvailable);
        if (favoriteRoutesPanel != null) {
            favoriteRoutesPanel.setVisibility(editMode || showFavorites
                    ? View.VISIBLE : View.GONE);
        }
        if (navigationRouteContent != null) {
            navigationRouteContent.setVisibility(editMode || !showFavorites
                    ? View.VISIBLE : View.GONE);
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
            if (editMode || navigationContentEditMode) showNavigationEditorSamples();
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
        if (editMode) showNavigationEditorSamples();
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
                || (!launcherRuntimeStageReached(5) && !navigationContentEditMode)
                || (!isCombinedNavigationEnabled() && !editMode
                && !navigationContentEditMode)) return;
        // Repository writes already emit ACTION_UPDATED. Poll only truly dynamic graphics whose
        // OEM publishers can miss callbacks; an idle route has no permanent 30-second loop.
        if (navigationDynamicRefresh) {
            navigationUiHandler.postDelayed(navigationUiRefresh,
                    NAVIGATION_DYNAMIC_REFRESH_MS);
        }
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
        allAppsEditMode = false;
        allAppsUninstallInProgress = false;
        FrameLayout root = new FrameLayout(this);
        root.setPadding(dp(24), dp(18), dp(24), dp(24));
        root.setBackgroundColor(Color.rgb(10, 13, 18));
        TextView title = text(24, Color.WHITE, true);
        title.setText("Все приложения");
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new FrameLayout.LayoutParams(
                wrapContent(), dp(72), Gravity.TOP | Gravity.START));

        MaterialButton close = new MaterialButton(this);
        close.setText("✕");
        close.setTextSize(24);
        close.setTextColor(Color.WHITE);
        close.setAllCaps(false);
        close.setMinWidth(0);
        close.setInsetTop(0);
        close.setInsetBottom(0);
        close.setContentDescription("Закрыть список приложений");
        root.addView(close, new FrameLayout.LayoutParams(
                dp(72), dp(72), Gravity.TOP | Gravity.END));

        MaterialButton done = new MaterialButton(this);
        done.setText("Готово");
        done.setTextSize(16);
        done.setTextColor(Color.WHITE);
        done.setAllCaps(false);
        done.setMinWidth(0);
        done.setInsetTop(0);
        done.setInsetBottom(0);
        done.setVisibility(View.GONE);
        done.setContentDescription("Завершить удаление приложений");
        root.addView(done, new FrameLayout.LayoutParams(
                dp(132), dp(64), Gravity.TOP | Gravity.END));

        GridView grid = new GridView(this);
        grid.setNumColumns(Math.max(3,
                Math.min(8, preferences.launcherAllAppsColumns.get())));
        grid.setPadding(dp(16), dp(16), dp(16), dp(16));
        int gap = Math.max(0, Math.min(40, preferences.launcherAllAppsGapPx.get()));
        grid.setVerticalSpacing(dp(gap));
        grid.setHorizontalSpacing(dp(gap));
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                matchWidth(), matchHeight());
        gridParams.topMargin = dp(84);
        root.addView(grid, gridParams);
        boolean overlay = Permissions.checkOverlayPermission(this);
        Context dialogContext = overlay ? getApplicationContext() : this;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(dialogContext,
                android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                .setView(root)
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
        allAppsGrid = grid;
        allAppsTitle = title;
        allAppsDone = done;
        refreshAllAppsDrawerContents();
        close.setOnClickListener(view -> dismissAllAppsDialog());
        done.setOnClickListener(view -> setAllAppsEditMode(false));
        dialog.setOnDismissListener(ignored -> {
            if (allAppsDialog != dialog) return;
            allAppsDialog = null;
            allAppsGrid = null;
            allAppsTitle = null;
            allAppsDone = null;
            allAppsEditMode = false;
            allAppsUninstallInProgress = false;
        });
        try {
            dialog.show();
            Window shown = dialog.getWindow();
            if (shown != null) {
                shown.setBackgroundDrawable(new ColorDrawable(Color.rgb(10, 13, 18)));
                shown.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
            }
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
        allAppsGrid = null;
        allAppsTitle = null;
        allAppsDone = null;
        allAppsEditMode = false;
        allAppsUninstallInProgress = false;
        if (dialog == null) return;
        try { dialog.dismiss(); }
        catch (RuntimeException ignored) {}
    }

    private void setAllAppsEditMode(boolean enabled) {
        allAppsEditMode = enabled;
        TextView title = allAppsTitle;
        if (title != null) title.setText(enabled
                ? "Удаление приложений" : "Все приложения");
        MaterialButton done = allAppsDone;
        if (done != null) done.setVisibility(enabled ? View.VISIBLE : View.GONE);
        GridView grid = allAppsGrid;
        if (grid != null && grid.getAdapter() instanceof AppAdapter) {
            ((AppAdapter) grid.getAdapter()).setEditMode(enabled);
        }
    }

    private void setAllAppsConfirmationActive(boolean active) {
        android.app.AlertDialog dialog = allAppsDialog;
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null) return;
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }

    private void refreshAllAppsDrawerContents() {
        GridView grid = allAppsGrid;
        if (grid == null || appCatalog == null) return;
        AppAdapter adapter = new AppAdapter(appCatalog.allVisible(), false,
                new AllAppsCallbacks() {
                    @Override public void launch(@NonNull AppEntry entry) {
                        dismissAllAppsDialog();
                        launchApp(entry);
                    }

                    @Override public void enterEditMode() {
                        setAllAppsEditMode(true);
                    }

                    @Override public void uninstall(@NonNull AppEntry entry) {
                        if (!AppDrawerUninstallPolicy.canUninstall(
                                LauncherActivity.this, entry.packageName,
                                entry.systemApp)) return;
                        lastAppCatalogLoadElapsed = 0L;
                        // Keep the exact grid/edit session visible behind Package Installer.
                        // Temporarily make only our window input-transparent so confirmation
                        // receives every tap; onResume restores interaction in the same overlay.
                        setAllAppsConfirmationActive(true);
                        allAppsUninstallInProgress = AppUninstallLauncher.request(
                                LauncherActivity.this, entry.packageName, entry.label);
                        if (!allAppsUninstallInProgress) {
                            setAllAppsConfirmationActive(false);
                        }
                    }
                });
        adapter.setEditMode(allAppsEditMode);
        grid.setAdapter(adapter);
    }

    private void refreshFavorites() {
        if (favoritesGrid != null && appCatalog != null) {
            favoritesGrid.setAdapter(new AppAdapter(appCatalog.favorites(), true));
            // GridView creates its tagged tiles only after adapter layout. Reconcile them promptly;
            // the permanently suppressed source panel guarantees that this asynchronous gap cannot
            // expose the old panel-local applications grid.
            refreshGlobalElementsAfterWidgetChange();
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
        final boolean systemApp;
        @Nullable private volatile Drawable cachedIcon;

        AppEntry(String label, String packageName, ComponentName component,
                 boolean systemApp) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
            this.systemApp = systemApp;
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

    private static final class ShortcutTileBinding {
        final LauncherShortcutStore.Shortcut shortcut;
        final MaterialCardView card;
        final ImageView icon;
        @Nullable final TextView titleLabel;
        @Nullable final TextView stateLabel;

        ShortcutTileBinding(LauncherShortcutStore.Shortcut shortcut, MaterialCardView card,
                            ImageView icon, @Nullable TextView titleLabel,
                            @Nullable TextView stateLabel) {
            this.shortcut = shortcut;
            this.card = card;
            this.icon = icon;
            this.titleLabel = titleLabel;
            this.stateLabel = stateLabel;
        }
    }

    private interface AllAppsCallbacks {
        void launch(@NonNull AppEntry entry);
        void enterEditMode();
        void uninstall(@NonNull AppEntry entry);
    }

    private final class AppAdapter extends BaseAdapter {
        private final List<AppEntry> values;
        private final boolean cacheIcons;
        private final Map<String, FavoriteAppConfig> appearances;
        @Nullable private final AllAppsCallbacks allAppsCallbacks;
        private boolean drawerEditMode;

        AppAdapter(List<AppEntry> values, boolean cacheIcons) {
            this(values, cacheIcons, null);
        }

        AppAdapter(List<AppEntry> values, boolean cacheIcons,
                   @Nullable AllAppsCallbacks callbacks) {
            this.values = values;
            this.cacheIcons = cacheIcons;
            this.allAppsCallbacks = callbacks;
            // One JSON parse for this adapter instead of one full parse for every recycled cell.
            this.appearances = favoriteAppsConfigStore.appearanceSnapshot();
        }

        void setEditMode(boolean enabled) {
            if (drawerEditMode == enabled) return;
            drawerEditMode = enabled;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return values.size(); }
        @Override public AppEntry getItem(int position) { return values.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View reusable, ViewGroup parent) {
            AppEntry entry = getItem(position);
            FavoriteAppConfig appearance = appearances.get(entry.packageName);
            if (appearance == null) appearance = new FavoriteAppConfig(entry.packageName);
            AppDrawerTileView drawerCell = !cacheIcons
                    ? reusable instanceof AppDrawerTileView
                    ? (AppDrawerTileView) reusable : new AppDrawerTileView(LauncherActivity.this)
                    : null;
            LinearLayout tile = LauncherAppTileRenderer.render(
                    LauncherActivity.this,
                    drawerCell == null ? reusable : drawerCell.reusableContent(), entry.label,
                    entry.icon(LauncherActivity.this, cacheIcons),
                    appearance, cacheIcons ? appsGridScalePercent
                            : Math.max(60, Math.min(180,
                            preferences.launcherAllAppsIconScalePercent.get())));
            if (cacheIcons) {
                LauncherGlobalElementTag.attach(tile, LauncherLayoutStore.APPS,
                        "app." + entry.component.flattenToShortString(),
                        "Приложение · " + entry.label);
                tile.setOnClickListener(view -> launchApp(entry));
                tile.setOnLongClickListener(view -> {
                    appCatalog.toggleFavorite(entry.packageName);
                    refreshFavorites();
                    return true;
                });
                return tile;
            }
            AllAppsCallbacks callbacks = allAppsCallbacks;
            if (callbacks == null || drawerCell == null) return tile;
            boolean uninstallable = AppDrawerUninstallPolicy.canUninstall(
                    LauncherActivity.this, entry.packageName, entry.systemApp);
            drawerCell.setContentDescription(entry.label
                    + (drawerEditMode && uninstallable ? ", можно удалить" : ""));
            drawerCell.bind(tile, drawerEditMode, uninstallable,
                    () -> callbacks.launch(entry),
                    callbacks::enterEditMode,
                    () -> callbacks.uninstall(entry));
            return drawerCell;
        }
    }

    private final class AppCatalog {
        private final Context context;
        private final List<AppEntry> apps = new ArrayList<>();
        AppCatalog(Context context) { this.context = context; }

        void reload() {
            apps.clear();
            List<LauncherAppCatalog.App> catalog =
                    LauncherAppCatalog.loadIncludingSystem(context);
            LauncherAppCatalog.ensureDefaultSystemVisibility(
                    context, preferences, catalog);
            for (LauncherAppCatalog.App app : catalog) {
                apps.add(new AppEntry(app.label, app.packageName, app.component,
                        app.systemApp));
            }
            ensureDefaultFavorites();
            // Preload only the handful of icons shown on HOME. The full application list keeps
            // lazy icons so dozens of adaptive drawables do not remain resident permanently.
            for (AppEntry favorite : favorites()) favorite.icon(context, true);
        }

        List<AppEntry> all() { return new ArrayList<>(apps); }

        List<AppEntry> allVisible() {
            Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
            List<AppEntry> result = new ArrayList<>();
            for (AppEntry app : apps) {
                if (!hidden.contains(app.component.flattenToString())) {
                    result.add(app);
                }
            }
            return result;
        }

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
