/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarTelemetryExporter;
import dezz.status.widget.databinding.OverlayStatusWidgetBinding;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.automation.ScenarioTriggerReceiver;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ConnectorActionDispatcher;
import dezz.status.widget.integration.ConnectorType;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.IntentScenarioController;
import dezz.status.widget.integration.LocalScenarioController;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.dim.DimMenuPanelService;
import dezz.status.widget.hud.HudPresentationService;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.MediaPlaybackHistoryStore;
import dezz.status.widget.launcher.information.StatusBarInformationCatalog;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntityCatalog;
import dezz.status.widget.ha.api.HaWebSocketConnector;
import dezz.status.widget.mqtt.MqttController;
import dezz.status.widget.navigation.NavigationHudEndpointService;
import dezz.status.widget.shade.SystemShadeService;
import dezz.status.widget.phone.PhoneAppIconStore;
import dezz.status.widget.phone.PhoneBluetoothIndicatorPolicy;
import dezz.status.widget.phone.PhoneConnectorController;
import dezz.status.widget.phone.PhoneLowBatteryAlertPolicy;
import dezz.status.widget.phone.PhoneNotificationAutomation;
import dezz.status.widget.phone.PhoneNotificationDeferralPolicy;
import dezz.status.widget.phone.PhoneNotificationDeferralQueue;
import dezz.status.widget.phone.PhoneNetworkTypePolicy;
import dezz.status.widget.phone.PhoneNotificationLockPolicy;
import dezz.status.widget.phone.PhoneStatusBarPolicy;
import dezz.status.widget.phone.PhoneIndicatorVisualPolicy;
import dezz.status.widget.phone.PhoneSprutPresenceExporter;
import dezz.status.widget.popup.PopupOverlayController;
import dezz.status.widget.popup.PopupOverlayManager;
import dezz.status.widget.popup.PopupOverlayConfig;
import dezz.status.widget.popup.PopupOverlayConfigStore;
import dezz.status.widget.popup.PopupItemConfig;
import dezz.status.widget.popup.PopupItemConfigStore;
import dezz.status.widget.sprut.SprutCatalog;
import dezz.status.widget.sprut.SprutHubController;

public class WidgetService extends Service {
    /** Same-process, event-driven presentation invalidation for surfaces outside this Service. */
    public interface AutomationPresentationListener {
        void onAutomationPresentationChanged(@NonNull String scope,
                                             @NonNull Set<String> ids);
    }
    enum GnssState {
        OFF, BAD, GOOD
    }

    enum WiFiState {
        OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET
    }

    enum BluetoothState {
        OFF, NO_DEVICE, CONNECTED
    }

    // Icon designs: 4 Wi-Fi states, 3 GNSS states, 3 Bluetooth states.
    private static final int[][] DESIGN_CLASSIC = {
            {
                    R.drawable.ic_status_wifi_off,
                    R.drawable.ic_status_wifi_no_internet,
                    R.drawable.ic_status_wifi_whitelist,
                    R.drawable.ic_status_wifi_internet
            },
            { R.drawable.ic_status_iphone_gps_off, R.drawable.ic_status_iphone_gps_searching,
                    R.drawable.ic_status_iphone_gps_active },
            { R.drawable.ic_status_iphone_bluetooth_off,
                    R.drawable.ic_status_iphone_bluetooth_outline,
                    R.drawable.ic_status_iphone_bluetooth_solid }
    };
    private static final int[][] DESIGN_SOLID = {
            {
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            { R.drawable.ic_status_iphone_gps_off, R.drawable.ic_status_iphone_gps_searching,
                    R.drawable.ic_status_iphone_gps_active },
            { R.drawable.ic_status_iphone_bluetooth_off,
                    R.drawable.ic_status_iphone_bluetooth_outline,
                    R.drawable.ic_status_iphone_bluetooth_solid }
    };
    private static final int[][] DESIGN_BARS = {
            {
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            },
            { R.drawable.ic_status_iphone_gps_off, R.drawable.ic_status_iphone_gps_searching,
                    R.drawable.ic_status_iphone_gps_active },
            { R.drawable.ic_status_iphone_bluetooth_off,
                    R.drawable.ic_status_iphone_bluetooth_outline,
                    R.drawable.ic_status_iphone_bluetooth_solid }
    };
    private static final int[][][] ICON_DESIGNS = { DESIGN_CLASSIC, DESIGN_SOLID, DESIGN_BARS };

    private static final int ICON_TYPE_WIFI = 0;
    private static final int ICON_TYPE_GNSS = 1;
    private static final int ICON_TYPE_BT = 2;

    private static final int WIDGET_MODE_FLOATING = 0;
    private static final int WIDGET_MODE_STATUS_BAR = 1;

    // Icon style indices (must match strings.xml/icon_styles array order).
    private static final int STYLE_MONO = 0;
    private static final int STYLE_COLOR = 1;

    private static final long INTERNET_PROBE_INTERVAL_MS = 30_000L;

    /** Cross-fade duration for the entire overlay (show/hide / per-app hide). */
    private static final int OVERLAY_FADE_DURATION_MS = 500;
    /** Cold attach is a near-immediate reveal; later hide/show keeps the calmer 500 ms. */
    private static final int INITIAL_OVERLAY_FADE_DURATION_MS = 90;
    private static final int INITIAL_OVERLAY_FALLBACK_GRACE_MS = 160;
    private static final long OVERLAY_ATTACH_RETRY_MS = 1_500L;
    private static final long MAX_OVERLAY_ATTACH_RETRY_MS = 30_000L;
    /**
     * Duration of the combined Fade + ChangeBounds transition that handles per-brick
     * visibility flips. See {@link #beginVisibilityTransition} for the "window-buffer"
     * trick that makes this transition stay inside a stable window rectangle.
     */
    private static final int BRICK_TRANSITION_DURATION_MS = 450;
    /** Duration of the alpha animation used when a brick is hidden in keeps-space mode. */
    private static final int BRICK_ALPHA_DURATION_MS = 300;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_FIX_DEGRADED_AFTER_MS = 5_000L;
    private static final long GNSS_FIX_OFF_AFTER_MS = 10_000L;
    private static final long GNSS_LOCATION_INTERVAL_MS = 2_000L;
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    private static final long SYSTEM_CONDITION_REFRESH_INTERVAL_MS = 60_000L;
    /** Moves the process-independent dead-man alarm before its nine-second deadline. */
    private static final long SERVICE_WATCHDOG_HEARTBEAT_MS = 3_000L;
    /** A real constructor/provider failure gets a bounded retry instead of a tight loop. */
    private static final long INITIAL_INTEGRATION_RETRY_MS = 650L;
    /** A one-off vendor/Binder rejection is retried without turning startup into a tight loop. */
    private static final int MAX_INITIAL_INTEGRATION_STAGE_RETRIES = 2;
    /** Cadence for advancing the media progress bar while a track is actively playing. 250ms
     *  is fast enough to look smooth on a thin bar and slow enough to not show up in profilers. */
    // One repaint per second is visually sufficient for a compact status-row progress line and
    // halves MediaSession polling/layout invalidation versus HA1048 on low-end head units.
    private static final long MEDIA_PROGRESS_TICK_MS = 1_000L;
    /** More than the connector cache, so removing the newest item can never replay an older one. */
    private static final int MAX_OBSERVED_PHONE_NOTIFICATIONS = 128;
    /** Burst deliveries are intentionally readable and deterministic, one card per second. */
    private static final long PHONE_NOTIFICATION_QUEUE_SLOT_MS = 1_000L;
    /** Gap between the play/pause indicator and the text it precedes, as a fraction of that
     *  text's size — same rationale as the icon's own size: it must track the font sliders. */
    private static final float STATE_ICON_GAP_RATIO = 0.25f;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 2_000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
    private static final long FOREGROUND_FAILURE_LOG_INTERVAL_MS = 10_000L;
    /** Longer than two observer refresh periods; prevents a dead Binder from pinning a hide. */
    private static final long ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS = 6_000L;
    private static final long ECARX_NAVIGATOR_OPTIMISTIC_GRACE_MS = 1_800L;
    private static final long[] ECARX_NAVIGATOR_OPTIMISTIC_RETRY_OFFSETS_MS = {
            0L, 120L, 400L, 900L, 1_500L
    };
    private static final String GNSSSHARE_CLIENT_PACKAGE = "dezz.gnssshare.client";
    private static final String GNSSSHARE_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    /** Satellite count extra. A value of {@code -1} means "no satellite data" (badge hidden). */
    private static final String GNSSSHARE_EXTRA_SATELLITES_COUNT = "count";
    /**
     * Optional positioning-mode extra, treated as a bit mask (absent / 0 = normal satellite
     * fixing). The two flags are independent — dead reckoning and spoofing-detected can each be
     * set on their own or together (3 = dead reckoning entered because of a detected spoof).
     */
    private static final String GNSSSHARE_EXTRA_MODE = "mode";
    private static final int GNSSSHARE_MODE_DR = 1;     // bit 0: position is dead-reckoned
    private static final int GNSSSHARE_MODE_SPOOF = 2;  // bit 1: GPS spoofing detected
    private static final long GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS = 30_000L;

    private static WidgetService instance;
    /** Process-wide ownership fence for retained-state workers across service replacement. */
    private static final AtomicLong STARTUP_STATE_OWNER = new AtomicLong();

    private Preferences prefs;
    private long startupStateOwnerToken;
    private AutomationStateStore automationStates;
    private ConnectorValueRegistry connectorValues;
    private volatile LocalScenarioController scenarioController;
    private volatile IntentScenarioController intentScenarioController;
    /**
     * Cold explicit commands may reach the foreground service while the post-visible controller
     * lane is still warming up. Retain only a small in-process queue: every entry keeps the
     * receiver-time monotonic deadline and is revalidated by IntentScenarioController before use.
     */
    private static final int MAX_PENDING_INTENT_SCENARIO_COMMANDS = 16;
    private static final long TEMPORARY_SCENARIO_HOST_RECHECK_MS = 1_000L;
    private static final long TEMPORARY_SCENARIO_HOST_MAX_MS = 16_000L;
    private final ArrayDeque<Intent> pendingIntentScenarioCommands = new ArrayDeque<>();
    private boolean temporaryScenarioHeadlessHost;
    private final Runnable explicitScenarioRuntimeOverrideRecheck = () ->
            reconcileExplicitScenarioRuntimeOverride(false);
    private final Runnable explicitScenarioRuntimeOverrideExpiry = () ->
            reconcileExplicitScenarioRuntimeOverride(true);
    private final Runnable temporaryScenarioHostRecheck = () ->
            reconcileTemporaryScenarioHeadlessHost(false);
    private final Runnable temporaryScenarioHostExpiry = () ->
            reconcileTemporaryScenarioHeadlessHost(true);
    private volatile ConnectorActionDispatcher actionDispatcher;
    private HaBrickConfigStore haConfigs;
    private volatile HaApiController haApiController;
    private volatile MqttController mqttController;
    private volatile SprutHubController sprutController;
    private volatile PhoneConnectorController phoneController;
    private volatile PhoneSprutPresenceExporter phonePresenceExporter;
    private volatile PhoneSprutPresenceExporter phoneAncsPresenceExporter;
    private volatile CarTelemetryExporter carTelemetryExporter;
    private PopupOverlayManager popupOverlay;
    /** Parsed only when settings change; connector packets must never reparse the JSON document. */
    private List<HaBrickConfig> configuredMainBricks = Collections.emptyList();
    @Nullable private String configuredMainBricksJson;
    /** Startup worker projections used by the main-only visual/listener pass. */
    private Set<BrickType> configuredPopupBuiltinTypes = Collections.emptySet();
    @Nullable private String configuredPopupOverlaysJson;
    @Nullable private String configuredPopupItemsJson;
    private Set<BrickType> configuredDriverInformationTypes = Collections.emptySet();
    @Nullable private String configuredDriverInformationJson;
    private boolean configuredDriverPanelEnabled;
    private final Object automationUiLock = new Object();
    private static final int MAX_AUTOMATION_PRESENTATION_LISTENERS = 8;
    private final Map<String, Set<String>> pendingAutomationUi = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<AutomationPresentationListener>
            automationPresentationListeners =
            new CopyOnWriteArrayList<>();
    private boolean automationUiRefreshScheduled;
    /** Fresh visual-only host is admitted, but controller/vendor work still belongs to host phase. */
    private boolean automaticRuntimeParked;
    /** Bounded authenticated command may use runtime without opening the automatic host barrier. */
    private boolean explicitScenarioRuntimeOverride;
    private boolean automaticLifecycleQuiet;
    private boolean automaticSurfaceReconcilePending;
    /** Only a process surviving QuickBoot needs an immediate WindowManager revalidation. */
    private boolean automaticSurfaceRevalidationRequired;
    /** Exact host phase was accepted; resume waits only for its current replacement root. */
    private boolean automaticHostReleaseAfterVisible;
    private int automaticLifecycleResumeGeneration;
    private int automaticLifecycleTeardownStage;
    private volatile boolean destroyed;
    private final Runnable automaticLifecycleQuietTeardown =
            this::runNextAutomaticLifecycleQuietTeardown;

    private void runNextAutomaticLifecycleQuietTeardown() {
        if (destroyed || !automaticLifecycleQuiet) return;
        switch (automaticLifecycleTeardownStage++) {
            case 0:
                runIntegrationStep("quiet phone", () -> {
                    if (phoneController != null) phoneController.stop();
                });
                break;
            case 1:
                runIntegrationStep("quiet MQTT", () -> {
                    if (mqttController != null) mqttController.pauseForAutomaticLifecycle();
                });
                break;
            case 2:
                runIntegrationStep("quiet Home Assistant", () -> {
                    if (haApiController != null) haApiController.pauseForAutomaticLifecycle();
                });
                break;
            case 3:
                runIntegrationStep("quiet Sprut.hub", () -> {
                    if (sprutController != null) sprutController.pauseForAutomaticLifecycle();
                });
                break;
            default:
                return;
        }
        mainHandler.post(automaticLifecycleQuietTeardown);
    }
    private final Runnable automaticVisualSurfaceRevalidation = () -> {
        if (destroyed || !automaticSurfaceRevalidationRequired || prefs == null) return;
        automaticSurfaceRevalidationRequired = false;
        if (!prefs.widgetEnabled.get() || !Permissions.allPermissionsGranted(this)) return;
        revalidateStatusOverlayWindowOnly("immediate QuickBoot surface revalidation");
    };
    private final Runnable automationUiRefresh = () -> {
        if (automaticSurfaceRefreshSuppressed()) {
            synchronized (automationUiLock) {
                automationUiRefreshScheduled = false;
            }
            return;
        }
        Map<String, Set<String>> changed = new LinkedHashMap<>();
        synchronized (automationUiLock) {
            for (Map.Entry<String, Set<String>> entry : pendingAutomationUi.entrySet()) {
                changed.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            pendingAutomationUi.clear();
            automationUiRefreshScheduled = false;
        }
        if (WidgetService.this.destroyed || changed.isEmpty()) return;
        dispatchAutomationPresentationChanges(changed);
        boolean affectsStatusRow = changed.containsKey(AutomationContract.SCOPE_MAIN)
                || changed.containsKey(AutomationContract.SCOPE_BUILTIN);
        boolean affectsPhoneNotification = false;
        Set<String> changedPopupItems = changed.get(AutomationContract.SCOPE_POPUP);
        if (changedPopupItems != null) {
            for (String id : changedPopupItems) {
                if (PhoneNotificationAutomation.isFieldAutomationId(id)) {
                    affectsPhoneNotification = true;
                    break;
                }
            }
        }
        if (popupOverlay != null) {
            for (Map.Entry<String, Set<String>> entry : changed.entrySet()) {
                for (String id : entry.getValue()) {
                    popupOverlay.onStateChanged(entry.getKey(), id);
                }
            }
        }
        if (changed.containsKey(AutomationContract.SCOPE_DRIVER)
                && prefs != null && prefs.driverPanelEnabled.get()) {
            DriverPanelService.apply(this);
        }
        if (changed.containsKey(AutomationContract.SCOPE_HUD)
                && (prefs.hudPanelAutostart.get()
                || HudPresentationService.isRunning(this))) {
            HudPresentationService.notifyAutomationChanged(this);
        }
        // Popup windows have an independent WindowManager lifecycle. A failed/retrying status-row
        // attachment must not discard their connector updates.
        if (WidgetService.this.binding == null) return;
        if (changed.containsKey(AutomationContract.SCOPE_MAIN)) renderHomeAssistantBricks();
        if (affectsPhoneNotification) refreshActivePhoneNotificationForConditions();
        // A popup-only temperature/sensor stream must not remeasure and animate the independent
        // status row. HA1048 did that for every packet even when no status brick had changed.
        if (affectsStatusRow) applyBrickVisibility(currentBrickSet());
    };
    private final AtomicBoolean crossSourceRuleRefreshScheduled = new AtomicBoolean();
    private final ConnectorValueRegistry.Listener crossSourceRuleListener =
            changedValues -> scheduleCrossSourceRuleRefresh();
    /** Latest immutable PHONE snapshot projected into the configurable status-row brick. */
    private final Map<String, ConnectorValue> phoneStatusValues = new LinkedHashMap<>();
    @Nullable
    private PhoneStatusBarPolicy.NotificationPresentation activePhoneNotification;
    /** Base field selection captured with the active delivery; local conditions refine it. */
    @NonNull
    private Set<String> activePhoneNotificationFields = Collections.emptySet();
    @Nullable
    private String activePhoneBatteryAlertText;
    @Nullable
    private String activePhoneBatteryAlertColor;
    private boolean phoneLowBatteryAlertLatched;
    private boolean phoneLowBatteryAlertLatched2;
    /** A warning remains pending until at least one configured destination really presents it. */
    private boolean phoneLowBatteryAlertPending;
    private boolean phoneLowBatteryAlertPending2;
    /** Confirmed ECARX 360°-camera / parktronic ownership of the vehicle display. */
    private boolean phoneExternalOverlayActive;
    private boolean phoneVehicleOverlayActive;
    private boolean phoneVehicleOverlayListenerInstalled;
    private final CarIntegration.ExternalOverlayListener phoneVehicleOverlayListener =
            this::onVehicleExternalOverlayChanged;
    private final Set<String> observedPhoneNotificationKeys = new LinkedHashSet<>();
    private final ArrayDeque<QueuedPhoneNotification> queuedPhoneNotifications =
            new ArrayDeque<>();
    /** Notifications held only while a configured full-screen app owns the head-unit display. */
    private final PhoneNotificationDeferralQueue<QueuedPhoneNotification>
            deferredPhoneNotifications = new PhoneNotificationDeferralQueue<>();
    private int deferredPhoneNotificationOverflowCount;
    private long deferredPhoneNotificationOverflowStartedElapsed;
    private int queuedPhoneNotificationOverflowCount;
    private boolean phoneNotificationBurstActive;
    private long activePhoneNotificationExpiresAt;
    private long activePhonePopupNotificationExpiresAt;
    @Nullable
    private PhoneStatusBarPolicy.NotificationPresentation activePhonePopupNotification;
    private boolean phoneNotificationOverlayPaused;
    private long pausedPhoneNotificationRemainingMs;
    private long pausedPhonePopupRemainingMs;
    private boolean pausedPhoneNotificationQueueAdvance;
    private boolean activePhoneLowBatteryPopup;
    private boolean phoneNotificationPopupConfigured;
    private int mediaDurationVisibilityBeforePhoneNotification = View.GONE;
    private int mediaProgressVisibilityBeforePhoneNotification = View.GONE;
    private final ConnectorValueRegistry.Listener phoneStatusListener =
            changedValues -> postPhoneValuesChanged(new ArrayList<>(changedValues));
    private final Runnable phoneNotificationExpiry = new Runnable() {
        @Override public void run() {
            if (destroyed || phoneNotificationOverlayPaused
                    || !hasActivePhoneStatusAlert()) return;
            long remaining = activePhoneNotificationExpiresAt
                    - android.os.SystemClock.elapsedRealtime();
            if (remaining > 0L) {
                mainHandler.postDelayed(this, remaining);
                return;
            }
            clearPhoneStatusNotification(true);
            if (phoneNotificationBurstActive && !queuedPhoneNotifications.isEmpty()) {
                mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
                mainHandler.post(phoneNotificationQueueAdvance);
            }
            if (binding != null) {
                updateMediaInfo();
                applyBrickVisibility(currentBrickSet());
            }
            schedulePopupRefresh();
        }
    };
    private final Runnable phonePopupNotificationExpiry = new Runnable() {
        @Override public void run() {
            if (destroyed || phoneNotificationOverlayPaused
                    || activePhonePopupNotificationExpiresAt <= 0L) return;
            long remaining = activePhonePopupNotificationExpiresAt
                    - android.os.SystemClock.elapsedRealtime();
            if (remaining > 0L) {
                mainHandler.postDelayed(this, remaining);
                return;
            }
            clearPhonePopupNotification();
        }
    };
    private final Runnable phoneNotificationQueueAdvance = new Runnable() {
        @Override public void run() {
            if (destroyed || phoneNotificationOverlayPaused
                    || !phoneNotificationBurstActive) return;
            long batteryRemaining = activePhoneLowBatteryRemaining();
            if (batteryRemaining > 0L) {
                mainHandler.postDelayed(this, batteryRemaining);
                return;
            }
            QueuedPhoneNotification next = queuedPhoneNotifications.pollFirst();
            if (next == null) {
                if (queuedPhoneNotificationOverflowCount <= 0) {
                    finishPhoneNotificationBurst();
                    return;
                }
                int overflow = queuedPhoneNotificationOverflowCount;
                queuedPhoneNotificationOverflowCount = 0;
                next = phoneNotificationOverflowDelivery(overflow);
            }
            boolean presented = presentPhoneNotification(next);
            if (!presented) {
                onPhoneNotificationDeliveryDropped(next);
                if (queuedPhoneNotifications.isEmpty()
                        && queuedPhoneNotificationOverflowCount <= 0) {
                    finishPhoneNotificationBurst();
                }
                else mainHandler.post(this);
                return;
            }
            if (queuedPhoneNotifications.isEmpty()
                    && queuedPhoneNotificationOverflowCount <= 0) {
                releasePhoneNotificationBurstToConfiguredExpiry();
                return;
            }
            long nextSlot = SystemClock.elapsedRealtime()
                    + PHONE_NOTIFICATION_QUEUE_SLOT_MS;
            holdPhoneNotificationDestinationsUntil(nextSlot);
            mainHandler.postDelayed(this, PHONE_NOTIFICATION_QUEUE_SLOT_MS);
        }
    };
    /** Exactly one callback is armed for the oldest (therefore nearest) hold deadline. */
    private final Runnable phoneNotificationDeferralDeadline =
            this::reconcileDeferredPhoneNotifications;
    private final Runnable crossSourceRuleRefresh = () -> {
        crossSourceRuleRefreshScheduled.set(false);
        if (destroyed) return;
        // RuleSet.sourceReference is connector-neutral. Re-project only those explicit
        // dependencies after any provider update, so an HA value can recolor/hide a Sprut tile
        // without waiting for the Sprut characteristic itself to change (and vice versa).
        if (mqttController != null) mqttController.reapplyCrossSourceBindings();
        if (sprutController != null) sprutController.reapplyCrossSourceBindings();
        if (haApiController != null) haApiController.reapplyCrossSourceBindings();
    };

    private void scheduleCrossSourceRuleRefresh() {
        if (destroyed || !crossSourceRuleRefreshScheduled.compareAndSet(false, true)) return;
        mainHandler.postDelayed(crossSourceRuleRefresh, 50L);
    }

    private void refreshActivePhoneNotificationForConditions() {
        if (binding != null && activePhoneNotification != null) updateMediaInfo();
    }

    private void postPhoneValuesChanged(@NonNull List<ConnectorValue> immutableCopy) {
        mainHandler.post(() -> onPhoneValuesChanged(immutableCopy));
    }

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;
    private int overlayAttachAttempts;
    private boolean overlayAttachRetryScheduled;
    /** Invalidates animator/frame/fallback callbacks retained by an older WindowManager root. */
    private int overlayAttachGeneration;
    private int overlayVisibleGeneration = -1;
    private final Runnable overlayAttachRetry = () -> {
        overlayAttachRetryScheduled = false;
        if (destroyed || binding != null || !prefs.widgetEnabled.get()) return;
        if (!Permissions.allPermissionsGranted(this)) {
            // Location AppOps are shared with the status row but are not required by the
            // independently attached driver/HUD surfaces and the phone connector. Keep that host
            // alive while the status surface waits for permissions to be restored.
            if (WidgetServiceStarter.requiresHeadlessHost(prefs)) {
                ensureEnabledRuntime();
            } else {
                stopSelf();
            }
            return;
        }
        createOverlayView();
    };

    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private GnssState gnssState = GnssState.OFF;
    private WiFiState wifiState = WiFiState.OFF;
    /** 0 = disconnected, 1..4 = progressively stronger RSSI. */
    private int wifiSignalLevel;
    private BluetoothState bluetoothState = BluetoothState.OFF;
    private final Set<String> btConnectedAddrs = new HashSet<>();
    /** True while the current direct iPhone transport reports an active ANCS profile. */
    private boolean phoneAncsReady;
    private boolean btReceiverRegistered = false;
    /** Invalidates asynchronous profile snapshots after status tracking is stopped or reseeded. */
    private int bluetoothTrackingGeneration;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable serviceWatchdogHeartbeat = new Runnable() {
        @Override public void run() {
            if (destroyed || prefs == null
                    || !WidgetServiceStarter.requiresAutomaticIntegrationHost(prefs)) {
                WidgetServiceWatchdog.cancel(WidgetService.this);
                return;
            }
            WidgetServiceWatchdog.arm(WidgetService.this);
            mainHandler.postDelayed(this, SERVICE_WATCHDOG_HEARTBEAT_MS);
        }
    };
    /** Large retained-state JSON is rewritten off the UI lane at Android background priority. */
    private final ExecutorService startupStateWorker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            } catch (RuntimeException ignored) {
            }
            task.run();
        }, "status-startup-state");
        thread.setDaemon(true);
        return thread;
    });
    /**
     * Connector startup is deliberately independent from the status-window binding. WindowManager
     * can transiently reject addView during boot while an already-running connector still needs to
     * re-read Keystore credentials on USER_UNLOCKED.
     */
    private boolean integrationsStarted;
    private boolean runtimeInitialized;
    private boolean integrationStartupScheduled;
    private boolean initialIntegrationStartupInProgress;
    /** One settings replay retained while the staged boot/unlock controller lane is busy. */
    private boolean integrationReconfigurePending;
    private boolean credentialRefreshPending;
    private boolean credentialRefreshScheduled;
    private int initialIntegrationStage;
    private int initialIntegrationStageRetryCount;
    /** Stage zero owns persistence until its worker result is committed on the main thread. */
    private boolean startupStateBarrierInFlight;
    /** Exactly one controller stage may prepare state on the serialized startup worker. */
    private boolean initialIntegrationWorkerInFlight;
    /**
     * A prepared graph is owned here between worker completion and its main-thread publication.
     * onDestroy atomically takes and cleans it before removing Handler callbacks.
     */
    private final AtomicReference<PreparedInitialIntegrationStage>
            pendingInitialIntegrationStage = new AtomicReference<>();
    private final Runnable initialIntegrationStageRunner = this::runNextInitialIntegrationStage;
    @Nullable private DeferredIntegrationStart deferredIntegrationStart;

    /** One attachment-owned Choreographer callback; stale roots cannot borrow a newer token. */
    private final class DeferredIntegrationStart
            implements Choreographer.FrameCallback, Runnable {
        final int attachmentGeneration;
        @NonNull final View root;

        DeferredIntegrationStart(int attachmentGeneration, @NonNull View root) {
            this.attachmentGeneration = attachmentGeneration;
            this.root = root;
        }

        @Override public void doFrame(long frameTimeNanos) {
            // Frame callbacks run before traversal. Posting once more lets traversal draw the
            // fully opaque status row before connector JSON/Keystore work begins.
            mainHandler.post(this);
        }

        @Override public void run() {
            if (deferredIntegrationStart != this) return;
            deferredIntegrationStart = null;
            if (!isCurrentOverlayAttachment(attachmentGeneration, root)
                    || root.getAlpha() < 0.999f) {
                integrationStartupScheduled = false;
                return;
            }
            StartupPerformanceTrace.mark("overlay_fully_visible");
            StatusWidgetApplication.notifyFirstUsefulSurface(WidgetService.this);
            // The surface and controller lanes are independent. A fresh status row is allowed to
            // draw during boot, but its fully-visible callback must never open the persisted host
            // generation by itself.
            if (!explicitScenarioRuntimeOverride
                    && StartupWorkCoordinator.shouldParkAutomaticRuntime(WidgetService.this)) {
                automaticRuntimeParked = true;
                StartupWorkCoordinator.ensureIntegrationHostScheduled(WidgetService.this);
            }
            boolean releasedParkedRuntime = false;
            if (automaticHostReleaseAfterVisible
                    && !StartupWorkCoordinator.shouldParkAutomaticRuntime(WidgetService.this)) {
                releasedParkedRuntime = automaticRuntimeParked || automaticLifecycleQuiet;
                automaticHostReleaseAfterVisible = false;
                resumeAutomaticLifecycleIntegrationsAfterQuiet();
            }
            boolean runtimeParked = automaticRuntimeParked || automaticLifecycleQuiet;
            if (!runtimeParked && !integrationsStarted
                    && !initialIntegrationStartupInProgress) {
                runInitialIntegrationStartup();
            }
            if (!runtimeParked && integrationsStarted && binding != null) {
                applyPreferences(false);
            }
            if (!runtimeParked && !automaticHostReleaseAfterVisible
                    && !releasedParkedRuntime) {
                finishAutomaticSurfaceReconcileIfReady();
            }
        }
    }
    /** Re-evaluates TTL/stale rules even when no new packet arrives. */
    private final Runnable automationFreshnessTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (binding != null) {
                renderHomeAssistantBricks();
                applyBrickVisibility(currentBrickSet());
            }
            applyPopupPreferencesSafely();
            if (!destroyed) mainHandler.postDelayed(this, 30_000L);
        }
    };
    private final Runnable popupRefresh = this::applyPopupPreferencesSafely;
    private static final long PHONE_EDITOR_PREVIEW_HANDOFF_MS = 450L;
    @Nullable private String activePhoneEditorPreviewOverlayId;
    @Nullable private String pendingPhoneEditorPreviewStopId;
    private final Runnable phoneEditorPreviewStop = () -> {
        String requested = pendingPhoneEditorPreviewStopId;
        pendingPhoneEditorPreviewStopId = null;
        if (destroyed || requested == null
                || !requested.equals(activePhoneEditorPreviewOverlayId)) return;
        if (popupOverlay != null) popupOverlay.stopEditorPreview(requested);
        activePhoneEditorPreviewOverlayId = null;
    };

    private void schedulePopupRefresh() {
        if (destroyed) return;
        mainHandler.removeCallbacks(popupRefresh);
        mainHandler.post(popupRefresh);
    }
    private LocationManager locationManager = null;
    private ConnectivityManager connectivityManager = null;
    private boolean gnssStatusCallbackRegistered;
    private boolean locationUpdatesRegistered;
    private boolean networkCallbackRegistered;
    private boolean wifiRssiReceiverRegistered;
    private boolean overlayAttached;
    /** Monotonic timestamp; wall-clock changes must not prolong or expire a GNSS fix. */
    private long lastLocationUpdateElapsed;

    private GradientDrawable background = null;
    private int bgColor = -1;
    private int bgCornerRadius = -1;

    private int touchSlop;

    private SimpleDateFormat timeFormat;
    private SimpleDateFormat dateFormat;
    private String currentDateFormatPattern;

    private UsageStatsManager usageStatsManager = null;
    private Set<String> hiddenInPackages;
    private String lastForegroundPackage;
    private long lastForegroundFailureLogElapsed;
    @Nullable private EcarxNavigatorWindowObserver ecarxNavigatorWindowObserver;
    /** Independent from StatusBarSurfaceContext's launch/focus/a11y fallback token. */
    @NonNull private NavigatorWindowSourcePolicy.VendorDecision ecarxNavigatorWindowDecision =
            NavigatorWindowSourcePolicy.VendorDecision.NONE;
    private long ecarxNavigatorWindowDecisionAtElapsed = -1L;
    private final Runnable ecarxNavigatorWindowLeaseExpiry =
            this::expireEcarxNavigatorWindowLease;
    private boolean ecarxNavigatorOptimisticConfirmationPending;
    private long ecarxNavigatorOptimisticStartedAtElapsed = -1L;
    private int ecarxNavigatorOptimisticRetryIndex;
    private final Runnable ecarxNavigatorOptimisticRetry =
            this::runEcarxNavigatorOptimisticRetry;
    private final Runnable ecarxNavigatorOptimisticExpiry =
            this::expireEcarxNavigatorOptimisticConfirmation;
    private boolean overlayHiddenByApp = false;

    /**
     * Number of in-flight transitions that have widened the WindowManager window to the
     * screen-width "buffer" so explicit visibility animations can play in a stable rectangle.
     * Incremented when a transition starts the buffer, decremented when it ends; the window is
     * restored to WRAP_CONTENT only when the counter reaches zero. Shared between:
     * <ul>
     *   <li>{@link #beginVisibilityTransition} (brick show/hide)</li>
     *   <li>The eager pre-empt in the size-change listener that catches a shrink before the
     *       window manager applies the new wrap-content bounds.</li>
     * </ul>
     */
    private int pendingBufferedTransitions = 0;

    /**
     * Closes the buffer opened eagerly by {@code onLayoutChange} when the content shrinks.
     * Posted with a delay slightly longer than {@link #BRICK_TRANSITION_DURATION_MS}, so the
     * window cannot remain screen-wide when a size hint is not followed by a visibility
     * transition.
     */
    private final Runnable shrinkBufferSafetyClose = this::endBufferedTransition;

    private Context themedContext;
    private int appliedThemePref = -1;

    /** Fires when the overlay's position or size changes so the settings UI can stay in sync. */
    public interface OverlayStateListener {
        void onOverlayStateChanged(int x, int y, int width, int height);
    }

    @Nullable private OverlayStateListener overlayStateListener;

    private MediaSessionManager mediaSessionManager;
    private int mediaBindingGeneration;
    private final List<MediaController> activeMediaControllers = new ArrayList<>();
    private final MediaController.Callback mediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
            updateMediaInfo();
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
            updateMediaInfo();
        }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsChangedListener =
            controllers -> {
                mediaBindingGeneration++;
                rebindMediaControllers(controllers);
            };

    private int satellitesCount = -1;
    private int gnssModeFlags = 0;
    private long satellitesCountTimestamp = 0;
    private boolean satelliteReceiverRegistered = false;
    private final BroadcastReceiver satelliteStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed || binding == null || prefs == null
                    || !prefs.widgetEnabled.get()) return;
            int count = intent.getIntExtra(GNSSSHARE_EXTRA_SATELLITES_COUNT, -1);
            int mode = intent.getIntExtra(GNSSSHARE_EXTRA_MODE, 0);
            Log.d(TAG, "GNSS Share satellites count: " + count + ", mode: " + mode);
            satellitesCount = count;
            gnssModeFlags = mode;
            // Monotonic clock (matches the postDelayed reset below), so a boot-time wall-clock
            // jump from GPS/NTP sync can't prematurely expire or freeze the freshness window.
            satellitesCountTimestamp = android.os.SystemClock.uptimeMillis();
            mainHandler.removeCallbacks(satellitesCountResetRunnable);
            mainHandler.postDelayed(satellitesCountResetRunnable, GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS);
            updateGnssStatus();
        }
    };
    private final Runnable satellitesCountResetRunnable = () -> {
        satellitesCount = -1;
        gnssModeFlags = 0;
        updateGnssStatus();
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (destroyed || binding == null || prefs == null
                    || !prefs.widgetEnabled.get()) return;
            String action = intent.getAction();
            if (action == null) return;
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    btConnectedAddrs.clear();
                } else if (state == BluetoothAdapter.STATE_ON) {
                    refreshBtConnectedFromProxies();
                }
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    btConnectedAddrs.add(device.getAddress());
                }
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && device.getAddress() != null) {
                    btConnectedAddrs.remove(device.getAddress());
                }
            }
            updateBluetoothStatus();
        }
    };

    private final Runnable updateDateTimeRunnable = new Runnable() {
        @Override
        public void run() {
            updateDateTime();
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(this, delay);
        }
    };
    /** Time-range conditions remain live even when the status row has no clock or is disabled. */
    private final Runnable systemConditionRefresh = new Runnable() {
        @Override public void run() {
            if (destroyed || !integrationsStarted) return;
            if (scenarioController != null) scenarioController.refreshSystemConditions();
            long now = System.currentTimeMillis();
            long delay = SYSTEM_CONDITION_REFRESH_INTERVAL_MS
                    - (now % SYSTEM_CONDITION_REFRESH_INTERVAL_MS);
            mainHandler.postDelayed(this, delay);
        }
    };

    private final Runnable foregroundAppCheckRunnable = new Runnable() {
        @Override
        public void run() {
            safeCheckForegroundApp("poll");
            if (!destroyed) {
                mainHandler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL_MS);
            }
        }
    };

    private final Runnable updateGnssStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !locationUpdatesRegistered || lastLocationUpdateElapsed <= 0L) {
                return;
            }
            long age = Math.max(0L,
                    SystemClock.elapsedRealtime() - lastLocationUpdateElapsed);
            if (age >= GNSS_FIX_OFF_AFTER_MS) {
                setGnssStatus(GnssState.OFF);
                return;
            }
            if (age >= GNSS_FIX_DEGRADED_AFTER_MS) {
                setGnssStatus(GnssState.BAD);
            }
            long nextBoundary = age < GNSS_FIX_DEGRADED_AFTER_MS
                    ? GNSS_FIX_DEGRADED_AFTER_MS : GNSS_FIX_OFF_AFTER_MS;
            mainHandler.postDelayed(this, Math.max(1L, nextBoundary - age));
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            lastLocationUpdateElapsed = SystemClock.elapsedRealtime();
            setGnssStatus(GnssState.BAD);
            scheduleGnssFreshnessDeadline();
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            lastLocationUpdateElapsed = 0L;
            mainHandler.removeCallbacks(updateGnssStatusRunnable);
            setGnssStatus(GnssState.OFF);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            lastLocationUpdateElapsed = SystemClock.elapsedRealtime();
            setGnssStatus(GnssState.BAD);
            scheduleGnssFreshnessDeadline();
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            lastLocationUpdateElapsed = SystemClock.elapsedRealtime();
            if (location.hasAccuracy() && location.getAccuracy() < 20.0) {
                setGnssStatus(GnssState.GOOD);
            } else {
                setGnssStatus(GnssState.BAD);
            }
            scheduleGnssFreshnessDeadline();
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
            lastLocationUpdateElapsed = SystemClock.elapsedRealtime();
            setGnssStatus(GnssState.BAD);
            scheduleGnssFreshnessDeadline();
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
            lastLocationUpdateElapsed = 0L;
            mainHandler.removeCallbacks(updateGnssStatusRunnable);
            setGnssStatus(GnssState.OFF);
        }
    };

    private final BroadcastReceiver wifiRssiReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent != null && WifiManager.RSSI_CHANGED_ACTION.equals(intent.getAction())) {
                refreshWifiSignalLevel();
            }
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is connected");
            if (wifiState == WiFiState.OFF) {
                setWifiStatus(WiFiState.NO_INTERNET);
            }
            refreshWifiSignalLevel();
            mainHandler.post(() -> probeReachability());
        }

        @Override
        public void onLost(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is lost");
            setWifiStatus(WiFiState.OFF);
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, NetworkCapabilities networkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                boolean hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
                Log.d(TAG, "Wi-Fi capabilities changed, has internet = " + hasInternet);
                if (hasInternet) {
                    // Network claims Internet capability — do our own probe to differentiate
                    // FULL vs WHITELIST vs NONE.
                    mainHandler.post(() -> probeReachability());
                } else {
                    setWifiStatus(WiFiState.NO_INTERNET);
                }
                refreshWifiSignalLevel();
            } else {
                setWifiStatus(WiFiState.OFF);
            }
        }
    };

    private final Runnable reachabilityProbeRunnable = new Runnable() {
        @Override
        public void run() {
            if (wifiState != WiFiState.OFF) {
                // Thirty-second safety refresh covers vendor stacks that suppress RSSI broadcasts.
                refreshWifiSignalLevel();
                probeReachability();
            }
            mainHandler.postDelayed(this, INTERNET_PROBE_INTERVAL_MS);
        }
    };

    private ReachabilityChecker reachabilityChecker;

    private void probeReachability() {
        if (destroyed || binding == null || prefs == null || !prefs.widgetEnabled.get()) return;
        if (reachabilityChecker == null) {
            reachabilityChecker = new ReachabilityChecker(mainHandler);
        }
        reachabilityChecker.check(reach -> {
            if (destroyed || binding == null || prefs == null
                    || !prefs.widgetEnabled.get()) return;
            if (wifiState == WiFiState.OFF) return;
            switch (reach) {
                case FULL -> setWifiStatus(WiFiState.INTERNET);
                case WHITELIST -> setWifiStatus(WiFiState.LIMITED_INTERNET);
                case NONE -> setWifiStatus(WiFiState.NO_INTERNET);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;

        // startForegroundService() gives us only a few seconds. Promote immediately, before
        // preferences and connector constructors parse potentially large cached catalogs.
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        StartupPerformanceTrace.mark("widget_foreground_promoted");
        NavigationHudEndpointService.startOptionalHudSpeedBootstrap(this);

    }

    /**
     * Builds only the visual shell after admission. Network, Bluetooth and ECARX objects are
     * created later in independent post-visible stages so the status row never waits for them.
     */
    private void initializeRuntime() {
        if (runtimeInitialized || destroyed) return;
        runtimeInitialized = true;
        startupStateOwnerToken = STARTUP_STATE_OWNER.incrementAndGet();

        // The early visual host reads geometry immediately but runs upgrade migrations inside the
        // background-priority state barrier at the delayed runtime phase.
        prefs = new Preferences(this, false);
        automationStates = new AutomationStateStore(this);
        // Cached values may be rendered in the first frame, but never as current. The persisted
        // mark-all-stale pass is intentionally delayed until after that frame.
        automationStates.beginSessionFreshnessBarrier();
        connectorValues = new ConnectorValueRegistry();
        connectorValues.addListener(crossSourceRuleListener);
        connectorValues.addListener(phoneStatusListener);
        haConfigs = new HaBrickConfigStore(prefs);

        boolean overlayRuntimeAvailable = Permissions.allPermissionsGranted(this);
        boolean headlessHostRequired = WidgetServiceStarter.requiresHeadlessHost(prefs)
                || !pendingIntentScenarioCommands.isEmpty();
        armTemporaryScenarioHeadlessHostIfNeeded(overlayRuntimeAvailable);
        if (!overlayRuntimeAvailable && !headlessHostRequired) {
            // Locked boot and a few OEM AppOps implementations can report a temporary denial.
            // Never turn that transient state into a permanent user preference and never pull
            // the settings activity over HOME without an explicit user action.
            Log.w(TAG, "Overlay permissions are not available yet; keeping widget enabled");
            stopSelf();
            return;
        }

        instance = this;
        refreshServiceWatchdog();
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        windowManager = getSystemService(WindowManager.class);
        ecarxNavigatorWindowObserver = new EcarxNavigatorWindowObserver(
                this, this::onEcarxNavigatorWindowStateChanged);
        ecarxNavigatorWindowObserver.start(android.view.Display.DEFAULT_DISPLAY);

        if (prefs.widgetEnabled.get() && overlayRuntimeAvailable) {
            createOverlayView();
            // Start the background freshness barrier immediately. Controller stages yield through
            // the main queue, so WindowManager can still draw without waiting for JSON migration.
            if (!automaticRuntimeParked && !automaticLifecycleQuiet) {
                runInitialIntegrationStartup();
            }
        } else if (headlessHostRequired) {
            // No visual frame exists in headless mode, so the same serialized controller lane can
            // begin immediately. It still creates and starts at most one integration per stage.
            StatusWidgetApplication.notifyFirstUsefulSurface(this);
            if (!automaticRuntimeParked && !automaticLifecycleQuiet) {
                runInitialIntegrationStartup();
            }
        } else {
            stopSelf();
        }
    }

    private void ensureMqttRuntimeGraph() {
        if (mqttController != null) return;
        mqttController = createMqttController();
    }

    @NonNull
    private MqttController createMqttController() {
        return new MqttController(this, prefs, automationStates, connectorValues,
                new MqttController.StateListener() {
                    @Override public void onStateChanged(String scope, String id) {
                        onAutomationStateChanged(scope, id);
                    }

                    @Override public void onConnectionChanged(boolean connected, String detail) {
                        Log.i(TAG, "MQTT " + (connected ? "connected" : "disconnected")
                                + ": " + detail);
                    }
                });
    }

    private static final class SprutRuntimeGraph {
        final SprutHubController controller;
        final PhoneSprutPresenceExporter phonePresence;
        final PhoneSprutPresenceExporter ancsPresence;

        SprutRuntimeGraph(@NonNull SprutHubController controller,
                          @NonNull PhoneSprutPresenceExporter phonePresence,
                          @NonNull PhoneSprutPresenceExporter ancsPresence) {
            this.controller = controller;
            this.phonePresence = phonePresence;
            this.ancsPresence = ancsPresence;
        }
    }

    private static final class PhonePresenceRuntimeGraph {
        final PhoneSprutPresenceExporter phonePresence;
        final PhoneSprutPresenceExporter ancsPresence;

        PhonePresenceRuntimeGraph(@NonNull PhoneSprutPresenceExporter phonePresence,
                                  @NonNull PhoneSprutPresenceExporter ancsPresence) {
            this.phonePresence = phonePresence;
            this.ancsPresence = ancsPresence;
        }
    }

    private void ensureSprutRuntimeGraph() {
        if (sprutController != null && phonePresenceExporter != null
                && phoneAncsPresenceExporter != null) return;
        if (sprutController != null || phonePresenceExporter != null
                || phoneAncsPresenceExporter != null) {
            // Heal a constructor failure from an earlier attempt as one bundle; no callback may
            // observe a controller without both exact-device presence projections.
            if (phonePresenceExporter != null) phonePresenceExporter.stop();
            if (phoneAncsPresenceExporter != null) phoneAncsPresenceExporter.stop();
            if (sprutController != null) sprutController.stop();
            sprutController = null;
            phonePresenceExporter = null;
            phoneAncsPresenceExporter = null;
        }
        publishSprutRuntimeGraph(createSprutRuntimeGraph());
    }

    @NonNull
    private SprutRuntimeGraph createSprutRuntimeGraph() {
        SprutHubController nextController = new SprutHubController(
                this, prefs, automationStates, connectorValues,
                new SprutHubController.Listener() {
                    @Override public void onStateChanged(@NonNull String scope,
                                                         @NonNull String id) {
                        onAutomationStateChanged(scope, id);
                    }

                    @Override public void onConnectionChanged(
                            @NonNull SprutHubController.State state, @NonNull String detail) {
                        Log.i(TAG, "Sprut.hub " + state + ": " + detail);
                        if (carTelemetryExporter != null) {
                            carTelemetryExporter.onSprutConnectionChanged(state);
                        }
                        if (phonePresenceExporter != null) {
                            phonePresenceExporter.onSprutConnectionChanged(state);
                        }
                        if (phoneAncsPresenceExporter != null) {
                            phoneAncsPresenceExporter.onSprutConnectionChanged(state);
                        }
                    }

                    @Override public void onCatalogChanged(@NonNull SprutCatalog catalog) {
                        Log.i(TAG, "Sprut.hub catalog: " + catalog.accessories().size()
                                + " devices, " + catalog.characteristics().size()
                                + " characteristics");
                        if (carTelemetryExporter != null) {
                            carTelemetryExporter.onSprutCatalogChanged();
                        }
                        if (phonePresenceExporter != null) {
                            phonePresenceExporter.onSprutCatalogChanged();
                        }
                        if (phoneAncsPresenceExporter != null) {
                            phoneAncsPresenceExporter.onSprutCatalogChanged();
                        }
                    }

                    @Override public void onCharacteristicChanged(
                            @NonNull dezz.status.widget.sprut.SprutPath path) {
                        if (carTelemetryExporter != null) {
                            carTelemetryExporter.onSprutCharacteristicChanged(path);
                        }
                        if (phonePresenceExporter != null) {
                            phonePresenceExporter.onSprutCharacteristicChanged(path);
                        }
                        if (phoneAncsPresenceExporter != null) {
                            phoneAncsPresenceExporter.onSprutCharacteristicChanged(path);
                        }
                    }
                });
        PhoneSprutPresenceExporter nextPresence = null;
        PhoneSprutPresenceExporter nextAncsPresence = null;
        try {
            nextPresence = new PhoneSprutPresenceExporter(
                    prefs, nextController, mainHandler);
            nextAncsPresence = new PhoneSprutPresenceExporter(
                    prefs, nextController, mainHandler,
                    PhoneSprutPresenceExporter.Signal.ANCS);
            return new SprutRuntimeGraph(nextController, nextPresence, nextAncsPresence);
        } catch (RuntimeException failure) {
            try { if (nextAncsPresence != null) nextAncsPresence.stop(); }
            catch (RuntimeException ignored) { }
            try { if (nextPresence != null) nextPresence.stop(); }
            catch (RuntimeException ignored) { }
            try { nextController.stop(); } catch (RuntimeException ignored) { }
            throw failure;
        }
    }

    /** Main-thread publication keeps callbacks from observing a partial three-object graph. */
    private void publishSprutRuntimeGraph(@NonNull SprutRuntimeGraph graph) {
        sprutController = graph.controller;
        phonePresenceExporter = graph.phonePresence;
        phoneAncsPresenceExporter = graph.ancsPresence;
    }

    private void discardSprutRuntimeGraph(@NonNull SprutRuntimeGraph graph) {
        try { if (graph.ancsPresence != null) graph.ancsPresence.stop(); }
        catch (RuntimeException ignored) { }
        try { if (graph.phonePresence != null) graph.phonePresence.stop(); }
        catch (RuntimeException ignored) { }
        try { if (graph.controller != null) graph.controller.stop(); }
        catch (RuntimeException ignored) { }
    }

    @NonNull
    private PhonePresenceRuntimeGraph createPhonePresenceRuntimeGraph(
            @NonNull SprutHubController controller) {
        PhoneSprutPresenceExporter phone = null;
        try {
            phone = new PhoneSprutPresenceExporter(prefs, controller, mainHandler);
            PhoneSprutPresenceExporter ancs = new PhoneSprutPresenceExporter(
                    prefs, controller, mainHandler, PhoneSprutPresenceExporter.Signal.ANCS);
            return new PhonePresenceRuntimeGraph(phone, ancs);
        } catch (RuntimeException failure) {
            if (phone != null) {
                try { phone.stop(); } catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }

    private void discardPhonePresenceRuntimeGraph(
            @NonNull PhonePresenceRuntimeGraph graph) {
        try { graph.ancsPresence.stop(); } catch (RuntimeException ignored) { }
        try { graph.phonePresence.stop(); } catch (RuntimeException ignored) { }
    }

    private void ensurePhoneRuntimeGraph() {
        if (phoneController != null) return;
        phoneController = createPhoneController();
    }

    @NonNull
    private PhoneConnectorController createPhoneController() {
        return new PhoneConnectorController(this, prefs, connectorValues,
                new PhoneConnectorController.PresenceSink() {
                    @Override public void onPhoneConnectionChanged(boolean connected) {
                        PhoneSprutPresenceExporter exporter = phonePresenceExporter;
                        if (exporter != null) exporter.onPhoneConnectionChanged(connected);
                    }

                    @Override public void onAncsConnectionChanged(boolean connected) {
                        PhoneSprutPresenceExporter exporter = phoneAncsPresenceExporter;
                        if (exporter != null) exporter.onPhoneConnectionChanged(connected);
                    }
                });
    }

    private static final class CarRuntimeGraph {
        final CarIntegration car;
        final CarTelemetryExporter exporter;

        CarRuntimeGraph(@NonNull CarIntegration car, @NonNull CarTelemetryExporter exporter) {
            this.car = car;
            this.exporter = exporter;
        }
    }

    private void ensureCarRuntimeGraph() {
        if (carTelemetryExporter != null) return;
        ensureSprutRuntimeGraph();
        publishCarRuntimeGraph(createCarRuntimeGraph(sprutController));
    }

    @NonNull
    private CarRuntimeGraph createCarRuntimeGraph(@NonNull SprutHubController sprut) {
        CarIntegration car = CarIntegrations.get(this);
        return new CarRuntimeGraph(car,
                new CarTelemetryExporter(prefs, car, sprut, mainHandler));
    }

    /** Vendor availability callbacks and service field publication remain main-thread-owned. */
    private void publishCarRuntimeGraph(@NonNull CarRuntimeGraph graph) {
        boolean replacingPublishedGraph = carTelemetryExporter != null;
        try {
            // Re-evaluate placeholders when the asynchronous vendor capability answer arrives.
            graph.car.setAvailabilityChangedListener(() -> mainHandler.post(() -> {
                if (!destroyed && binding != null) refreshCarStatusSurface();
            }));
            reconcileCarExternalOverlayListener(graph.car);
            carTelemetryExporter = graph.exporter;
        } catch (RuntimeException failure) {
            try { graph.exporter.stop(); } catch (RuntimeException ignored) { }
            // With a current graph, the old callback is still valid. The vendor setter may throw
            // either before or after accepting the identical service-level callback, so clearing
            // it here would break the live graph that remains authoritative.
            if (!replacingPublishedGraph) {
                try { graph.car.setAvailabilityChangedListener(null); }
                catch (RuntimeException ignored) { }
            }
            throw failure;
        }
    }

    private void discardCarRuntimeGraph(@NonNull CarRuntimeGraph graph) {
        try { graph.exporter.stop(); } catch (RuntimeException ignored) { }
    }

    private void ensureHomeAssistantRuntimeGraph() {
        if (haApiController != null) return;
        haApiController = createHomeAssistantController();
    }

    @NonNull
    private HaApiController createHomeAssistantController() {
        return new HaApiController(this, prefs, automationStates, connectorValues,
                new HaApiController.Listener() {
                    @Override public void onStateChanged(@NonNull String scope,
                                                         @NonNull String id) {
                        onAutomationStateChanged(scope, id);
                    }

                    @Override public void onConnectionChanged(
                            @NonNull HaWebSocketConnector.ConnectionState state,
                            @NonNull String detail) {
                        Log.i(TAG, "Home Assistant " + state + ": " + detail);
                    }

                    @Override public void onCatalogChanged(@NonNull HaEntityCatalog catalog) {
                        Log.i(TAG, "Home Assistant catalog: " + catalog.size() + " entities");
                    }
                });
    }

    private static final class ScenarioRuntimeGraph {
        final ConnectorActionDispatcher dispatcher;
        final LocalScenarioController controller;

        ScenarioRuntimeGraph(@NonNull ConnectorActionDispatcher dispatcher,
                             @NonNull LocalScenarioController controller) {
            this.dispatcher = dispatcher;
            this.controller = controller;
        }
    }

    private void ensureScenarioRuntimeGraph() {
        if (scenarioController != null) return;
        ensureMqttRuntimeGraph();
        ensureSprutRuntimeGraph();
        ensureHomeAssistantRuntimeGraph();
        publishScenarioRuntimeGraph(createScenarioRuntimeGraph(
                mqttController, sprutController, haApiController));
        ensurePopupOverlayManager();
    }

    @NonNull
    private ScenarioRuntimeGraph createScenarioRuntimeGraph(
            @NonNull MqttController mqtt, @NonNull SprutHubController sprut,
            @NonNull HaApiController homeAssistant) {
        ConnectorActionDispatcher dispatcher = new ConnectorActionDispatcher(
                mqtt, sprut, homeAssistant);
        LocalScenarioController controller = new LocalScenarioController(
                this, prefs, automationStates, connectorValues,
                CarIntegrations.get(this), this::onScenarioTargetsChanged);
        return new ScenarioRuntimeGraph(dispatcher, controller);
    }

    private void publishScenarioRuntimeGraph(@NonNull ScenarioRuntimeGraph graph) {
        actionDispatcher = graph.dispatcher;
        scenarioController = graph.controller;
    }

    private void ensureIntentScenarioRuntimeGraph() {
        if (intentScenarioController != null) return;
        ensureScenarioRuntimeGraph();
        intentScenarioController = createIntentScenarioController(actionDispatcher);
    }

    @NonNull
    private IntentScenarioController createIntentScenarioController(
            @NonNull ConnectorActionDispatcher dispatcher) {
        return new IntentScenarioController(this, prefs, dispatcher);
    }

    private void onScenarioTargetsChanged(@NonNull Set<String> targets) {
        // Initial startup performs one consolidated render after all providers and scenarios are
        // configured. Credential-only refresh follows the same coalescing rule.
        if (automaticSurfaceRefreshSuppressed()) {
            synchronized (automationUiLock) {
                for (String target : targets) {
                    int divider = target.indexOf('|');
                    if (divider <= 0 || divider >= target.length() - 1) continue;
                    pendingAutomationUi.computeIfAbsent(target.substring(0, divider),
                            ignored -> new HashSet<>()).add(target.substring(divider + 1));
                }
            }
            return;
        }
        mainHandler.post(() -> {
            if (destroyed) return;
            dispatchAutomationPresentationTargets(targets);
            if (binding != null) renderHomeAssistantBricks();
            applyPopupPreferencesSafely();
            boolean phoneFieldsChanged = false;
            boolean driverTargetsChanged = false;
            for (String target : targets) {
                if (target.startsWith(AutomationContract.SCOPE_POPUP + "|")
                        && PhoneNotificationAutomation.isFieldAutomationId(
                        target.substring(target.indexOf('|') + 1))) {
                    phoneFieldsChanged = true;
                }
                if (target.startsWith(AutomationContract.SCOPE_DRIVER + "|")) {
                    driverTargetsChanged = true;
                }
                if (target.startsWith(AutomationContract.SCOPE_HUD + "|")
                        && (prefs.hudPanelAutostart.get()
                        || HudPresentationService.isRunning(this))) {
                    HudPresentationService.notifyAutomationChanged(this);
                }
            }
            if (driverTargetsChanged) DriverPanelService.apply(this);
            if (binding != null) {
                if (phoneFieldsChanged && activePhoneNotification != null) updateMediaInfo();
                applyBrickVisibility(currentBrickSet());
            }
        });
    }

    private static final class PreparedInitialIntegrationStage {
        final int stage;
        @NonNull final String name;
        final boolean succeeded;
        @NonNull final Runnable publication;
        @NonNull final Runnable cleanup;
        private boolean published;

        PreparedInitialIntegrationStage(int stage, @NonNull String name, boolean succeeded,
                                        @NonNull Runnable publication,
                                        @NonNull Runnable cleanup) {
            this.stage = stage;
            this.name = name;
            this.succeeded = succeeded;
            this.publication = publication;
            this.cleanup = cleanup;
        }

        void publish() {
            publication.run();
            published = true;
        }

        void discard() {
            if (!published) cleanup.run();
        }
    }

    /** Starts every enabled integration immediately; persistence and controllers run off-main. */
    private void runInitialIntegrationStartup() {
        if (destroyed || automaticRuntimeParked || automaticLifecycleQuiet
                || integrationsStarted || initialIntegrationStartupInProgress) return;
        integrationStartupScheduled = true;
        initialIntegrationStartupInProgress = true;
        initialIntegrationStage = 0;
        initialIntegrationStageRetryCount = 0;
        mainHandler.removeCallbacks(initialIntegrationStageRunner);
        mainHandler.post(initialIntegrationStageRunner);
    }

    private void runNextInitialIntegrationStage() {
        if (destroyed || !initialIntegrationStartupInProgress
                || initialIntegrationWorkerInFlight) return;
        if (automaticRuntimeParked || automaticLifecycleQuiet) {
            // Keep the exact stage parked. The host-phase generation will resume it; no polling
            // and no transport/vendor construction is allowed inside the QuickBoot quiet lane.
            mainHandler.removeCallbacks(initialIntegrationStageRunner);
            return;
        }
        StartupPerformanceTrace.mark("integration_stage_" + initialIntegrationStage);
        switch (initialIntegrationStage) {
            case 0:
                // Persist the session barrier before any connector is allowed to publish fresh.
                runCachedStateFreshnessBarrier();
                return;
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
            case 7:
            case 8:
            case 9:
                submitInitialIntegrationWorkerStage(initialIntegrationStage);
                return;
            default:
                finishInitialIntegrationStartup();
                return;
        }
    }

    /**
     * Submits the current stage now to the one background-priority startup lane. The worker may
     * parse preferences, touch journals and start transport-owned threads, but it never publishes
     * a service field or invokes view/system-listener code. Publication is one fenced main task.
     */
    private void submitInitialIntegrationWorkerStage(int stage) {
        initialIntegrationWorkerInFlight = true;
        final long ownerToken = startupStateOwnerToken;
        try {
            startupStateWorker.execute(() -> {
                PreparedInitialIntegrationStage workerResult;
                try {
                    workerResult = prepareInitialIntegrationWorkerStage(stage);
                } catch (RuntimeException failure) {
                    // An unexpected parser/controller exception must still reach the main
                    // completion path so initialIntegrationWorkerInFlight is always released.
                    workerResult = failedInitialIntegrationStage(
                            stage, "integration stage " + stage, failure);
                }
                final PreparedInitialIntegrationStage prepared = workerResult;
                if (!ownsStartupState(ownerToken)) {
                    discardPreparedInitialIntegrationStage(prepared);
                    return;
                }
                if (!pendingInitialIntegrationStage.compareAndSet(null, prepared)) {
                    discardPreparedInitialIntegrationStage(prepared);
                    mainHandler.post(() -> completeInitialIntegrationWorkerStage(
                            ownerToken, stage, null));
                    return;
                }
                if (!ownsStartupState(ownerToken)) {
                    if (pendingInitialIntegrationStage.compareAndSet(prepared, null)) {
                        discardPreparedInitialIntegrationStage(prepared);
                    }
                    return;
                }
                mainHandler.post(() -> completeInitialIntegrationWorkerStage(
                        ownerToken, stage, prepared));
            });
        } catch (RuntimeException rejected) {
            initialIntegrationWorkerInFlight = false;
            Log.e(TAG, "Could not schedule integration stage " + stage, rejected);
            advanceInitialIntegrationStage(false);
        }
    }

    /** Main-thread owner/stage fence and the only publication point for prepared controllers. */
    private void completeInitialIntegrationWorkerStage(long ownerToken, int stage,
                                                       @Nullable
                                                       PreparedInitialIntegrationStage prepared) {
        if (prepared != null
                && !pendingInitialIntegrationStage.compareAndSet(prepared, null)) return;
        initialIntegrationWorkerInFlight = false;
        if (prepared == null) {
            if (!destroyed && initialIntegrationStartupInProgress
                    && initialIntegrationStage == stage) {
                advanceInitialIntegrationStage(false);
            }
            return;
        }
        if (!ownsStartupState(ownerToken) || startupStateOwnerToken != ownerToken
                || prepared.stage != stage || !initialIntegrationStartupInProgress
                || initialIntegrationStage != stage
                || automaticRuntimeParked || automaticLifecycleQuiet) {
            discardPreparedInitialIntegrationStage(prepared);
            // QuickBoot may resume and rewind to stage one while this older stage is still on the
            // worker. Its already-posted runner observes inFlight and returns; hand ownership back
            // here once the stale result is discarded or the rewound lane would remain stranded.
            if (ownsStartupState(ownerToken) && startupStateOwnerToken == ownerToken
                    && initialIntegrationStartupInProgress
                    && !automaticRuntimeParked && !automaticLifecycleQuiet) {
                mainHandler.post(initialIntegrationStageRunner);
            }
            return;
        }
        boolean stageSucceeded = prepared.succeeded
                && runIntegrationStep(prepared.name + " publication", prepared::publish);
        if (!stageSucceeded) discardPreparedInitialIntegrationStage(prepared);
        advanceInitialIntegrationStage(stageSucceeded);
    }

    private void advanceInitialIntegrationStage(boolean stageSucceeded) {
        if (!stageSucceeded
                && initialIntegrationStageRetryCount
                < MAX_INITIAL_INTEGRATION_STAGE_RETRIES) {
            initialIntegrationStageRetryCount++;
            mainHandler.postDelayed(initialIntegrationStageRunner,
                    INITIAL_INTEGRATION_RETRY_MS);
            return;
        }
        initialIntegrationStageRetryCount = 0;
        initialIntegrationStage++;
        mainHandler.post(initialIntegrationStageRunner);
    }

    private void discardPreparedInitialIntegrationStage(
            @NonNull PreparedInitialIntegrationStage prepared) {
        runCleanupStep(prepared.name + " unpublished startup", prepared::discard);
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareInitialIntegrationWorkerStage(int stage) {
        switch (stage) {
            case 1:
                return preparePhoneStage(stage);
            case 2:
                return preparePhonePresenceStage(stage);
            case 3:
                return prepareStatusSurfaceStage(stage);
            case 4:
                return prepareCarTelemetryStage(stage);
            case 5:
                return prepareMqttStage(stage);
            case 6:
                return prepareHomeAssistantStage(stage);
            case 7:
                return prepareSprutStage(stage);
            case 8:
                return prepareVisualScenarioStage(stage);
            case 9:
                return prepareIntentScenarioStage(stage);
            default:
                return failedInitialIntegrationStage(stage, "unknown integration stage");
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage preparePhonePresenceStage(int stage) {
        SprutHubController currentController = sprutController;
        PhoneSprutPresenceExporter currentPhone = phonePresenceExporter;
        PhoneSprutPresenceExporter currentAncs = phoneAncsPresenceExporter;
        if (currentController != null && currentPhone != null && currentAncs != null) {
            PhonePresenceRuntimeGraph next = createPhonePresenceRuntimeGraph(currentController);
            return successfulInitialIntegrationStage(stage, "phone presence", () -> {
                if (sprutController != currentController
                        || phonePresenceExporter != currentPhone
                        || phoneAncsPresenceExporter != currentAncs) {
                    throw new IllegalStateException(
                            "Sprut presence graph changed before replacement");
                }
                // Both fallible reloads finish before either authoritative field changes.
                next.phonePresence.reconfigure();
                next.ancsPresence.reconfigure();
                phonePresenceExporter = next.phonePresence;
                phoneAncsPresenceExporter = next.ancsPresence;
                runCleanupStep("replaced phone presence", currentPhone::stop);
                runCleanupStep("replaced phone ANCS presence", currentAncs::stop);
            }, () -> discardPhonePresenceRuntimeGraph(next));
        }
        if (currentController != null || currentPhone != null || currentAncs != null) {
            return failedInitialIntegrationStage(stage, "phone presence partial graph");
        }

        SprutRuntimeGraph next = null;
        try {
            next = createSprutRuntimeGraph();
            SprutRuntimeGraph prepared = next;
            return successfulInitialIntegrationStage(stage, "phone presence", () -> {
                if (sprutController != null || phonePresenceExporter != null
                        || phoneAncsPresenceExporter != null) {
                    throw new IllegalStateException("Sprut graph changed before publication");
                }
                // Do not leave service fields pointing at a stopped bundle if either reload fails.
                prepared.phonePresence.reconfigure();
                prepared.ancsPresence.reconfigure();
                publishSprutRuntimeGraph(prepared);
            }, () -> discardSprutRuntimeGraph(prepared));
        } catch (RuntimeException failure) {
            if (next != null) discardSprutRuntimeGraph(next);
            return failedInitialIntegrationStage(stage, "phone presence", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage preparePhoneStage(int stage) {
        PhoneConnectorController current = phoneController;
        if (current != null) {
            return successfulInitialIntegrationStage(stage, "phone", () -> {
                if (phoneController != current) {
                    throw new IllegalStateException("Phone graph changed before reconfigure");
                }
                current.reconfigure();
            }, () -> { });
        }
        PhoneConnectorController next = null;
        try {
            next = createPhoneController();
            next.reconfigure();
            PhoneConnectorController prepared = next;
            return successfulInitialIntegrationStage(stage, "phone", () -> {
                if (phoneController != null) {
                    throw new IllegalStateException("Phone graph changed before publication");
                }
                phoneController = prepared;
            }, prepared::stop);
        } catch (RuntimeException failure) {
            if (next != null) {
                try { next.stop(); } catch (RuntimeException ignored) { }
            }
            return failedInitialIntegrationStage(stage, "phone", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareStatusSurfaceStage(int stage) {
        boolean phonePopupReady = phoneNotificationPopupConfigured;
        if (!phonePopupReady && (prefs.phonePopupNotificationsEnabled.get()
                || prefs.phoneLowBatteryAlertEnabled.get())) {
            try {
                PhoneNotificationAutomation.ensureConfigured(prefs);
                phonePopupReady = true;
            } catch (JSONException | RuntimeException failure) {
                Log.e(TAG, "Could not configure phone notification popup", failure);
            }
        }
        Set<BrickType> popupTypes = immutableBrickTypes(loadPopupBuiltinTypes());
        String popupOverlaysJson = prefs.popupOverlaysJson.get();
        String popupItemsJson = prefs.popupItemsJson.get();
        Set<BrickType> driverTypes = immutableBrickTypes(loadDriverInformationBrickTypes());
        String driverInformationJson = prefs.activeDriverPanelProfile().shortcutsJson.get();
        boolean driverPanelEnabled = prefs.driverPanelEnabled.get();
        boolean preparedPhonePopup = phonePopupReady;
        return successfulInitialIntegrationStage(stage, "status surface runtime", () -> {
            phoneNotificationPopupConfigured = preparedPhonePopup;
            configuredPopupBuiltinTypes = popupTypes;
            configuredPopupOverlaysJson = popupOverlaysJson;
            configuredPopupItemsJson = popupItemsJson;
            configuredDriverInformationTypes = driverTypes;
            configuredDriverInformationJson = driverInformationJson;
            configuredDriverPanelEnabled = driverPanelEnabled;
            if (binding != null) {
                runIntegrationStep("status surface runtime main", () -> applyPreferences(false));
            }
        }, () -> { });
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareCarTelemetryStage(int stage) {
        CarTelemetryExporter current = carTelemetryExporter;
        SprutHubController sprut = sprutController;
        if (sprut == null) {
            return failedInitialIntegrationStage(stage, "car telemetry missing Sprut graph");
        }
        CarRuntimeGraph next = null;
        try {
            next = createCarRuntimeGraph(sprut);
            CarRuntimeGraph prepared = next;
            return successfulInitialIntegrationStage(stage, "car telemetry", () -> {
                if (carTelemetryExporter != current) {
                    throw new IllegalStateException("Car graph changed before publication");
                }
                // Parse and subscribe on the main thread, but do not replace the authoritative
                // field/listener until the complete reconfigure succeeds. A retry therefore sees
                // either the previous live exporter or a fully configured replacement.
                prepared.exporter.reconfigure();
                if (carTelemetryExporter != current) {
                    throw new IllegalStateException("Car graph changed during reconfigure");
                }
                publishCarRuntimeGraph(prepared);
                if (current != null) {
                    runCleanupStep("replaced car telemetry", current::stop);
                }
                runIntegrationStep("car telemetry surface", this::refreshCarStatusSurface);
            }, () -> discardCarRuntimeGraph(prepared));
        } catch (RuntimeException failure) {
            if (next != null) discardCarRuntimeGraph(next);
            return failedInitialIntegrationStage(stage, "car telemetry", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareMqttStage(int stage) {
        MqttController current = mqttController;
        if (current != null) {
            return successfulInitialIntegrationStage(stage, "MQTT", () -> {
                if (mqttController != current) {
                    throw new IllegalStateException("MQTT graph changed before reconfigure");
                }
                current.reconfigure();
            }, () -> { });
        }
        MqttController next = null;
        try {
            next = createMqttController();
            next.reconfigure();
            MqttController prepared = next;
            return successfulInitialIntegrationStage(stage, "MQTT", () -> {
                if (mqttController != null) {
                    throw new IllegalStateException("MQTT graph changed before publication");
                }
                mqttController = prepared;
            }, prepared::stop);
        } catch (RuntimeException failure) {
            if (next != null) {
                try { next.stop(); } catch (RuntimeException ignored) { }
            }
            return failedInitialIntegrationStage(stage, "MQTT", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareHomeAssistantStage(int stage) {
        HaApiController current = haApiController;
        if (current != null) {
            return successfulInitialIntegrationStage(stage, "Home Assistant", () -> {
                if (haApiController != current) {
                    throw new IllegalStateException("HA graph changed before reconfigure");
                }
                current.reconfigure();
            }, () -> { });
        }
        HaApiController next = null;
        try {
            next = createHomeAssistantController();
            next.reconfigure();
            HaApiController prepared = next;
            return successfulInitialIntegrationStage(stage, "Home Assistant", () -> {
                if (haApiController != null) {
                    throw new IllegalStateException("HA graph changed before publication");
                }
                haApiController = prepared;
            }, prepared::stop);
        } catch (RuntimeException failure) {
            if (next != null) {
                try { next.stop(); } catch (RuntimeException ignored) { }
            }
            return failedInitialIntegrationStage(stage, "Home Assistant", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareSprutStage(int stage) {
        SprutHubController current = sprutController;
        if (current == null || phonePresenceExporter == null
                || phoneAncsPresenceExporter == null) {
            return failedInitialIntegrationStage(stage, "Sprut.hub missing runtime graph");
        }
        return successfulInitialIntegrationStage(stage, "Sprut.hub", () -> {
            if (sprutController != current) {
                throw new IllegalStateException("Sprut graph changed before reconfigure");
            }
            current.reconfigure();
        }, () -> { });
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareVisualScenarioStage(int stage) {
        LocalScenarioController current = scenarioController;
        if (current != null && actionDispatcher != null) {
            ConnectorActionDispatcher currentDispatcher = actionDispatcher;
            return successfulInitialIntegrationStage(stage, "visual scenarios", () -> {
                if (scenarioController != current || actionDispatcher != currentDispatcher) {
                    throw new IllegalStateException(
                            "Scenario graph changed before reconfigure");
                }
                current.reconfigure();
            }, () -> { });
        }
        if (current != null || actionDispatcher != null || mqttController == null
                || sprutController == null || haApiController == null) {
            return failedInitialIntegrationStage(stage, "visual scenario partial graph");
        }
        ScenarioRuntimeGraph next = null;
        try {
            next = createScenarioRuntimeGraph(
                    mqttController, sprutController, haApiController);
            ScenarioRuntimeGraph prepared = next;
            return successfulInitialIntegrationStage(stage, "visual scenarios", () -> {
                if (scenarioController != null || actionDispatcher != null) {
                    throw new IllegalStateException("Scenario graph changed before publication");
                }
                prepared.controller.reconfigure();
                publishScenarioRuntimeGraph(prepared);
            }, prepared.controller::destroy);
        } catch (RuntimeException failure) {
            if (next != null) {
                try { next.controller.destroy(); } catch (RuntimeException ignored) { }
            }
            return failedInitialIntegrationStage(stage, "visual scenarios", failure);
        }
    }

    @NonNull
    private PreparedInitialIntegrationStage prepareIntentScenarioStage(int stage) {
        IntentScenarioController current = intentScenarioController;
        if (current != null) {
            return successfulInitialIntegrationStage(stage, "intent scenarios",
                    () -> publishIntentScenarioStage(current, false), () -> { });
        }
        ConnectorActionDispatcher dispatcher = actionDispatcher;
        if (dispatcher == null) {
            return failedInitialIntegrationStage(stage, "intent scenario missing dispatcher");
        }
        IntentScenarioController next = null;
        try {
            next = createIntentScenarioController(dispatcher);
            IntentScenarioController prepared = next;
            return successfulInitialIntegrationStage(stage, "intent scenarios",
                    () -> publishIntentScenarioStage(prepared, true), prepared::destroy);
        } catch (RuntimeException failure) {
            if (next != null) {
                try { next.destroy(); } catch (RuntimeException ignored) { }
            }
            return failedInitialIntegrationStage(stage, "intent scenarios", failure);
        }
    }

    /**
     * Main-publication-only boundary for IntentScenarioController maps, Handler retries and the
     * dynamic receiver. A stale worker result is destroyed without ever entering this method.
     */
    private void publishIntentScenarioStage(@NonNull IntentScenarioController controller,
                                            boolean publishNewController) {
        if (publishNewController) {
            if (intentScenarioController != null) {
                throw new IllegalStateException("Intent graph changed before publication");
            }
            // Publish before receiver registration so even an immediate main-loop broadcast can
            // resolve the controller through the service's authoritative field.
            intentScenarioController = controller;
        } else if (intentScenarioController != controller) {
            throw new IllegalStateException("Intent graph changed before reconfigure");
        }
        try {
            controller.reconfigure();
        } catch (RuntimeException failure) {
            if (publishNewController && intentScenarioController == controller) {
                intentScenarioController = null;
                controller.destroy();
            }
            throw failure;
        }
        runIntegrationStep("pending intent scenarios",
                () -> drainPendingIntentScenarioCommands(false));
    }

    @NonNull
    private PreparedInitialIntegrationStage successfulInitialIntegrationStage(
            int stage, @NonNull String name, @NonNull Runnable publication,
            @NonNull Runnable cleanup) {
        return new PreparedInitialIntegrationStage(
                stage, name, true, publication, cleanup);
    }

    @NonNull
    private PreparedInitialIntegrationStage failedInitialIntegrationStage(
            int stage, @NonNull String name) {
        Log.e(TAG, "Could not configure " + name);
        return new PreparedInitialIntegrationStage(
                stage, name, false, () -> { }, () -> { });
    }

    @NonNull
    private PreparedInitialIntegrationStage failedInitialIntegrationStage(
            int stage, @NonNull String name, @NonNull RuntimeException failure) {
        Log.e(TAG, "Could not configure " + name, failure);
        return new PreparedInitialIntegrationStage(
                stage, name, false, () -> { }, () -> { });
    }

    private void runCachedStateFreshnessBarrier() {
        // Reserve the next state before dispatch so settings callbacks cannot start a parallel
        // lane while the worker owns the retained JSON document.
        startupStateBarrierInFlight = true;
        initialIntegrationStage = 1;
        final long ownerToken = startupStateOwnerToken;
        try {
            startupStateWorker.execute(() -> {
                List<HaBrickConfig> loadedMainBricks = Collections.emptyList();
                String loadedMainJson = "[]";
                try {
                    // Both documents can be large after long use. Parse/rewrite them at Android's
                    // background priority while the already-attached shell remains responsive.
                    prefs.completeDeferredStartupMigrations();
                    loadedMainJson = prefs.haMainBricksJson.get();
                    loadedMainBricks = haConfigs.loadMain(loadedMainJson);
                    if (!automationStates.markAllStaleIf(
                            () -> ownsStartupState(ownerToken))) return;
                    if (!ownsStartupState(ownerToken)) return;
                    clearRetainedPhonePopupStateForStartup(ownerToken);
                } catch (RuntimeException failure) {
                    // Leave the session projection fail-closed/stale if persistence is malformed.
                    Log.e(TAG, "Could not persist cached-state freshness barrier", failure);
                }
                List<HaBrickConfig> immutableMainBricks = Collections.unmodifiableList(
                        new ArrayList<>(loadedMainBricks));
                String immutableMainJson = loadedMainJson;
                mainHandler.post(() -> {
                    startupStateBarrierInFlight = false;
                    if (destroyed || !initialIntegrationStartupInProgress) return;
                    configuredMainBricks = immutableMainBricks;
                    configuredMainBricksJson = immutableMainJson;
                    StartupPerformanceTrace.mark("cached_state_ready");
                    if (automaticRuntimeParked || automaticLifecycleQuiet) return;
                    mainHandler.post(initialIntegrationStageRunner);
                });
            });
        } catch (RuntimeException rejected) {
            startupStateBarrierInFlight = false;
            Log.e(TAG, "Could not schedule cached-state freshness barrier", rejected);
            mainHandler.post(initialIntegrationStageRunner);
        }
    }

    private boolean ownsStartupState(long token) {
        return token != 0L && STARTUP_STATE_OWNER.get() == token && !destroyed
                && !Thread.currentThread().isInterrupted();
    }

    /** Worker-only startup cleanup; popup windows/controllers do not exist at this point. */
    private void clearRetainedPhonePopupStateForStartup(long ownerToken) {
        if (automationStates == null) return;
        try {
            long now = System.currentTimeMillis();
            for (String overlayId : new String[]{
                    PhoneNotificationAutomation.OVERLAY_ID,
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID}) {
                if (!automationStates.applyIf(() -> ownsStartupState(ownerToken),
                        AutomationContract.SCOPE_OVERLAY, overlayId,
                        new JSONObject().put("visible", false).put("fresh", false)
                                .put("updated_at", now))) return;
            }
            for (String automationId : PhoneNotificationAutomation.fieldAutomationIds()) {
                if (!automationStates.applyIf(() -> ownsStartupState(ownerToken),
                        AutomationContract.SCOPE_POPUP, automationId,
                        new JSONObject().put("text", "").put("visible", false)
                                .put("fresh", false).put("updated_at", now))) return;
            }
        } catch (JSONException | RuntimeException failure) {
            Log.w(TAG, "Could not clear retained phone popup state", failure);
        }
    }

    private void finishInitialIntegrationStartup() {
        initialIntegrationStartupInProgress = false;
        integrationStartupScheduled = false;
        integrationsStarted = true;
        StartupPerformanceTrace.mark("integrations_ready");
        // Diagnostics/privileged ECARX policy is nonvisual. Keep it out of every connector stage
        // instead of letting an independent 1.5-second timer collide with Phone/Car/MQTT startup.
        StatusWidgetApplication.resumeSurfaceOwnedInitialization(this);
        // A constructor failure in stage 8 must not strand a still-valid explicit command. The
        // on-demand retry remains serialized on the service main looper and executes at most once.
        drainPendingIntentScenarioCommands();
        if (binding != null) {
            runIntegrationStep("initial status-row projection", () -> {
                renderHomeAssistantBricks();
                applyBrickVisibility(currentBrickSet());
            });
        }
        applyPopupPreferencesSafely();
        // Scenario callbacks are deliberately coalesced while integrations start. Rebuild the
        // driver rail once after that consolidated evaluation so boot-time visibility/action
        // overrides are already reflected in its very first stable configuration.
        automaticSurfaceReconcilePending = false;
        if (prefs.driverPanelEnabled.get()) DriverPanelService.apply(this);
        if (prefs.dimMenuPanelEnabled.get() && prefs.dimMenuPanelAutostart.get()) {
            DimMenuPanelService.reconcileAutomatic(this);
        }
        if (prefs.systemShadeEnabled.get() && prefs.systemShadeAutostart.get()) {
            SystemShadeService.reconcile(this, true);
        }
        if (prefs.hudPanelEnabled.get() && prefs.hudPanelAutostart.get()) {
            mainHandler.post(() -> {
                if (!destroyed && prefs != null && prefs.hudPanelEnabled.get()) {
                    HudPresentationService.notifyAutomationChanged(this);
                }
            });
        }
        synchronized (automationUiLock) {
            // Driver/HUD are reconciled exactly once above from the final startup state.
            pendingAutomationUi.remove(AutomationContract.SCOPE_DRIVER);
            pendingAutomationUi.remove(AutomationContract.SCOPE_HUD);
        }
        schedulePendingAutomationUiRefresh();
        mainHandler.removeCallbacks(automationFreshnessTick);
        mainHandler.postDelayed(automationFreshnessTick, 30_000L);
        mainHandler.removeCallbacks(systemConditionRefresh);
        long now = System.currentTimeMillis();
        mainHandler.postDelayed(systemConditionRefresh,
                SYSTEM_CONDITION_REFRESH_INTERVAL_MS
                        - (now % SYSTEM_CONDITION_REFRESH_INTERVAL_MS));
        if (credentialRefreshPending) {
            credentialRefreshPending = false;
            mainHandler.post(this::reconfigureCredentialBackedIntegrationsAfterUnlock);
        } else {
            schedulePendingIntegrationReconfigure();
        }
    }

    private void scheduleInitialIntegrationStartupAfterFrame() {
        if (destroyed || binding == null || !overlayAttached) return;
        if (integrationsStarted && !automaticRuntimeParked && !automaticLifecycleQuiet
                && !automaticSurfaceReconcilePending) return;
        View root = binding.getRoot();
        int generation = overlayAttachGeneration;
        if (root.getAlpha() < 0.999f
                || !isCurrentOverlayAttachment(generation, root)) return;
        DeferredIntegrationStart existing = deferredIntegrationStart;
        if (integrationStartupScheduled && existing != null
                && existing.attachmentGeneration == generation && existing.root == root) return;
        cancelDeferredIntegrationStart();
        integrationStartupScheduled = true;
        DeferredIntegrationStart next = new DeferredIntegrationStart(generation, root);
        deferredIntegrationStart = next;
        try {
            Choreographer.getInstance().postFrameCallback(next);
        } catch (RuntimeException failure) {
            // Choreographer should always be available on the service main Looper. A broken OEM
            // implementation must not leave all connectors permanently stopped, however.
            Log.w(TAG, "Could not defer integrations to the first frame", failure);
            mainHandler.post(next);
        }
    }

    private void cancelDeferredIntegrationStart() {
        DeferredIntegrationStart pending = deferredIntegrationStart;
        deferredIntegrationStart = null;
        if (pending != null) {
            mainHandler.removeCallbacks(pending);
            try {
                Choreographer.getInstance().removeFrameCallback(pending);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not remove deferred integration startup", failure);
            }
        }
        if (!initialIntegrationStartupInProgress && !integrationsStarted) {
            integrationStartupScheduled = false;
        }
    }

    private boolean isCurrentOverlayAttachment(int generation, @NonNull View root) {
        return !destroyed && generation == overlayAttachGeneration && overlayAttached
                && binding != null && binding.getRoot() == root && root.isAttachedToWindow();
    }

    /** Reconfigures each independent integration without letting one bad provider block the rest. */
    private void reconfigureIntegrationControllers() {
        runIntegrationStep("MQTT", () -> {
            ensureMqttRuntimeGraph();
            mqttController.reconfigure();
        });
        // Load the exact selected-address boundary before the phone transport can emit its
        // current state. A device change therefore clears the old Sprut switch first.
        runIntegrationStep("phone presence", () -> {
            ensureSprutRuntimeGraph();
            phonePresenceExporter.reconfigure();
            phoneAncsPresenceExporter.reconfigure();
        });
        runIntegrationStep("phone", () -> {
            ensurePhoneRuntimeGraph();
            phoneController.reconfigure();
        });
        runIntegrationStep("car telemetry", () -> {
            ensureCarRuntimeGraph();
            carTelemetryExporter.reconfigure();
        });
        runIntegrationStep("Sprut.hub", () -> {
            ensureSprutRuntimeGraph();
            sprutController.reconfigure();
        });
        runIntegrationStep("Home Assistant", () -> {
            ensureHomeAssistantRuntimeGraph();
            haApiController.reconfigure();
        });
        runIntegrationStep("visual scenarios", () -> {
            ensureScenarioRuntimeGraph();
            scenarioController.reconfigure();
        });
        runIntegrationStep("intent scenarios", () -> {
            ensureIntentScenarioRuntimeGraph();
            intentScenarioController.reconfigure();
        });
    }

    private boolean runIntegrationStep(@NonNull String name, @NonNull Runnable step) {
        try {
            step.run();
            return true;
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not configure " + name, failure);
            return false;
        }
    }

    private void runCleanupStep(@NonNull String name, @NonNull Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not completely stop " + name, failure);
        }
    }

    private void applyPopupPreferencesSafely() {
        if (prefs == null || !Settings.canDrawOverlays(this)) return;
        ensurePopupOverlayManager();
        if (popupOverlay == null) return;
        try {
            popupOverlay.applyPreferences();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not apply popup overlays", failure);
        }
    }

    private void ensurePopupOverlayManager() {
        if (popupOverlay != null || prefs == null || !Settings.canDrawOverlays(this)
                || automationStates == null
                || actionDispatcher == null) return;
        popupOverlay = new PopupOverlayManager(this, prefs, automationStates,
                actionDispatcher, this::popupBuiltinValue);
    }

    private void ensurePhoneNotificationPopupConfigured() {
        if (phoneNotificationPopupConfigured || prefs == null) return;
        try {
            PhoneNotificationAutomation.ensureConfigured(prefs);
            phoneNotificationPopupConfigured = true;
        } catch (JSONException | RuntimeException failure) {
            Log.e(TAG, "Could not configure phone notification popup", failure);
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        dezz.status.widget.diagnostics.ActionRecorder.recordServiceIntent(
                getClass().getName(), intent == null ? null : intent.getAction(), startId);
        boolean explicitScenarioCommand = !destroyed && intent != null
                && ScenarioTriggerReceiver.ACTION_EXECUTE_RULE.equals(intent.getAction());
        // Queue before visual/headless admission. An explicit, already-authenticated user command
        // is allowed to keep a temporary headless host even when overlay AppOps are unavailable.
        if (explicitScenarioCommand) enqueueIntentScenarioCommand(intent);
        if (!runtimeInitialized) {
            boolean deferredStickyRestart = intent == null
                    && StartupWorkCoordinator.shouldDeferAutomaticStickyRestart(this);
            boolean stickyVisualSurface = deferredStickyRestart
                    && StartupWorkCoordinator.isUserUnlocked(this)
                    && WidgetServiceStarter.canStartVisualSurfaceWhileRuntimeParked(
                    Preferences.isStatusWidgetEnabledForVisualBootstrap(this),
                    Permissions.allPermissionsGranted(this));
            if (deferredStickyRestart && !stickyVisualSurface) {
                StartupWorkCoordinator.ensureIntegrationHostScheduled(this);
                stopForeground(true);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            boolean visualSurfaceOnly = intent != null
                    && WidgetServiceStarter.ACTION_START_VISIBLE_SURFACE.equals(
                    intent.getAction());
            automaticRuntimeParked = (visualSurfaceOnly || stickyVisualSurface)
                    && StartupWorkCoordinator.shouldParkAutomaticRuntime(this);
            if (automaticRuntimeParked) {
                StartupWorkCoordinator.ensureIntegrationHostScheduled(this);
                StartupPerformanceTrace.mark("integration_runtime_parked");
            }
            initializeRuntime();
        }
        if (!runtimeInitialized || prefs == null) return START_NOT_STICKY;
        if (explicitScenarioCommand) {
            // Explicit, authenticated user work is not an automatic boot reconnect. It may open
            // the runtime lane for its original bounded command deadline.
            explicitScenarioRuntimeOverride = true;
            mainHandler.removeCallbacks(explicitScenarioRuntimeOverrideRecheck);
            mainHandler.removeCallbacks(explicitScenarioRuntimeOverrideExpiry);
            mainHandler.postDelayed(explicitScenarioRuntimeOverrideExpiry,
                    TEMPORARY_SCENARIO_HOST_MAX_MS);
            automaticHostReleaseAfterVisible = false;
            mainHandler.removeCallbacks(automaticLifecycleQuietTeardown);
            if (automaticRuntimeParked || automaticLifecycleQuiet) {
                // Reuse the serialized reconnect lane. The command controller retries against its
                // absolute 15-second deadline while MQTT/HA/Sprut become authoritative again.
                resumeAutomaticLifecycleIntegrationsAfterQuiet();
            }
            armTemporaryScenarioHeadlessHostIfNeeded(
                    Permissions.allPermissionsGranted(this));
        }
        if (!destroyed && prefs != null
                && ((prefs.widgetEnabled.get() && binding == null
                && !overlayAttachRetryScheduled
                && Permissions.allPermissionsGranted(this))
                || (!prefs.widgetEnabled.get()
                && (binding != null || popupOverlay != null)))) {
            applyPreferences(false);
        }
        if (explicitScenarioCommand) {
            if (integrationsStarted) {
                drainPendingIntentScenarioCommands();
            } else if (!initialIntegrationStartupInProgress) {
                // A visible or rejected overlay must not hold an explicit physical command behind
                // WindowManager/host retries. This is bounded user work, not automatic boot work.
                runInitialIntegrationStartup();
            }
        }
        // A sticky restart restores the long-lived widget/connectors but carries no old command.
        // Re-delivering a TOGGLE after process death would be unsafe, so null intents do nothing.
        return START_STICKY;
    }

    private void enqueueIntentScenarioCommand(@NonNull Intent command) {
        if (pendingIntentScenarioCommands.size() >= MAX_PENDING_INTENT_SCENARIO_COMMANDS) {
            // Fail closed under a broadcast storm. Dropping the new command is safer than
            // evicting an older TOGGLE whose execution state is not yet known.
            Log.w(TAG, "Ignored Intent scenario command while startup queue is full");
            return;
        }
        pendingIntentScenarioCommands.addLast(new Intent(command));
    }

    /** Re-arms the bounded host even when a command reaches an already initialized service. */
    private void armTemporaryScenarioHeadlessHostIfNeeded(boolean overlayRuntimeAvailable) {
        if (prefs == null || pendingIntentScenarioCommands.isEmpty()
                || WidgetServiceStarter.requiresHeadlessHost(prefs)
                || (prefs.widgetEnabled.get() && overlayRuntimeAvailable)) return;
        temporaryScenarioHeadlessHost = true;
        mainHandler.removeCallbacks(temporaryScenarioHostExpiry);
        mainHandler.postDelayed(temporaryScenarioHostExpiry,
                TEMPORARY_SCENARIO_HOST_MAX_MS);
    }

    private void drainPendingIntentScenarioCommands() {
        drainPendingIntentScenarioCommands(true);
    }

    /**
     * Startup stage nine has just loaded the current rules on the worker, so its main publication
     * must not parse the same JSON once per queued command. Other callers retain the strict
     * reload-before-lookup boundary used after settings edits.
     */
    private void drainPendingIntentScenarioCommands(boolean reloadRules) {
        if (destroyed || pendingIntentScenarioCommands.isEmpty()) return;
        if (intentScenarioController == null) {
            if (!reloadRules) return;
            if (!integrationsStarted) return;
            runIntegrationStep("intent scenarios on demand", () -> {
                ensureIntentScenarioRuntimeGraph();
                intentScenarioController.reconfigure();
            });
            if (intentScenarioController == null) return;
        }
        Intent command;
        while ((command = pendingIntentScenarioCommands.pollFirst()) != null) {
            // Reload before lookup so a broadcast accepted from the latest device-protected
            // preferences cannot execute an older in-memory target after a settings edit.
            if (reloadRules) intentScenarioController.reconfigure();
            intentScenarioController.triggerRuleId(
                    command.getStringExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_ID),
                    command.getStringExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_TOKEN),
                    command.getStringExtra(ScenarioTriggerReceiver.EXTRA_RULE_FINGERPRINT),
                    command.getLongExtra(ScenarioTriggerReceiver.EXTRA_DEADLINE_ELAPSED, 0L));
        }
        reconcileExplicitScenarioRuntimeOverride(false);
        reconcileTemporaryScenarioHeadlessHost(false);
    }

    /**
     * Keeps the bounded runtime override until the accepted physical command has either completed
     * or reached its original monotonic deadline. Draining the service queue is not completion:
     * IntentScenarioController may still be waiting for a connector snapshot or acknowledgement.
     */
    private void reconcileExplicitScenarioRuntimeOverride(boolean force) {
        mainHandler.removeCallbacks(explicitScenarioRuntimeOverrideRecheck);
        if (!explicitScenarioRuntimeOverride || destroyed) return;
        boolean executionPending = !pendingIntentScenarioCommands.isEmpty()
                || (intentScenarioController != null
                && intentScenarioController.hasPendingExecutions());
        if (!force && executionPending) {
            mainHandler.postDelayed(explicitScenarioRuntimeOverrideRecheck,
                    TEMPORARY_SCENARIO_HOST_RECHECK_MS);
            return;
        }
        explicitScenarioRuntimeOverride = false;
        mainHandler.removeCallbacks(explicitScenarioRuntimeOverrideExpiry);
        if (StartupWorkCoordinator.shouldParkAutomaticRuntime(this)) {
            automaticRuntimeParked = true;
            StartupWorkCoordinator.ensureIntegrationHostScheduled(this);
        }
    }

    private void reconcileTemporaryScenarioHeadlessHost(boolean force) {
        mainHandler.removeCallbacks(temporaryScenarioHostRecheck);
        if (!temporaryScenarioHeadlessHost || destroyed || prefs == null) return;
        boolean executionPending = intentScenarioController != null
                && intentScenarioController.hasPendingExecutions();
        if (!force && (!pendingIntentScenarioCommands.isEmpty() || executionPending)) {
            mainHandler.postDelayed(temporaryScenarioHostRecheck,
                    TEMPORARY_SCENARIO_HOST_RECHECK_MS);
            return;
        }
        temporaryScenarioHeadlessHost = false;
        reconcileExplicitScenarioRuntimeOverride(force);
        pendingIntentScenarioCommands.clear();
        mainHandler.removeCallbacks(temporaryScenarioHostExpiry);
        boolean persistentSurfaceHost = prefs.widgetEnabled.get()
                && Permissions.allPermissionsGranted(this);
        if (binding == null && !persistentSurfaceHost
                && !WidgetServiceStarter.requiresHeadlessHost(prefs)) {
            Log.i(TAG, "Stopping temporary scenario host after command deadline/completion");
            stopSelf();
        }
    }

    private void createOverlayView() {
        if (destroyed || binding != null || prefs == null || !prefs.widgetEnabled.get()
                || overlayAttachRetryScheduled
                || !Permissions.allPermissionsGranted(this)) return;
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
        final int attachmentGeneration = ++overlayAttachGeneration;
        final View attachmentRoot = binding.getRoot();
        // Start invisible — the addView() below makes the window appear instantly; we then
        // fade the content in to match the symmetric fade-out the overlay does elsewhere.
        binding.getRoot().setAlpha(0f);
        binding.getRoot().setVisibility(View.VISIBLE);
        // Listen on the INNER container, not the outer FrameLayout. During a visibility
        // transition we pre-expand the *window* (root) to screenWidth as a buffer for
        // TransitionManager; if we listened on the root we'd see that buffer expand as a
        // huge layout change and shove overlayX by hundreds of pixels (and persist it).
        // The inner container's bounds are what TransitionManager animates smoothly.
        binding.overlayContainer.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            updateBackground();
            // Right-edge anchoring: when the widget content changes its measured width, shift the
            // window's left edge by the same amount so the right edge stays put. Done in a single
            // updateViewLayout to avoid the "shrink then slide" two-phase animation that
            // Gravity.RIGHT produces.
            if (params == null) return;
            int oldWidth = oldRight - oldLeft;
            int newWidth = right - left;
            boolean nonStatusBar = prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR;
            if (nonStatusBar
                    && prefs.widgetAlignRight.get() && oldWidth > 0 && newWidth > 0 && newWidth != oldWidth) {
                params.x += oldWidth - newWidth;
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                }
                prefs.overlayX.set(params.x);
            }
            notifyOverlayState();
        });

        // Synchronous "size about to change" hook. It fires from onMeasure before ViewRootImpl
        // pushes new wrap-content dimensions to WindowManager. Catching it mid-measure lets our
        // updateViewLayout(screenWidth) win the race, so an explicit visibility transition never
        // snaps below the children that are about to animate.
        binding.overlayContainer.setSizeChangeHint((oldW, newW, oldH, newH) -> {
            if (params == null) return;
            if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) return;
            if (pendingBufferedTransitions > 0) return;   // some transition already buffering
            // KX11 keeps the previous WRAP_CONTENT outer width when an already-visible brick
            // grows internally (notably signal bars followed later by the LTE text). Buffer both
            // directions so WindowManager receives a second natural-size relayout.
            beginBufferedTransition(true);
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            mainHandler.postDelayed(shrinkBufferSafetyClose,
                    BRICK_TRANSITION_DURATION_MS + 200);
        });

        // Never install an always-on LayoutTransition. A marquee invalidates once per display
        // frame and several Android 9 ECARX builds misclassify that invalidation as a bounds
        // change, making every sibling Settings/app icon visibly twitch. Show/hide remains
        // handled by the explicit buffered transition below.
        binding.overlayContainer.setLayoutTransition(null);

        // Set up drag listener (just registers a touch listener on the root view — safe to do
        // before addView since the listener captures touches once attached).
        setupDragListener();

        // Build the WindowManager params, then normalize every layout-affecting preference before
        // addView. The XML intentionally uses conspicuous 100sp preview sizes for time and status
        // icons; attaching that raw tree lets some Android 9 vendor WindowManagers retain the
        // oversized first measurement until a later MediaSession requestLayout. That is why the
        // row used to look tall after boot and suddenly become normal when the first song arrived.
        // applyBrickVisibility/applyBrickTarget explicitly suppress transitions while detached,
        // so this preflight cannot strand the buffered-transition counter.
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        params = new WindowManager.LayoutParams(
                statusBar
                        ? WindowManager.LayoutParams.MATCH_PARENT
                        : WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                ,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = statusBar ? 0 : prefs.overlayX.get();
        params.y = statusBar ? 0 : prefs.overlayY.get();
        params.windowAnimations = 0;

        overlayAttached = false;
        prepareOverlayGeometryBeforeAttach();
        try {
            windowManager.addView(attachmentRoot, params);
        } catch (Exception e) {
            Log.e(TAG, "Could not attach status overlay (attempt "
                    + (overlayAttachAttempts + 1) + ")", e);
            // Some vendor WindowManager implementations can throw after accepting the view.
            // Remove that partial attachment before dropping our reference and retrying.
            removeStatusOverlaySafely("failed attach");
            binding = null;
            params = null;
            overlayAttachAttempts++;
            if (!destroyed && prefs.widgetEnabled.get()) {
                mainHandler.removeCallbacks(overlayAttachRetry);
                long delay = Math.min(MAX_OVERLAY_ATTACH_RETRY_MS,
                        OVERLAY_ATTACH_RETRY_MS * Math.max(1, overlayAttachAttempts));
                overlayAttachRetryScheduled = true;
                mainHandler.postDelayed(overlayAttachRetry, delay);
            }
            // A transient status-row rejection must not freeze live smart-home state on the
            // independently attached driver rail while we wait for WindowManager's retry.
            if (WidgetServiceStarter.requiresHeadlessHost(prefs)
                    && !integrationsStarted) {
                runInitialIntegrationStartup();
            }
            return;
        }

        overlayAttached = true;
        if (ecarxNavigatorWindowObserver != null) {
            ecarxNavigatorWindowObserver.setTargetDisplayId(currentOverlayDisplayId());
            ecarxNavigatorWindowObserver.refresh("status-overlay-attached");
        }
        mainHandler.removeCallbacks(overlayAttachRetry);
        overlayAttachRetryScheduled = false;
        overlayAttachAttempts = 0;
        if (integrationsStarted && !automaticRuntimeParked && !automaticLifecycleQuiet) {
            // A later user-driven reattach reuses the live graph and needs its listeners now.
            applyPreferences(false);
        }

        updateWifiStatus();
        updateGnssStatus();

        // Fade in the geometry-only shell. Controller construction and system listener
        // registration begin only after the row is fully visible.
        attachmentRoot.animate()
                .alpha(1f)
                .setDuration(INITIAL_OVERLAY_FADE_DURATION_MS)
                .withEndAction(() -> completeInitialOverlayVisibility(
                        attachmentGeneration, attachmentRoot, false))
                .start();
        if (integrationsStarted && !automaticRuntimeParked && !automaticLifecycleQuiet) {
            // Dynamic headless -> status-row attach reuses the already-running connectors, but
            // popup windows still need to be recreated for the newly enabled status surface.
            applyPopupPreferencesSafely();
        }
        if (!integrationsStarted || automaticRuntimeParked || automaticLifecycleQuiet
                || automaticSurfaceReconcilePending) {
            // OEM animators have occasionally missed an end callback after a SurfaceFlinger
            // restart. This bounded idempotent fallback preserves headless/connectivity startup.
            mainHandler.postDelayed(() -> completeInitialOverlayVisibility(
                            attachmentGeneration, attachmentRoot, true),
                    INITIAL_OVERLAY_FADE_DURATION_MS + INITIAL_OVERLAY_FALLBACK_GRACE_MS);
        }
    }

    private void completeInitialOverlayVisibility(int generation, @NonNull View root,
                                                  boolean animatorFallback) {
        if (!isCurrentOverlayAttachment(generation, root)) return;
        if (animatorFallback && root.getAlpha() < 0.999f) {
            root.animate().cancel();
            root.setAlpha(1f);
        }
        if (root.getAlpha() < 0.999f || overlayVisibleGeneration == generation) return;
        overlayVisibleGeneration = generation;
        if (!integrationsStarted || automaticRuntimeParked || automaticLifecycleQuiet
                || automaticSurfaceReconcilePending) {
            scheduleInitialIntegrationStartupAfterFrame();
        }
    }

    /**
     * Applies only values that can affect the overlay's first measurement.
     *
     * <p>This deliberately runs before {@link WindowManager#addView(View,
     * ViewGroup.LayoutParams)} and does not start listeners/integrations. The normal
     * {@link #applyPreferences(boolean)} pass runs in its own post-visible stage and remains the
     * single owner of those lifecycle side effects.</p>
     */
    private void prepareOverlayGeometryBeforeAttach() {
        // HA descriptors were loaded once by the visual shell before inflation. Re-reading them
        // here used to parse the same JSON three times before the first visible frame.
        updateThemedContext();
        updateDateTime();

        List<BrickType> bricks = BrickType.parseOrder(prefs.brickOrder.get());
        Set<BrickType> bricksSet = EnumSet.noneOf(BrickType.class);
        bricksSet.addAll(bricks);

        // No child transition may start against a tree that WindowManager does not own yet.
        binding.overlayContainer.setLayoutTransition(null);
        reorderBricks(bricks);
        applyTimeBrickSettings();
        applyDateBrickSettings();
        applyMediaBrickSettings();
        applyWifiBrickSettings();
        applyGpsBrickSettings();
        applyBluetoothBrickSettings();
        applyPhoneCellularBrickSettings();
        applyPhoneBatteryBrickSettings();
        applyPhoneNetworkTypeBrickSettings();
        applyIndoorTempBrickSettings();
        applyOutdoorTempBrickSettings();
        renderHomeAssistantBricks(true);
        renderPhoneStatusBricks(true);
        updatePhoneIndicators();
        applyBrickVisibility(bricksSet);

        binding.overlayContainer.setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());
        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(bricksSet) + verticalPadding);
    }

    private void removeStatusOverlaySafely(@NonNull String reason) {
        overlayAttachGeneration++;
        overlayVisibleGeneration = -1;
        cancelDeferredIntegrationStart();
        if (binding == null || windowManager == null) {
            overlayAttached = false;
            return;
        }
        View root = binding.getRoot();
        root.animate().cancel();
        if (!overlayAttached && !root.isAttachedToWindow()) return;
        try {
            windowManager.removeView(root);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Status overlay was already detached during " + reason, failure);
        } finally {
            overlayAttached = false;
        }
    }

    /**
     * Replaces only the WindowManager root after SurfaceFlinger/QuickBoot ownership changes.
     * Location, connectivity, media and vehicle subscriptions stay intact; tearing those down
     * here would both create an early Binder burst and require a second full runtime startup.
     */
    private void revalidateStatusOverlayWindowOnly(@NonNull String reason) {
        mainHandler.removeCallbacks(overlayAttachRetry);
        overlayAttachRetryScheduled = false;
        overlayAttachAttempts = 0;
        if (binding != null) {
            binding.getRoot().animate().cancel();
            binding.overlayContainer.setLayoutTransition(null);
        }
        pendingBufferedTransitions = 0;
        removeStatusOverlaySafely(reason);
        binding = null;
        params = null;
        createOverlayView();
    }

    /**
     * Stops every listener and delayed task owned exclusively by the status-row surface while
     * leaving HA/MQTT/Sprut, phone, scenarios and the driver rail alive.
     *
     * <p>The driver panel can be enabled without the status row. Merely removing the WindowManager
     * view is not enough: queued clock/GNSS/Wi-Fi/media callbacks still render into {@link #binding}
     * and would either crash after it is cleared or briefly recreate stale work when the row is
     * enabled again. Keep this teardown symmetrical with the tracking section in
     * {@link #applyPreferences(boolean)}.</p>
     */
    private void detachStatusSurfaceRuntime(@NonNull String reason) {
        mainHandler.removeCallbacks(overlayAttachRetry);
        overlayAttachRetryScheduled = false;
        // A later explicit enable starts a fresh retry sequence. Otherwise a previous transient
        // WindowManager outage could make the first new retry wait the old 30-second maximum.
        overlayAttachAttempts = 0;
        mainHandler.removeCallbacks(updateDateTimeRunnable);
        mainHandler.removeCallbacks(foregroundAppCheckRunnable);
        mainHandler.removeCallbacks(updateGnssStatusRunnable);
        mainHandler.removeCallbacks(reachabilityProbeRunnable);
        mainHandler.removeCallbacks(satellitesCountResetRunnable);
        mainHandler.removeCallbacks(mediaProgressTick);
        mainHandler.removeCallbacks(shrinkBufferSafetyClose);
        mainHandler.removeCallbacks(popupRefresh);

        stopLocationTracking();
        stopConnectivityTracking();
        unregisterSatelliteStatusReceiver();
        unregisterBluetoothReceiver();
        runCleanupStep("status media tracking", this::disableMediaTracking);

        if (reachabilityChecker != null) {
            ReachabilityChecker checker = reachabilityChecker;
            reachabilityChecker = null;
            runCleanupStep("status reachability checker", checker::shutdown);
        }

        if (carTelemetryExporter != null) {
            runCleanupStep("status car sensor subscriptions", () -> {
                CarIntegration car = CarIntegrations.get(this);
                car.unsubscribe(BrickType.INDOOR_TEMP);
                car.unsubscribe(BrickType.OUTDOOR_TEMP);
            });
        }

        if (popupOverlay != null) {
            runCleanupStep("popup overlays", popupOverlay::destroy);
            popupOverlay = null;
        }

        if (binding != null) {
            binding.getRoot().animate().cancel();
            binding.overlayContainer.setLayoutTransition(null);
        }
        pendingBufferedTransitions = 0;
        if (!phoneNotificationForegroundTrackingNeeded()) {
            usageStatsManager = null;
            lastForegroundPackage = null;
        }
        overlayHiddenByApp = false;
        wifiState = WiFiState.OFF;
        gnssState = GnssState.OFF;
        lastLocationUpdateElapsed = 0L;
        btConnectedAddrs.clear();
        bluetoothState = BluetoothState.OFF;
        phoneAncsReady = false;
        lastMediaSubtitle = null;
        removeStatusOverlaySafely(reason);
        binding = null;
        params = null;
        // Notification deferral is independent from the optional status-row surface. Reconcile
        // its event/poll path after binding becomes null instead of silently losing foreground.
        if (phoneNotificationForegroundTrackingNeeded()) updateForegroundAppTracking();
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Re-create date/time formatters so a locale change is reflected.
        timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());
        currentDateFormatPattern = null;
        // If the user is in "follow system" mode, the system uiMode flip means the cached
        // themedContext now points at the wrong configuration — invalidate so the next
        // applyPreferences() rebuilds it.
        themedContext = null;
        appliedThemePref = -1;

        if (binding != null) {
            removeStatusOverlaySafely("configuration change");
            binding = null;
            params = null;
            createOverlayView();
        }
    }

    @SuppressLint("MissingPermission")
    public void applyPreferences() {
        applyPreferences(true);
    }

    /**
     * Wakes the already-running shared host after a driver-panel enable without reloading every
     * connector on each geometry slider change.
     *
     * <p>This also resumes a status attach that was paused by a temporary permission denial. It
     * deliberately leaves an existing attach retry alone so repeated settings events cannot bypass
     * its bounded backoff.</p>
     */
    void ensureEnabledRuntime() {
        if (destroyed || prefs == null) return;
        if (automaticRuntimeParked || automaticLifecycleQuiet) return;
        if (WidgetServiceStarter.requiresHeadlessHost(prefs)
                && !integrationsStarted) {
            runInitialIntegrationStartup();
        }
        if (prefs.widgetEnabled.get() && binding == null && !overlayAttachRetryScheduled
                && Permissions.allPermissionsGranted(this)) {
            createOverlayView();
        }
    }

    /**
     * Idempotent visual-only wake used by HOME/boot while controller work may still be parked.
     * It deliberately does not call applyPreferences or construct a headless runtime graph.
     */
    void ensureAutomaticVisualSurface() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::ensureAutomaticVisualSurface);
            return;
        }
        if (destroyed || prefs == null || !prefs.widgetEnabled.get()
                || binding != null || overlayAttachRetryScheduled
                || !Permissions.allPermissionsGranted(this)) return;
        createOverlayView();
    }

    /** Reattaches only the WindowManager root after QuickBoot; live connectors stay untouched. */
    public void revalidateAutomaticVisualSurfaceAfterQuickBoot() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::revalidateAutomaticVisualSurfaceAfterQuickBoot);
            return;
        }
        if (destroyed || prefs == null || !prefs.widgetEnabled.get()
                || !Permissions.allPermissionsGranted(this)) return;
        automaticSurfaceRevalidationRequired = false;
        revalidateStatusOverlayWindowOnly("immediate QuickBoot surface revalidation");
    }

    /**
     * QuickBoot can preserve this integration host while WindowManager/HUD processes are
     * recreated. Reconcile automatic surfaces after the quiet lane without reconnecting any
     * transport or replaying the full controller graph.
     */
    public void reconcileAutomaticLifecycleSurfaces() {
        if (destroyed || prefs == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::reconcileAutomaticLifecycleSurfaces);
            return;
        }
        automaticSurfaceReconcilePending = true;
        if (automaticSurfaceRevalidationRequired && prefs.widgetEnabled.get()) {
            automaticSurfaceRevalidationRequired = false;
            revalidateStatusOverlayWindowOnly("deferred QuickBoot surface revalidation");
        }
        boolean waitsForVisibleSurface = prefs.widgetEnabled.get()
                && Permissions.allPermissionsGranted(this);
        if (waitsForVisibleSurface) {
            // Persist the accepted host handoff before addView: a synchronous OEM WindowManager
            // rejection may clear binding, but a later bounded retry must still release this
            // exact parked runtime after its real frame.
            automaticHostReleaseAfterVisible = true;
            if (binding == null) createOverlayView();
            boolean surfaceReady = binding != null && overlayAttached
                    && overlayVisibleGeneration == overlayAttachGeneration
                    && binding.getRoot().getAlpha() >= 0.999f;
            if (binding != null && !surfaceReady) {
                // This flag is minted only by an accepted host-generation callback. The visible
                // callback may therefore release the parked graph, but can never open the host
                // barrier on its own.
                automaticHostReleaseAfterVisible = true;
                scheduleInitialIntegrationStartupAfterFrame();
                return;
            }
            if (surfaceReady) {
                boolean releasedParkedRuntime = automaticRuntimeParked
                        || automaticLifecycleQuiet;
                automaticHostReleaseAfterVisible = false;
                resumeAutomaticLifecycleIntegrationsAfterQuiet();
                if (!releasedParkedRuntime) finishAutomaticSurfaceReconcileIfReady();
                return;
            }
            if (!WidgetServiceStarter.requiresHeadlessHost(prefs)) return;
            automaticHostReleaseAfterVisible = false;
        }
        boolean releasedParkedRuntime = automaticRuntimeParked || automaticLifecycleQuiet;
        resumeAutomaticLifecycleIntegrationsAfterQuiet();
        if (!releasedParkedRuntime) finishAutomaticSurfaceReconcileIfReady();
    }

    private void finishAutomaticSurfaceReconcileIfReady() {
        if (!automaticSurfaceReconcilePending || automaticRuntimeParked
                || automaticLifecycleQuiet
                || initialIntegrationStartupInProgress || !integrationsStarted) return;
        automaticSurfaceReconcilePending = false;
        if (prefs.driverPanelEnabled.get()) {
            runIntegrationStep("automatic Driver surface reconcile",
                    () -> DriverPanelService.apply(this));
        }
        if (prefs.dimMenuPanelEnabled.get() && prefs.dimMenuPanelAutostart.get()) {
            runIntegrationStep("automatic DIM menu reconcile",
                    () -> DimMenuPanelService.reconcileAutomatic(this));
        }
        if (prefs.systemShadeEnabled.get() && prefs.systemShadeAutostart.get()) {
            runIntegrationStep("automatic system shade reconcile",
                    () -> SystemShadeService.reconcile(this, true));
        }
        if (prefs.hudPanelEnabled.get() && prefs.hudPanelAutostart.get()) {
            mainHandler.post(() -> {
                if (!destroyed && prefs != null && prefs.hudPanelEnabled.get()
                        && prefs.hudPanelAutostart.get()) {
                    runIntegrationStep("automatic HUD surface reconcile",
                            () -> HudPresentationService.reconcileAutomaticLifecycle(this));
                }
            });
        }
        schedulePendingAutomationUiRefresh();
    }

    /**
     * QuickBoot may preserve this process while every radio/vendor service underneath it flaps.
     * Park only reconnecting transports immediately; the FGS notification and cached UI remain.
     */
    public void enterAutomaticLifecycleQuiet() {
        enterAutomaticLifecycleQuiet(false);
    }

    public void enterAutomaticLifecycleQuiet(boolean revalidateVisualSurfaceImmediately) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            enterAutomaticLifecycleQuietOnMain(revalidateVisualSurfaceImmediately);
        } else {
            mainHandler.post(() -> enterAutomaticLifecycleQuietOnMain(
                    revalidateVisualSurfaceImmediately));
        }
    }

    private void enterAutomaticLifecycleQuietOnMain(
            boolean revalidateVisualSurfaceImmediately) {
        if (destroyed || !runtimeInitialized) return;
        if (!automaticLifecycleQuiet) {
            automaticLifecycleQuiet = true;
            automaticLifecycleResumeGeneration++;
            mainHandler.removeCallbacks(initialIntegrationStageRunner);
            cancelDeferredIntegrationStart();
            automaticHostReleaseAfterVisible = false;
        }
        if (revalidateVisualSurfaceImmediately && binding != null) {
            automaticSurfaceRevalidationRequired = true;
            mainHandler.removeCallbacks(automaticVisualSurfaceRevalidation);
            mainHandler.post(automaticVisualSurfaceRevalidation);
        }
        // The BroadcastReceiver-visible fence above is synchronous. Socket/Binder teardown is
        // deliberately posted so QuickBoot's main-thread broadcast can return immediately.
        mainHandler.removeCallbacks(automaticLifecycleQuietTeardown);
        automaticLifecycleTeardownStage = 0;
        mainHandler.post(automaticLifecycleQuietTeardown);
    }

    public void resumeAutomaticLifecycleIntegrationsAfterQuiet() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::resumeAutomaticLifecycleIntegrationsAfterQuiet);
            return;
        }
        if (destroyed || (!explicitScenarioRuntimeOverride
                && StartupWorkCoordinator.shouldParkAutomaticRuntime(this))) return;
        boolean hadParkedRuntime = automaticRuntimeParked || automaticLifecycleQuiet;
        if (!hadParkedRuntime) return;
        mainHandler.removeCallbacks(automaticLifecycleQuietTeardown);
        automaticRuntimeParked = false;
        automaticLifecycleQuiet = false;
        automaticHostReleaseAfterVisible = false;
        int generation = ++automaticLifecycleResumeGeneration;
        if (initialIntegrationStartupInProgress) {
            // Stage zero is a one-time persistence barrier. Every later partially-started graph is
            // replayed from stage one so stopped transports regain a fresh serialized session.
            if (initialIntegrationStage > 1) initialIntegrationStage = 1;
            initialIntegrationStageRetryCount = 0;
            mainHandler.removeCallbacks(initialIntegrationStageRunner);
            // Stage zero's background persistence barrier owns progression until its result is
            // committed. Its completion callback will resume this exact lane once quiet opens.
            if (!startupStateBarrierInFlight) mainHandler.post(initialIntegrationStageRunner);
            return;
        }
        if (!integrationsStarted) {
            runInitialIntegrationStartup();
            return;
        }
        credentialRefreshPending = false;
        Runnable[] stages = new Runnable[] {
                () -> { ensurePhoneRuntimeGraph(); phoneController.reconfigure(); },
                () -> { ensureMqttRuntimeGraph(); mqttController.reconfigure(); },
                () -> { ensureHomeAssistantRuntimeGraph(); haApiController.reconfigure(); },
                () -> { ensureSprutRuntimeGraph(); sprutController.reconfigure(); }
        };
        for (int index = 0; index < stages.length; index++) {
            final int stage = index;
            mainHandler.post(() -> {
                if (destroyed || automaticRuntimeParked || automaticLifecycleQuiet
                        || generation != automaticLifecycleResumeGeneration) return;
                runIntegrationStep("automatic lifecycle resume " + stage, stages[stage]);
                if (stage == stages.length - 1) {
                    schedulePendingAutomationUiRefresh();
                    finishAutomaticSurfaceReconcileIfReady();
                }
            });
        }
    }

    /**
     * Re-opens only Keystore-backed transports after USER_UNLOCKED.
     *
     * <p>Driver/HUD/Climate and status-window geometry belong to the already coalesced boot
     * generation and must not be rebuilt merely because credentials became readable.</p>
     */
    public void reconfigureCredentialBackedIntegrationsAfterUnlock() {
        if (destroyed || prefs == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(this::reconfigureCredentialBackedIntegrationsAfterUnlock);
            return;
        }
        if (initialIntegrationStartupInProgress) {
            credentialRefreshPending = true;
            return;
        }
        if (!integrationsStarted) {
            runInitialIntegrationStartup();
            return;
        }
        if (automaticRuntimeParked || automaticLifecycleQuiet) {
            // The staged lifecycle resume includes every Keystore-backed transport and Phone;
            // do not schedule a second overlapping MQTT/HA/Sprut pass for the same unlock edge.
            credentialRefreshPending = true;
            return;
        }
        if (credentialRefreshScheduled) return;
        credentialRefreshScheduled = true;
        runIntegrationStep("MQTT unlock", () -> {
            ensureMqttRuntimeGraph();
            mqttController.reconfigure();
        });
        mainHandler.post(() -> {
            if (destroyed) return;
            runIntegrationStep("Home Assistant unlock", () -> {
                ensureHomeAssistantRuntimeGraph();
                haApiController.reconfigure();
            });
            mainHandler.post(() -> {
                if (destroyed) return;
                runIntegrationStep("Sprut.hub unlock", () -> {
                    ensureSprutRuntimeGraph();
                    sprutController.reconfigure();
                });
                credentialRefreshScheduled = false;
                schedulePendingIntegrationReconfigure();
                schedulePendingAutomationUiRefresh();
            });
        });
    }

    /** Queues a fresh direct handshake for the explicit test button in Phone settings. */
    public boolean reconnectPhoneForDiagnostics() {
        PhoneConnectorController controller = phoneController;
        return controller != null && controller.reconnectForDiagnostics();
    }

    public boolean beginPhoneLeEnrollment(
            @NonNull PhoneConnectorController.LeEnrollmentListener listener) {
        PhoneConnectorController controller = phoneController;
        return controller != null && controller.beginSecureLeEnrollment(listener);
    }

    public boolean confirmPhoneLeEnrollmentSas(boolean matches) {
        PhoneConnectorController controller = phoneController;
        return controller != null && controller.confirmSecureLeEnrollmentSas(matches);
    }

    public void cancelPhoneLeEnrollment() {
        PhoneConnectorController controller = phoneController;
        if (controller != null) controller.cancelSecureLeEnrollment();
    }

    public boolean forgetPhoneLeEnrollment() {
        PhoneConnectorController controller = phoneController;
        return controller != null && controller.forgetSecureLeEnrollment();
    }

    @SuppressLint("MissingPermission")
    private void applyPreferences(boolean reconfigureIntegrations) {
        if (destroyed || prefs == null) return;
        refreshServiceWatchdog();
        if (reconfigureIntegrations
                && (initialIntegrationStartupInProgress || credentialRefreshScheduled)) {
            integrationReconfigurePending = true;
            reconfigureIntegrations = false;
        }
        if (prefs.phonePopupNotificationsEnabled.get()) {
            ensurePhoneNotificationPopupConfigured();
        }

        // Refresh hide targets before a disabled status surface detaches. Foreground ownership is
        // shared with popup notification deferral and must reflect the just-saved preferences
        // even when no status-row View will be recreated in this pass.
        hiddenInPackages = prefs.hideInPackages.get();
        rebuildEffectiveHideLists();

        boolean statusSurfaceEnabled = prefs.widgetEnabled.get();
        if (!statusSurfaceEnabled) {
            detachStatusSurfaceRuntime("status row disabled");
        }
        if (!WidgetServiceStarter.requiresIntegrationHost(prefs)) {
            stopSelf();
            return;
        }
        if (statusSurfaceEnabled && binding == null) {
            ensurePopupOverlayManager();
            createOverlayView();
            if (reconfigureIntegrations) {
                if (integrationsStarted) {
                    reconfigureIntegrationControllers();
                } else if (binding == null) {
                    runInitialIntegrationStartup();
                }
                if (integrationsStarted) applyPopupPreferencesSafely();
            }
            return;
        }

        boolean popupAppliedByStartup = false;
        if (reconfigureIntegrations) {
            if (integrationsStarted) {
                reconfigureIntegrationControllers();
            } else if (binding == null) {
                // USER_UNLOCKED can arrive while WindowManager is still rejecting the status
                // window. Credentials must nevertheless be re-read now; a later successful
                // attach uses the already-running authoritative connector sessions.
                runInitialIntegrationStartup();
                popupAppliedByStartup = integrationsStarted;
            } else {
                // Normal cold start: preserve the first-frame guarantee. The deferred startup
                // reads current preferences, so no separate pre-frame reconfigure is required.
                scheduleInitialIntegrationStartupAfterFrame();
            }
        }

        if (reconfigureIntegrations && !popupAppliedByStartup && integrationsStarted) {
            applyPopupPreferencesSafely();
        }
        if (!prefs.phoneStatusBarNotificationsEnabled.get()
                && !prefs.phonePopupNotificationsEnabled.get()) {
            cancelPhoneNotificationQueue();
        }
        if (!prefs.phonePopupNotificationsEnabled.get()) {
            clearPhonePopupNotification();
        } else if (integrationsStarted) {
            // The reserved overlay can be created by this preference pass after the manager's
            // previous catalog snapshot. Reconcile its state owner before an event arrives.
            applyPopupPreferencesSafely();
        }
        // A settings edit can remove the active blocker or shorten the wait. Reconcile even when
        // the optional status-row surface is disabled; popup-only delivery still owns this queue.
        safeUpdateForegroundAppTracking("phone notification preferences applied");
        reconcileDeferredPhoneNotifications();
        if (!prefs.phoneLowBatteryPresentedLatchMigration.get()) {
            // Older builds persisted the latch before queue/lock/overlay routing actually showed
            // the warning. Clear that ambiguous state once; new latches are presentation-backed.
            prefs.phoneLowBatteryAlertLatched.set(false);
            prefs.phoneLowBatteryAlertLatched2.set(false);
            prefs.phoneLowBatteryPresentedLatchMigration.set(true);
        }
        phoneLowBatteryAlertLatched = prefs.phoneLowBatteryAlertEnabled.get()
                && prefs.phoneLowBatteryAlertLatched.get();
        phoneLowBatteryAlertLatched2 = prefs.phoneLowBatteryAlertEnabled.get()
                && prefs.phoneLowBatteryAlertLatched2.get();
        if (!prefs.phoneLowBatteryAlertEnabled.get()) {
            phoneLowBatteryAlertPending = false;
            phoneLowBatteryAlertPending2 = false;
            if (prefs.phoneLowBatteryAlertLatched.get()) {
                prefs.phoneLowBatteryAlertLatched.set(false);
            }
            if (prefs.phoneLowBatteryAlertLatched2.get()) {
                prefs.phoneLowBatteryAlertLatched2.set(false);
            }
        }
        // Re-evaluate a freshly edited threshold even when only the phone popup is enabled and
        // the status-row View is deliberately detached. Delivery routing below remains governed
        // by the ordinary row/popup/lock/foreground switches.
        ConnectorValue currentPhoneBattery = phoneStatusValues.get("battery.level");
        if (currentPhoneBattery != null) {
            handlePhoneLowBatteryAlert(currentPhoneBattery);
        }
        if (binding == null) return;
        boolean activePhoneAlertDisabled = activePhoneBatteryAlertText != null
                ? !prefs.phoneLowBatteryAlertEnabled.get()
                || !prefs.phoneStatusBarNotificationsEnabled.get()
                : activePhoneNotification != null
                && !prefs.phoneStatusBarNotificationsEnabled.get();
        if (activePhoneAlertDisabled) {
            clearPhoneStatusNotification(true);
        }
        // The cold pass was parsed on startupStateWorker. Reparse here only after a real settings
        // edit changed the immutable preference snapshot; stage 3 must not repeat large JSON on
        // the main looper merely to register status listeners.
        if (haConfigs != null) {
            String currentMainJson = prefs.haMainBricksJson.get();
            if (!Objects.equals(currentMainJson, configuredMainBricksJson)) {
                configuredMainBricks = haConfigs.loadMain(currentMainJson);
                configuredMainBricksJson = currentMainJson;
            }
        }
        safeUpdateForegroundAppTracking("status preferences applied");
        updateThemedContext();
        updateBackground();
        updateDateTime();

        List<BrickType> bricks = BrickType.parseOrder(prefs.brickOrder.get());
        Set<BrickType> bricksSet = EnumSet.noneOf(BrickType.class);
        bricksSet.addAll(bricks);
        Set<BrickType> trackingSet = EnumSet.noneOf(BrickType.class);
        trackingSet.addAll(bricksSet);
        trackingSet.addAll(popupBuiltinTypes());
        trackingSet.addAll(driverInformationBrickTypes());

        // Keep implicit child transitions disabled in every mode. Only explicit visibility
        // changes are animated; ordinary text and icon frames must never move their siblings.
        binding.overlayContainer.setLayoutTransition(null);

        // Reorder children of the root LinearLayout to match brickOrder. Hidden bricks are
        // appended at the end with View.GONE — kept attached so we don't need to re-bind state.
        reorderBricks(bricks);

        // Apply each brick's settings (size/font, outline, margins) — independent of visibility.
        applyTimeBrickSettings();
        applyDateBrickSettings();
        applyMediaBrickSettings();
        applyWifiBrickSettings();
        applyGpsBrickSettings();
        applyBluetoothBrickSettings();
        applyPhoneCellularBrickSettings();
        applyPhoneBatteryBrickSettings();
        applyPhoneNetworkTypeBrickSettings();
        applyIndoorTempBrickSettings();
        applyOutdoorTempBrickSettings();
        renderHomeAssistantBricks(true);
        renderPhoneStatusBricks(true);
        updatePhoneIndicators();

        applyBrickVisibility(bricksSet);
        applyOverlayPosition();

        // Re-apply icon style for the current state — icon style and outline may have changed.
        updateWifiStatus();
        updateGnssStatus();
        updateBluetoothStatus();

        // User-controllable global padding around the widget content (four independent sides).
        // Was previously auto-computed as half of the largest brick dimension — many users found
        // it too wide on small head units, so it's now explicit prefs. Slight outline clipping
        // at thin paddings is acceptable.
        // Padding goes on the INNER container — that's the view with the rounded background.
        // Putting it on the outer FrameLayout instead leaves a transparent gutter around the
        // background rect (visible at non-zero padding) and shifts the background's rounded
        // corners outside the touchable area.
        binding.overlayContainer.setPadding(
                prefs.paddingLeft.get(),
                prefs.paddingTop.get(),
                prefs.paddingRight.get(),
                prefs.paddingBottom.get());

        // Lock the widget height to the tallest brick that's in the user's chosen order —
        // including bricks currently hidden per-app. Otherwise hiding e.g. a big Time brick
        // would let the row shrink vertically and the remaining icons would re-center up,
        // breaking alignment with the device status bar that users carefully tune.
        // {@code setMinimumHeight} compares against the view's *total* measured height (content
        // plus padding), so we add the vertical padding here — otherwise when the tallest brick
        // is visible the view measures to {@code maxBrick + padding} and when it's hidden it
        // collapses to {@code minHeight = maxBrick} (without padding), shrinking by the padding
        // amount on every hide.
        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(bricksSet) + verticalPadding);

        mainHandler.removeCallbacks(updateDateTimeRunnable);
        if (trackingSet.contains(BrickType.TIME) || trackingSet.contains(BrickType.DATE)) {
            long now = System.currentTimeMillis();
            long delay = DATETIME_UPDATE_INTERVAL_MS - (now % DATETIME_UPDATE_INTERVAL_MS);
            mainHandler.postDelayed(updateDateTimeRunnable, delay);
        }

        if (trackingSet.contains(BrickType.WIFI)) {
            ensureConnectivityTracking();
            updateWifiStatus();
        } else {
            stopConnectivityTracking();
        }

        if (trackingSet.contains(BrickType.GPS)) {
            ensureLocationTracking();
            if (prefs.gps.showSatelliteBadge.get()) {
                registerSatelliteStatusReceiver();
            } else {
                unregisterSatelliteStatusReceiver();
            }
            updateGnssStatus();
        } else {
            unregisterSatelliteStatusReceiver();
            stopLocationTracking();
        }

        if (trackingSet.contains(BrickType.BLUETOOTH)) {
            registerBluetoothReceiver();
            refreshBtConnectedFromProxies();
        } else {
            unregisterBluetoothReceiver();
            btConnectedAddrs.clear();
        }
        updateBluetoothStatus();

        if (trackingSet.contains(BrickType.MEDIA) && Permissions.isNotificationAccessGranted(this)) {
            enableMediaTracking();
        } else {
            disableMediaTracking();
            if (isPhoneNotificationActive()) {
                renderPhoneStatusNotification();
            } else {
                binding.mediaContainer.setVisibility(View.GONE);
            }
        }

        // Car temperature bricks — one subscription per brick through the flavor's
        // CarIntegration; the callback lands on the main thread per its contract.
        updateCarTempSubscription(BrickType.INDOOR_TEMP, trackingSet, binding.indoorTempText);
        updateCarTempSubscription(BrickType.OUTDOOR_TEMP, trackingSet, binding.outdoorTempText);
    }

    private void ensureConnectivityTracking() {
        if (connectivityManager == null) {
            try {
                connectivityManager = getSystemService(ConnectivityManager.class);
            } catch (RuntimeException failure) {
                Log.w(TAG, "ConnectivityManager is unavailable", failure);
            }
        }
        ConnectivityManager manager = connectivityManager;
        if (manager == null) return;

        boolean wifiPresent = false;
        try {
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    setWifiStatus(WiFiState.NO_INTERNET);
                    wifiPresent = true;
                    break;
                }
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not inspect active Wi-Fi networks", failure);
        }

        if (!networkCallbackRegistered) {
            try {
                NetworkRequest request = new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .build();
                // Deliver callbacks on the main thread: they touch overlay views and theme state.
                manager.registerNetworkCallback(request, networkCallback, mainHandler);
                networkCallbackRegistered = true;
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not register Wi-Fi network callback", failure);
            }
        }

        if (wifiPresent) {
            probeReachability();
        } else {
            // The status surface may have been disabled while Wi-Fi disconnected. Start every
            // reattach from the fresh ConnectivityManager snapshot, not its previous badge.
            setWifiStatus(WiFiState.OFF);
        }
        mainHandler.removeCallbacks(reachabilityProbeRunnable);
        mainHandler.postDelayed(reachabilityProbeRunnable, INTERNET_PROBE_INTERVAL_MS);
        refreshWifiSignalLevel();
        if (!wifiRssiReceiverRegistered) {
            try {
                registerReceiver(wifiRssiReceiver,
                        new IntentFilter(WifiManager.RSSI_CHANGED_ACTION));
                wifiRssiReceiverRegistered = true;
            } catch (RuntimeException failure) {
                Log.w(TAG, "Could not register Wi-Fi RSSI receiver", failure);
            }
        }
    }

    private void stopConnectivityTracking() {
        mainHandler.removeCallbacks(reachabilityProbeRunnable);
        if (wifiRssiReceiverRegistered) {
            try {
                unregisterReceiver(wifiRssiReceiver);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Wi-Fi RSSI receiver was already unregistered", failure);
            }
            wifiRssiReceiverRegistered = false;
        }
        ConnectivityManager manager = connectivityManager;
        if (manager != null && networkCallbackRegistered) {
            try {
                manager.unregisterNetworkCallback(networkCallback);
            } catch (RuntimeException failure) {
                Log.w(TAG, "Wi-Fi network callback was already unregistered", failure);
            }
        }
        networkCallbackRegistered = false;
        connectivityManager = null;
        wifiSignalLevel = 0;
    }

    @SuppressLint("MissingPermission")
    private void ensureLocationTracking() {
        if (locationManager == null) {
            try {
                locationManager = getSystemService(LocationManager.class);
            } catch (RuntimeException failure) {
                Log.w(TAG, "LocationManager is unavailable", failure);
            }
        }
        LocationManager manager = locationManager;
        if (manager == null) return;

        if (!gnssStatusCallbackRegistered) {
            try {
                gnssStatusCallbackRegistered = manager.registerGnssStatusCallback(
                        gnssStatusCallback, mainHandler);
                if (!gnssStatusCallbackRegistered) {
                    Log.w(TAG, "GNSS status callback registration was rejected");
                }
            } catch (RuntimeException failure) {
                gnssStatusCallbackRegistered = false;
                Log.w(TAG, "Could not register GNSS status callback", failure);
            }
        }

        if (!locationUpdatesRegistered) {
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                        GNSS_LOCATION_INTERVAL_MS, 0, locationListener,
                        Looper.getMainLooper());
                locationUpdatesRegistered = true;
            } catch (RuntimeException failure) {
                locationUpdatesRegistered = false;
                Log.w(TAG, "Could not request GPS location updates", failure);
            }
        }

        scheduleGnssFreshnessDeadline();
    }

    private void scheduleGnssFreshnessDeadline() {
        mainHandler.removeCallbacks(updateGnssStatusRunnable);
        if (!locationUpdatesRegistered || lastLocationUpdateElapsed <= 0L) return;
        long age = Math.max(0L,
                SystemClock.elapsedRealtime() - lastLocationUpdateElapsed);
        if (age >= GNSS_FIX_OFF_AFTER_MS) {
            mainHandler.post(updateGnssStatusRunnable);
        } else {
            long boundary = age < GNSS_FIX_DEGRADED_AFTER_MS
                    ? GNSS_FIX_DEGRADED_AFTER_MS : GNSS_FIX_OFF_AFTER_MS;
            mainHandler.postDelayed(updateGnssStatusRunnable,
                    Math.max(1L, boundary - age));
        }
    }

    private void stopLocationTracking() {
        mainHandler.removeCallbacks(updateGnssStatusRunnable);
        LocationManager manager = locationManager;
        if (manager != null && locationUpdatesRegistered) {
            try {
                manager.removeUpdates(locationListener);
            } catch (RuntimeException failure) {
                Log.w(TAG, "GPS location updates were already removed", failure);
            }
        }
        if (manager != null && gnssStatusCallbackRegistered) {
            try {
                manager.unregisterGnssStatusCallback(gnssStatusCallback);
            } catch (RuntimeException failure) {
                Log.w(TAG, "GNSS status callback was already unregistered", failure);
            }
        }
        locationUpdatesRegistered = false;
        gnssStatusCallbackRegistered = false;
        lastLocationUpdateElapsed = 0L;
        locationManager = null;
    }

    /** Applies only floating-window geometry/visibility. Used by live popup sliders so changing
     * a pixel value does not re-scan every connector binding on every touch sample. */
    public void applyPopupPreferences() {
        if (destroyed || popupOverlay == null) return;
        applyPopupPreferencesSafely();
    }

    /** Applies a popup tile's rules/action/style live from in-memory connector snapshots. This
     * deliberately does not call connector reconfigure(), so an offline connector is not
     * restarted and a large Sprut catalog is not fetched while the user drags a slider. */
    public void applyPopupItemPreferences() {
        if (destroyed || popupOverlay == null) return;
        if (mqttController != null) mqttController.reapplyPopupBindings();
        if (sprutController != null) sprutController.reapplyPopupBindings();
        if (haApiController != null) haApiController.reapplyPopupBindings();
        applyPopupPreferencesSafely();
    }

    /** Starts or hands off the non-persistent WYSIWYG phone-notification preview. */
    public void startPhoneNotificationEditorPreview(@NonNull String overlayId) {
        if (!PhoneNotificationAutomation.isNotificationOverlayId(overlayId)) return;
        mainHandler.post(() -> {
            if (destroyed) return;
            mainHandler.removeCallbacks(phoneEditorPreviewStop);
            pendingPhoneEditorPreviewStopId = null;
            ensurePhoneNotificationPopupConfigured();
            ensurePopupOverlayManager();
            if (popupOverlay == null) return;
            activePhoneEditorPreviewOverlayId = overlayId;
            popupOverlay.startEditorPreview(overlayId);
        });
    }

    /**
     * Delayed release lets PopupSettingsActivity hand the same preview to the precise tile editor
     * without a visible hide/show flash. Leaving settings altogether still removes it promptly.
     */
    public void schedulePhoneNotificationEditorPreviewStop(@NonNull String overlayId) {
        if (!PhoneNotificationAutomation.isNotificationOverlayId(overlayId)) return;
        mainHandler.post(() -> {
            if (destroyed || !overlayId.equals(activePhoneEditorPreviewOverlayId)) return;
            pendingPhoneEditorPreviewStopId = overlayId;
            mainHandler.removeCallbacks(phoneEditorPreviewStop);
            mainHandler.postDelayed(phoneEditorPreviewStop,
                    PHONE_EDITOR_PREVIEW_HANDOFF_MS);
        });
    }

    /** Live main-row appearance/rule update without restarting an offline connector. */
    public void applyMainItemPreferences() {
        if (destroyed || binding == null) return;
        if (mqttController != null) mqttController.reapplyMainBindings();
        if (sprutController != null) sprutController.reapplyMainBindings();
        if (haApiController != null) haApiController.reapplyMainBindings();
        applyPreferences(false);
    }

    private Set<BrickType> popupBuiltinTypes() {
        String overlaysJson = prefs.popupOverlaysJson.get();
        String itemsJson = prefs.popupItemsJson.get();
        if (Objects.equals(overlaysJson, configuredPopupOverlaysJson)
                && Objects.equals(itemsJson, configuredPopupItemsJson)) {
            return configuredPopupBuiltinTypes;
        }
        Set<BrickType> result = immutableBrickTypes(loadPopupBuiltinTypes());
        // A legacy load may persist its projection, so capture the post-load documents.
        configuredPopupOverlaysJson = prefs.popupOverlaysJson.get();
        configuredPopupItemsJson = prefs.popupItemsJson.get();
        configuredPopupBuiltinTypes = result;
        return result;
    }

    @NonNull
    private Set<BrickType> loadPopupBuiltinTypes() {
        Set<BrickType> result = EnumSet.noneOf(BrickType.class);
        Set<String> enabledOverlays = new HashSet<>();
        for (PopupOverlayConfig overlay : new PopupOverlayConfigStore(prefs).load()) {
            if (overlay.enabled) enabledOverlays.add(overlay.id);
        }
        if (enabledOverlays.isEmpty()) return result;
        for (PopupItemConfig item : new PopupItemConfigStore(prefs).load()) {
            if (!item.enabled || !enabledOverlays.contains(item.overlayId)
                    || !PopupItemConfig.TYPE_BUILTIN.equals(item.type)) continue;
            for (BrickType type : BrickType.values()) {
                if (type.automationId().equals(item.builtinId)) result.add(type);
            }
        }
        return result;
    }

    @NonNull
    private static Set<BrickType> immutableBrickTypes(@NonNull Set<BrickType> source) {
        Set<BrickType> copy = EnumSet.noneOf(BrickType.class);
        copy.addAll(source);
        return Collections.unmodifiableSet(copy);
    }

    private boolean isPopupBuiltinRequested(BrickType type) {
        return popupBuiltinTypes().contains(type);
    }

    /** Adds only the delayed ECARX-backed temperature portion to an already visible row. */
    private void refreshCarStatusSurface() {
        if (binding == null || carTelemetryExporter == null) return;
        Set<BrickType> visible = currentBrickSet();
        Set<BrickType> tracking = EnumSet.noneOf(BrickType.class);
        tracking.addAll(visible);
        tracking.addAll(popupBuiltinTypes());
        tracking.addAll(driverInformationBrickTypes());
        updateCarTempSubscription(BrickType.INDOOR_TEMP, tracking, binding.indoorTempText);
        updateCarTempSubscription(BrickType.OUTDOOR_TEMP, tracking, binding.outdoorTempText);
        applyBrickVisibility(visible);
        int verticalPadding = binding.overlayContainer.getPaddingTop()
                + binding.overlayContainer.getPaddingBottom();
        binding.overlayContainer.setMinimumHeight(
                computeMinWidgetHeight(visible) + verticalPadding);
    }

    private void updateCarTempSubscription(BrickType type, Set<BrickType> bricksSet,
                                           OutlineTextView target) {
        if (carTelemetryExporter == null) {
            // The visual shell reserves configured geometry without touching the ECARX Binder.
            if (bricksSet.contains(type) && target.getText().length() == 0) {
                target.setText(TEMP_PLACEHOLDER);
            }
            return;
        }
        CarIntegration car = CarIntegrations.get(this);
        if (bricksSet.contains(type)) {
            // Subscribe regardless of isBrickSupported(): right after boot the vendor service
            // may not have connected yet and support reads as "unknown/error" — but the SDK
            // queues listener registrations locally, so subscribing now means data starts
            // flowing the moment the service comes up. Visibility is gated separately in
            // applyBrickVisibility, and the availability-changed callback re-runs
            // applyPreferences when the support answer flips.
            if (target.getText().length() == 0) {
                // Placeholder until the first value arrives, so the brick occupies its slot
                // instead of rendering as a zero-width hole.
                target.setText(TEMP_PLACEHOLDER);
            }
            car.subscribe(type, (brickType, value) -> {
                if (binding == null) return;
                // The rolling ambient filter may intentionally republish its current median
                // while sub-second raw packets are discarded. Avoid turning those identical
                // values into needless status-row measure/layout passes.
                setTextIfChanged(target, formatTemperature(value));
                schedulePopupRefresh();
            });
        } else {
            car.unsubscribe(type);
            // Reset so a re-added brick starts from the placeholder, not a stale reading.
            target.setText(TEMP_PLACEHOLDER);
        }
    }

    /** Last rendered media subtitle — used to distinguish a real track change from the
     *  once-a-second metadata republishes some players emit (see updateMediaInfo). */
    @Nullable
    private String lastMediaSubtitle = null;

    /** Shown while a subscribed temperature brick has not yet received a plausible value. */
    private static final String TEMP_PLACEHOLDER = "--°";

    /** {@code TextView.setText} drops the layout and forces a relayout even for identical text —
     *  callers on hot paths (per-second player callbacks) must skip unchanged values. */
    private static void setTextIfChanged(android.widget.TextView view, CharSequence text) {
        if (!TextUtils.equals(view.getText(), text)) {
            view.setText(text);
        }
    }

    private static String formatTemperature(float celsius) {
        // Integer rounding via Math.round avoids "%.0f"-style "-0°" for readings in (-0.5, 0).
        return Math.round(celsius) + "°";
    }

    private void reorderBricks(List<BrickType> bricks) {
        // Adding/removing a brick changes child order/membership of the root.
        // applyBrickVisibility() (called right after this from applyPreferences) drives the
        // per-brick fade + width animation that gives us the "dynamic island" feel; we
        // just rearrange children here.
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            reorderForStatusBar(bricks);
        } else {
            reorderForFloating(bricks);
        }
    }

    private void reorderForFloating(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        // Status-bar group containers and spacers are hidden in floating mode and emptied so
        // bricks live as direct children of the root again.
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();
        binding.startGroup.setVisibility(View.GONE);
        binding.centerGroup.setVisibility(View.GONE);
        binding.endGroup.setVisibility(View.GONE);
        binding.startCenterSpacer.setVisibility(View.GONE);
        binding.centerEndSpacer.setVisibility(View.GONE);

        List<View> expected = new ArrayList<>();
        // Re-include the (empty) groups + spacers so their visibility=GONE keeps them out of
        // measure but the views remain attached to the same root for next switch.
        expected.add(binding.startGroup);
        expected.add(binding.startCenterSpacer);
        expected.add(binding.centerGroup);
        expected.add(binding.centerEndSpacer);
        expected.add(binding.endGroup);
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v != null) expected.add(v);
        }
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) expected.add(v);
            }
        }
        applyChildOrder(root, expected);
    }

    private void reorderForStatusBar(List<BrickType> bricks) {
        LinearLayout root = binding.overlayContainer;
        // Detach bricks from wherever they currently sit (root or any group).
        binding.startGroup.removeAllViews();
        binding.centerGroup.removeAllViews();
        binding.endGroup.removeAllViews();

        // Root order: startGroup, spacer, centerGroup, spacer, endGroup. Hidden bricks dangle off
        // the root after these so they remain attached but invisible.
        List<View> rootChildren = new ArrayList<>();
        rootChildren.add(binding.startGroup);
        rootChildren.add(binding.startCenterSpacer);
        rootChildren.add(binding.centerGroup);
        rootChildren.add(binding.centerEndSpacer);
        rootChildren.add(binding.endGroup);
        for (BrickType type : BrickType.values()) {
            if (!bricks.contains(type)) {
                View v = viewForBrick(type);
                if (v != null) rootChildren.add(v);
            }
        }
        applyChildOrder(root, rootChildren);

        // Distribute visible bricks into the proper alignment group.
        for (BrickType type : bricks) {
            View v = viewForBrick(type);
            if (v == null) continue;
            int alignment = clampAlignment(prefs.statusAlignmentFor(type).get());
            LinearLayout target = (alignment == 1) ? binding.centerGroup
                    : (alignment == 2) ? binding.endGroup
                    : binding.startGroup;
            target.addView(v);
        }

        binding.startGroup.setVisibility(View.VISIBLE);
        binding.centerGroup.setVisibility(View.VISIBLE);
        binding.endGroup.setVisibility(View.VISIBLE);
        binding.startCenterSpacer.setVisibility(View.VISIBLE);
        binding.centerEndSpacer.setVisibility(View.VISIBLE);
    }

    private static void applyChildOrder(ViewGroup parent, List<View> expected) {
        boolean inOrder = parent.getChildCount() == expected.size();
        if (inOrder) {
            for (int i = 0; i < expected.size(); i++) {
                if (parent.getChildAt(i) != expected.get(i)) {
                    inOrder = false;
                    break;
                }
            }
        }
        if (inOrder) return;
        parent.removeAllViews();
        for (View v : expected) {
            ViewGroup p = (ViewGroup) v.getParent();
            if (p != null) p.removeView(v);
            parent.addView(v);
        }
    }

    private static int clampAlignment(int v) {
        return v < 0 ? 0 : (v > 2 ? 2 : v);
    }

    @Nullable
    private View viewForBrick(BrickType type) {
        switch (type) {
            case TIME:
                return binding.timeText;
            case DATE:
                return binding.dateText;
            case MEDIA:
                return binding.mediaContainer;
            case WIFI:
                return binding.wifiStatusIcon;
            case GPS:
                return binding.gnssStatusIcon;
            case BLUETOOTH:
                return binding.bluetoothStatusIcon;
            case INDOOR_TEMP:
                return binding.indoorTempText;
            case OUTDOOR_TEMP:
                return binding.outdoorTempText;
            case HOME_ASSISTANT:
                return binding.homeAssistantContainer;
            case PHONE_STATUS:
                return binding.phoneStatusContainer;
            case PHONE_CELLULAR:
                return binding.phoneCellularContainer;
            case PHONE_BATTERY:
                return binding.phoneBatteryStatusIcon;
            case PHONE_NETWORK_TYPE:
                return binding.phoneNetworkTypeText;
            default:
                return null;
        }
    }

    private void applyTimeBrickSettings() {
        applySingleLineTextBrick(binding.timeText, prefs.time);
    }

    private void applyIndoorTempBrickSettings() {
        applySingleLineTextBrick(binding.indoorTempText, prefs.indoorTemp);
    }

    private void applyOutdoorTempBrickSettings() {
        applySingleLineTextBrick(binding.outdoorTempText, prefs.outdoorTemp);
    }

    /** Reconciles the dynamic smart-home row without reallocating every tile on each packet. */
    private void renderHomeAssistantBricks() {
        renderHomeAssistantBricks(false);
    }

    private void renderHomeAssistantBricks(boolean forceStyle) {
        if (binding == null || automationStates == null || haConfigs == null) return;
        LinearLayout container = binding.homeAssistantContainer;
        Map<String, MarqueeOutlineTextView> existing = new LinkedHashMap<>();
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            Object tag = child.getTag();
            if (child instanceof MarqueeOutlineTextView && tag instanceof String) {
                existing.put((String) tag, (MarqueeOutlineTextView) child);
            }
        }
        List<MarqueeOutlineTextView> desired = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (HaBrickConfig config : configuredMainBricks) {
            if (!config.enabled) continue;
            AutomationState state = automationStates.get(AutomationContract.SCOPE_MAIN, config.id);
            if (!state.visible) continue;

            boolean hiddenByOwnAppList = matchesForegroundContext(config.hideInPackages);
            boolean hiddenByGroupList = config.inheritGroupHide
                    && isBrickHiddenByApp(BrickType.HOME_ASSISTANT);
            if ((hiddenByOwnAppList || hiddenByGroupList) && !config.hideKeepsSpace) continue;

            boolean stale = state.present
                    && state.isStale(now, config.staleAfterSeconds * 1000L);
            String text;
            String color;
            if (!state.present) {
                text = config.pendingText;
                color = config.pendingColor;
            } else if (stale) {
                text = config.staleText;
                color = config.staleColor;
            } else if (state.text == null) {
                text = config.defaultText;
                color = config.defaultColor;
            } else if (TextUtils.isEmpty(state.text)) {
                text = config.emptyText;
                color = TextUtils.isEmpty(state.color) ? config.emptyColor : state.color;
            } else {
                text = state.text;
                color = TextUtils.isEmpty(state.color) ? config.defaultColor : state.color;
            }
            if (config.collapseWhenEmpty && TextUtils.isEmpty(text)) continue;
            // A transparent value selected by a value rule means "hide this brick", not
            // "reserve its margins for invisible text". Keep this renderer-side guard for
            // retained states written by older builds before connectors recompute visibility.
            if (AutomationState.isFullyTransparentColor(color)) continue;

            MarqueeOutlineTextView view = existing.remove(config.id);
            boolean created = view == null;
            if (created) {
                view = new MarqueeOutlineTextView(
                        themedContext != null ? themedContext : this);
                view.setTag(config.id);
                view.setIncludeFontPadding(false);
                view.setSingleLine(true);
            }
            if (created || forceStyle) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, config.fontSize);
                view.setTypeface(Fonts.resolve(this, config.fontFamily,
                        config.bold, config.italic));
                int outlineBase = AutomationState.parseColor(
                        config.outlineColor, 0xFF000000);
                view.setOutlineColor((outlineBase & 0x00FFFFFF)
                        | (config.outlineAlpha << 24));
                view.setOutlineWidth(config.outlineWidth);
                view.setTranslationY(config.adjustY);
                view.setPadding(config.paddingLeft, config.paddingTop,
                        config.paddingRight, config.paddingBottom);
                if (config.maxWidth > 0) view.setMaxWidth(config.maxWidth);
                else view.setMaxWidth(Integer.MAX_VALUE);
                view.setMarqueeEnabled(config.marquee);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.CENTER_VERTICAL;
                lp.setMarginStart(config.marginStart);
                lp.setMarginEnd(config.marginEnd);
                view.setLayoutParams(lp);
            }
            int textColor = AutomationState.parseColor(color, 0xFFFFFFFF);
            if (view.getCurrentTextColor() != textColor) view.setTextColor(textColor);
            float alpha = (hiddenByOwnAppList || hiddenByGroupList)
                    ? 0f : config.contentAlpha / 255f;
            if (view.getAlpha() != alpha) view.setAlpha(alpha);
            view.setMarqueeText(text);
            desired.add(view);
        }
        // Remove hidden/deleted bricks, then move only children whose configured order changed.
        for (MarqueeOutlineTextView obsolete : existing.values()) {
            container.removeView(obsolete);
        }
        for (int index = 0; index < desired.size(); index++) {
            MarqueeOutlineTextView view = desired.get(index);
            if (index < container.getChildCount() && container.getChildAt(index) == view) continue;
            ViewGroup.LayoutParams layout = view.getLayoutParams();
            if (view.getParent() == container) container.removeView(view);
            if (layout == null) {
                layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            container.addView(view, index, layout);
        }
        if (forceStyle) {
            applyHorizontalMargins(container, prefs.homeAssistant.marginStart.get(),
                    prefs.homeAssistant.marginEnd.get());
            container.setTranslationY(prefs.homeAssistant.adjustY.get());
            container.setAlpha(prefs.homeAssistant.contentAlpha.get() / 255f);
        }
    }

    /** Applies one immutable PHONE registry burst on the main thread. */
    private void onPhoneValuesChanged(@NonNull List<ConnectorValue> changedValues) {
        if (destroyed || prefs == null) return;
        boolean previousAncsReady = phoneAncsReady;
        ConnectorValue latestNotification = null;
        ConnectorValue notificationItems = null;
        ConnectorValue batteryLevel = null;
        boolean phonePresentationGateChanged = false;
        boolean phoneValueChanged = false;
        boolean sessionEnded = false;
        for (ConnectorValue value : changedValues) {
            if (value == null || value.connectorType != ConnectorType.PHONE) continue;
            phoneStatusValues.put(value.resourceId, value);
            phoneValueChanged = true;
            if ("connected".equals(value.resourceId)
                    && Boolean.FALSE.equals(value.rawValue)) {
                sessionEnded = true;
            }
            if ("profiles.ancs".equals(value.resourceId)) {
                phoneAncsReady = value.fresh && value.available && value.readable
                        && Boolean.TRUE.equals(value.rawValue);
            }
            if ("notifications.latest".equals(value.resourceId)) {
                latestNotification = value;
            } else if ("notifications.items".equals(value.resourceId)) {
                notificationItems = value;
            } else if ("battery.level".equals(value.resourceId)) {
                batteryLevel = value;
            } else if ("device.locked".equals(value.resourceId)) {
                phonePresentationGateChanged = true;
            }
        }
        if (!phoneValueChanged) return;
        suppressPhoneNotificationsUnlessLockAllows();
        if (sessionEnded) {
            phoneAncsReady = false;
            observedPhoneNotificationKeys.clear();
            cancelPhoneNotificationQueue();
            clearPhonePopupNotification();
            if (activePhoneBatteryAlertText != null) {
                clearPhoneStatusNotification(true);
            }
        }

        if (!sessionEnded && latestNotification != null
                && phoneNotificationForegroundTrackingNeeded()
                && lastForegroundPackage == null) {
            // Accessibility seeds event-driven state immediately. Usage access is the explicit
            // Android 9 fallback; sample before enqueueing so the first delivery cannot slip
            // past the selected full-screen blocker before its normal fallback cadence.
            safeCheckForegroundApp("phone notification arrival");
        }
        if (latestNotification != null) {
            // Observe the delivery even while status-row notifications are disabled. Enabling
            // the preference later must not replay whatever happened to be the last phone item.
            String latestKey = PhoneStatusBarPolicy.notificationKey(
                    latestNotification.rawValue);
            boolean latestIsNew = latestKey != null
                    && !observedPhoneNotificationKeys.contains(latestKey);
            rememberPhoneNotificationItems(notificationItems);
            rememberPhoneNotificationKey(latestKey);
            if (latestIsNew && phoneNotificationAllowedByLockState()) {
                if (prefs.phoneStatusBarNotificationsEnabled.get()
                        || prefs.phonePopupNotificationsEnabled.get()) {
                    Set<String> selected = PhoneStatusBarPolicy.parseIds(
                            prefs.phoneStatusBarNotificationFields.get(),
                            PhoneStatusBarPolicy.notificationFieldIds());
                    PhoneStatusBarPolicy.NotificationPresentation presentation =
                            PhoneStatusBarPolicy.notification(latestNotification, selected);
                    if (presentation != null) {
                        enqueuePhoneNotification(presentation, selected);
                    }
                }
            }
        }
        if (!sessionEnded && batteryLevel != null) {
            handlePhoneLowBatteryAlert(batteryLevel);
        } else if (!sessionEnded && phonePresentationGateChanged) {
            // A low-battery sample may have arrived while the only-when-locked gate rejected it.
            // Re-evaluate when that gate changes even if the percentage itself is unchanged.
            ConnectorValue currentBattery = phoneStatusValues.get("battery.level");
            if (currentBattery != null) handlePhoneLowBatteryAlert(currentBattery);
        }

        if (binding != null) {
            renderPhoneStatusBricks();
            updatePhoneIndicators();
            applyBrickVisibility(currentBrickSet());
            updateBluetoothStatus();
        }
        if (previousAncsReady != phoneAncsReady && prefs.driverPanelEnabled.get()) {
            // ANCS-gated information rows are structural: rebuilding only on the actual profile
            // transition lets controls reclaim the row's height and avoids polling the rail.
            if (automaticSurfaceRefreshSuppressed()) {
                onAutomationStateChanged(AutomationContract.SCOPE_DRIVER,
                        "builtin.phone_ancs_ready");
            } else {
                DriverPanelService.apply(this);
            }
        }
        schedulePopupRefresh();
    }

    private void rememberPhoneNotificationItems(@Nullable ConnectorValue value) {
        if (value == null || !(value.rawValue instanceof List<?>)) return;
        for (Object item : (List<?>) value.rawValue) {
            rememberPhoneNotificationKey(PhoneStatusBarPolicy.notificationKey(item));
        }
    }

    private void rememberPhoneNotificationKey(@Nullable String key) {
        if (TextUtils.isEmpty(key)) return;
        observedPhoneNotificationKeys.add(key);
        while (observedPhoneNotificationKeys.size() > MAX_OBSERVED_PHONE_NOTIFICATIONS) {
            java.util.Iterator<String> oldest = observedPhoneNotificationKeys.iterator();
            if (!oldest.hasNext()) break;
            oldest.next();
            oldest.remove();
        }
    }

    /**
     * Keeps the visible card stable. The first newcomer waits one second; every later delivery is
     * retained in arrival order and receives its own one-second slot.
     */
    private void enqueuePhoneNotification(
            @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation,
            @NonNull Set<String> selectedFields) {
        if (!phoneNotificationAllowedByLockState()) return;
        QueuedPhoneNotification delivery = new QueuedPhoneNotification(
                presentation, selectedFields);
        enqueuePhoneDelivery(delivery);
    }

    /** Low-battery events use exactly the same lock, overlay-delay and destination queue. */
    private boolean enqueuePhoneLowBatteryAlert(int level, @NonNull String color,
                                                int stage) {
        if (prefs == null || !phoneNotificationAllowedByLockState()
                || (!prefs.phoneStatusBarNotificationsEnabled.get()
                && !prefs.phonePopupNotificationsEnabled.get())) return false;
        return enqueuePhoneDelivery(QueuedPhoneNotification.lowBattery(level, color, stage));
    }

    private boolean enqueuePhoneDelivery(@NonNull QueuedPhoneNotification delivery) {
        if (phoneNotificationBlockedByForeground()) {
            long now = SystemClock.elapsedRealtime();
            if (!deferredPhoneNotifications.offer(delivery, now)) {
                if (deferredPhoneNotificationOverflowCount == 0) {
                    deferredPhoneNotificationOverflowStartedElapsed = now;
                }
                deferredPhoneNotificationOverflowCount = saturatingIncrement(
                        deferredPhoneNotificationOverflowCount);
                return false;
            }
            DiagnosticJournal.info("phone-notification",
                    "delivery deferred reason="
                            + (phoneExternalOverlayActive
                            ? "vehicle-overlay" : "foreground-app")
                            + " key=" + phoneNotificationDeliveryKey(delivery));
            schedulePhoneNotificationDeferralDeadline();
            return true;
        }
        // A missed/coalesced foreground callback must not let a newcomer overtake older held
        // notifications. Release those first, then append this delivery to the normal sequencer.
        if (!deferredPhoneNotifications.isEmpty()
                || deferredPhoneNotificationOverflowCount > 0) {
            releaseAllDeferredPhoneNotifications();
        }
        return enqueuePhoneNotificationNow(delivery);
    }

    /** Enters the existing one-second sequencer without re-applying foreground deferral. */
    private boolean enqueuePhoneNotificationNow(@NonNull QueuedPhoneNotification delivery) {
        long batteryRemaining = activePhoneLowBatteryRemaining();
        if (batteryRemaining > 0L) {
            boolean accepted = appendQueuedPhoneNotification(delivery);
            if (!accepted) return false;
            phoneNotificationBurstActive = true;
            schedulePhoneNotificationQueueAdvanceAfter(batteryRemaining);
            return true;
        }
        if (phoneNotificationBurstActive) {
            return appendQueuedPhoneNotification(delivery);
        }
        if (hasActiveRoutinePhoneNotificationDestination()) {
            boolean accepted = appendQueuedPhoneNotification(delivery);
            if (!accepted) return false;
            phoneNotificationBurstActive = true;
            long delay = PHONE_NOTIFICATION_QUEUE_SLOT_MS;
            holdPhoneNotificationDestinationsUntil(
                    SystemClock.elapsedRealtime() + delay);
            schedulePhoneNotificationQueueAdvanceAfter(delay);
            return true;
        }
        return presentPhoneNotification(delivery);
    }

    private boolean appendQueuedPhoneNotification(@NonNull QueuedPhoneNotification delivery) {
        if (queuedPhoneNotifications.size() >= PhoneNotificationDeferralQueue.MAX_ITEMS) {
            queuedPhoneNotificationOverflowCount = saturatingIncrement(
                    queuedPhoneNotificationOverflowCount);
            return false;
        }
        queuedPhoneNotifications.addLast(delivery);
        return true;
    }

    private void schedulePhoneNotificationQueueAdvanceAfter(long delayMillis) {
        mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
        if (phoneNotificationOverlayPaused) {
            pausedPhoneNotificationQueueAdvance = true;
            return;
        }
        mainHandler.postDelayed(phoneNotificationQueueAdvance,
                Math.max(0L, delayMillis));
    }

    private boolean phoneNotificationForegroundTrackingNeeded() {
        return prefs != null && prefs.phoneNotificationDelayInAppsEnabled.get()
                && !prefs.phoneNotificationDelayInPackages.get().isEmpty();
    }

    private boolean phoneNotificationBlockedByForeground() {
        if (prefs == null || !prefs.phoneNotificationDelayInAppsEnabled.get()) return false;
        if (prefs.phoneNotificationDelayForExternalOverlays.get()
                && phoneExternalOverlayActive) return true;
        if (prefs.phoneNotificationDelayInPackages.get().isEmpty()) return false;
        // Foreground identity can be briefly unknown while Accessibility reconnects, before the
        // first UsageStats sample, or after its permission is revoked. Showing immediately would
        // defeat the feature exactly for explicitly selected applications. Hold conservatively;
        // each delivery still has its configured monotonic maximum-wait deadline.
        if (lastForegroundPackage == null) return true;
        return PhoneNotificationDeferralPolicy.isBlocking(true,
                prefs.phoneNotificationDelayInPackages.get(), lastForegroundPackage);
    }

    /** Called by the shared event-driven foreground tracker only when its package really changes. */
    private void onPhoneNotificationForegroundChanged() {
        reconcileDeferredPhoneNotifications();
    }

    private boolean shouldPausePhoneNotificationForExternalOverlay() {
        return prefs != null
                && prefs.phoneNotificationDelayInAppsEnabled.get()
                && prefs.phoneNotificationDelayForExternalOverlays.get()
                && phoneExternalOverlayActive;
    }

    /** Keeps an already-rendered delivery alive while 360/PAS is confirmed active. */
    private void syncPhoneNotificationExternalOverlayPause() {
        boolean shouldPause = shouldPausePhoneNotificationForExternalOverlay();
        if (shouldPause == phoneNotificationOverlayPaused) return;
        if (shouldPause) pausePhoneNotificationForExternalOverlay();
        else resumePhoneNotificationAfterExternalOverlay();
    }

    private void pausePhoneNotificationForExternalOverlay() {
        phoneNotificationOverlayPaused = true;
        long now = SystemClock.elapsedRealtime();
        pausedPhoneNotificationRemainingMs = hasActivePhoneStatusAlert()
                && activePhoneNotificationExpiresAt > 0L
                ? Math.max(1L, activePhoneNotificationExpiresAt - now) : 0L;
        pausedPhonePopupRemainingMs = activePhonePopupNotificationExpiresAt > 0L
                ? Math.max(1L, activePhonePopupNotificationExpiresAt - now) : 0L;
        pausedPhoneNotificationQueueAdvance = phoneNotificationBurstActive;
        mainHandler.removeCallbacks(phoneNotificationExpiry);
        mainHandler.removeCallbacks(phonePopupNotificationExpiry);
        mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
        if (pausedPhoneNotificationRemainingMs > 0L) {
            activePhoneNotificationExpiresAt = Long.MAX_VALUE;
        }
        if (pausedPhonePopupRemainingMs > 0L) {
            activePhonePopupNotificationExpiresAt = Long.MAX_VALUE;
            updatePhonePopupAutomationExpiry(0L);
        }
        DiagnosticJournal.info("phone-notification",
                "external overlay pause statusRemainingMs="
                        + pausedPhoneNotificationRemainingMs
                        + " popupRemainingMs=" + pausedPhonePopupRemainingMs
                        + " queue=" + pausedPhoneNotificationQueueAdvance);
        dezz.status.widget.diagnostics.ActionRecorder.recordOverlay(
                "phone-notification-external", "PAUSED",
                "statusRemainingMs=" + pausedPhoneNotificationRemainingMs
                        + ", popupRemainingMs=" + pausedPhonePopupRemainingMs);
    }

    private void resumePhoneNotificationAfterExternalOverlay() {
        phoneNotificationOverlayPaused = false;
        long now = SystemClock.elapsedRealtime();
        long statusRemaining = pausedPhoneNotificationRemainingMs;
        long popupRemaining = pausedPhonePopupRemainingMs;
        boolean resumeQueue = pausedPhoneNotificationQueueAdvance;
        pausedPhoneNotificationRemainingMs = 0L;
        pausedPhonePopupRemainingMs = 0L;
        pausedPhoneNotificationQueueAdvance = false;
        boolean staleStatus = activePhoneNotification != null
                && !phoneNotificationStillCurrent(activePhoneNotification);
        boolean stalePopup = activePhonePopupNotification != null
                && !phoneNotificationStillCurrent(activePhonePopupNotification);
        if (staleStatus) {
            statusRemaining = 0L;
            clearPhoneStatusNotification(true);
        }
        if (stalePopup) {
            popupRemaining = 0L;
            clearPhonePopupNotification();
        }
        if (statusRemaining > 0L && hasActivePhoneStatusAlert()) {
            activePhoneNotificationExpiresAt = now + statusRemaining;
            mainHandler.postDelayed(phoneNotificationExpiry, statusRemaining);
        }
        if (popupRemaining > 0L && activePhonePopupNotificationExpiresAt > 0L) {
            activePhonePopupNotificationExpiresAt = now + popupRemaining;
            updatePhonePopupAutomationExpiry(
                    System.currentTimeMillis() + popupRemaining);
            mainHandler.postDelayed(phonePopupNotificationExpiry, popupRemaining);
        }
        if (resumeQueue && phoneNotificationBurstActive) {
            mainHandler.postDelayed(phoneNotificationQueueAdvance,
                    PHONE_NOTIFICATION_QUEUE_SLOT_MS);
        }
        DiagnosticJournal.info("phone-notification",
                "external overlay resume statusRemainingMs=" + statusRemaining
                        + " popupRemainingMs=" + popupRemaining
                        + " queue=" + resumeQueue
                        + " staleStatus=" + staleStatus
                        + " stalePopup=" + stalePopup);
        dezz.status.widget.diagnostics.ActionRecorder.recordOverlay(
                "phone-notification-external", "RESUMED",
                "statusRemainingMs=" + statusRemaining
                        + ", popupRemainingMs=" + popupRemaining);
        if (binding != null) {
            updateMediaInfo();
            applyBrickVisibility(currentBrickSet());
        }
        schedulePopupRefresh();
    }

    /** Updates only phone-owned transient states; content and visibility remain unchanged. */
    private void updatePhonePopupAutomationExpiry(long expiresAtWallMillis) {
        if (automationStates == null) return;
        long now = System.currentTimeMillis();
        List<String> ids = new ArrayList<>(PhoneNotificationAutomation.fieldAutomationIds());
        ids.add(PhoneNotificationAutomation.OVERLAY_ID);
        ids.add(PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
        for (String id : ids) {
            String scope = PhoneNotificationAutomation.isFieldAutomationId(id)
                    ? AutomationContract.SCOPE_POPUP : AutomationContract.SCOPE_OVERLAY;
            AutomationState state = automationStates.get(scope, id);
            if (!state.present || state.source == null
                    || !state.source.startsWith("phone")) continue;
            try {
                automationStates.apply(scope, id, new JSONObject()
                        .put("expires_at", expiresAtWallMillis)
                        .put("updated_at", now));
                onAutomationStateChanged(scope, id);
            } catch (JSONException | RuntimeException failure) {
                Log.w(TAG, "Could not pause phone popup expiry", failure);
            }
        }
    }

    /**
     * Releases every due item while blocked, or the whole ordered queue as soon as the blocker
     * leaves. The next callback always targets the oldest item's exact monotonic deadline.
     */
    private void reconcileDeferredPhoneNotifications() {
        mainHandler.removeCallbacks(phoneNotificationDeferralDeadline);
        if ((deferredPhoneNotifications.isEmpty()
                && deferredPhoneNotificationOverflowCount <= 0) || prefs == null) return;
        if (!phoneNotificationAllowedByLockState()
                || (!prefs.phoneStatusBarNotificationsEnabled.get()
                && !prefs.phonePopupNotificationsEnabled.get())) {
            cancelPhoneNotificationQueue();
            return;
        }
        // Camera/360 and PAS are safety-owned vehicle surfaces. Their hold is released only by
        // the authoritative vehicle close transition; the configurable foreground-app timeout
        // must never punch through them while they are still visible.
        if (shouldPausePhoneNotificationForExternalOverlay()) return;
        if (!phoneNotificationBlockedByForeground()) {
            releaseAllDeferredPhoneNotifications();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        int seconds = prefs.phoneNotificationDelayMaxWaitSeconds.get();
        List<QueuedPhoneNotification> due = deferredPhoneNotifications.drainDue(now, seconds);
        boolean overflowDue = deferredPhoneNotificationOverflowCount > 0
                && now >= PhoneNotificationDeferralPolicy.deadline(
                deferredPhoneNotificationOverflowStartedElapsed, seconds);
        for (QueuedPhoneNotification delivery : due) {
            if (!phoneNotificationStillCurrent(delivery)) {
                DiagnosticJournal.info("phone-notification",
                        "deferred delivery dropped stale key="
                                + phoneNotificationDeliveryKey(delivery));
                onPhoneNotificationDeliveryDropped(delivery);
                continue;
            }
            if (!enqueuePhoneNotificationNow(delivery)) {
                onPhoneNotificationDeliveryDropped(delivery);
            }
        }
        if (overflowDue) {
            releaseDeferredPhoneNotificationOverflow();
        }
        if (!phoneNotificationBlockedByForeground()) {
            releaseAllDeferredPhoneNotifications();
            return;
        }
        schedulePhoneNotificationDeferralDeadline();
    }

    private void releaseAllDeferredPhoneNotifications() {
        mainHandler.removeCallbacks(phoneNotificationDeferralDeadline);
        for (QueuedPhoneNotification delivery : deferredPhoneNotifications.drainAll()) {
            if (!phoneNotificationStillCurrent(delivery)) {
                DiagnosticJournal.info("phone-notification",
                        "deferred delivery dropped stale key="
                                + phoneNotificationDeliveryKey(delivery));
                onPhoneNotificationDeliveryDropped(delivery);
                continue;
            }
            DiagnosticJournal.info("phone-notification",
                    "deferred delivery released key="
                            + phoneNotificationDeliveryKey(delivery));
            if (!enqueuePhoneNotificationNow(delivery)) {
                onPhoneNotificationDeliveryDropped(delivery);
            }
        }
        releaseDeferredPhoneNotificationOverflow();
    }

    private void releaseDeferredPhoneNotificationOverflow() {
        if (deferredPhoneNotificationOverflowCount <= 0) return;
        int overflow = deferredPhoneNotificationOverflowCount;
        deferredPhoneNotificationOverflowCount = 0;
        deferredPhoneNotificationOverflowStartedElapsed = 0L;
        enqueuePhoneNotificationNow(phoneNotificationOverflowDelivery(overflow));
    }

    private void schedulePhoneNotificationDeferralDeadline() {
        mainHandler.removeCallbacks(phoneNotificationDeferralDeadline);
        if (prefs == null || !phoneNotificationBlockedByForeground()) return;
        if (shouldPausePhoneNotificationForExternalOverlay()) return;
        long deadline = deferredPhoneNotifications.nextDeadline(
                prefs.phoneNotificationDelayMaxWaitSeconds.get());
        if (deferredPhoneNotificationOverflowCount > 0) {
            long overflowDeadline = PhoneNotificationDeferralPolicy.deadline(
                    deferredPhoneNotificationOverflowStartedElapsed,
                    prefs.phoneNotificationDelayMaxWaitSeconds.get());
            deadline = deadline < 0L ? overflowDeadline : Math.min(deadline, overflowDeadline);
        }
        if (deadline < 0L) return;
        long remaining = Math.max(1L, deadline - SystemClock.elapsedRealtime());
        mainHandler.postDelayed(phoneNotificationDeferralDeadline, remaining);
    }

    private boolean presentPhoneNotification(@NonNull QueuedPhoneNotification delivery) {
        if (prefs == null || !phoneNotificationAllowedByLockState()) return false;
        if (delivery.lowBatteryLevel != null) {
            if (!isPhoneLowBatteryAlertPending(delivery.lowBatteryStage)) return false;
            int level = delivery.lowBatteryLevel;
            boolean presentedInStatusRow = prefs.phoneStatusBarNotificationsEnabled.get()
                    && showPhoneLowBatteryStatus(level, delivery.lowBatteryColor);
            boolean presentedInPopup = prefs.phonePopupNotificationsEnabled.get()
                    && showPhoneLowBatteryPopup(level);
            if (binding != null) {
                updateMediaInfo();
                applyBrickVisibility(currentBrickSet());
            }
            schedulePopupRefresh();
            boolean presented = presentedInStatusRow || presentedInPopup;
            if (presented) markPhoneLowBatteryAlertPresented(delivery.lowBatteryStage);
            return presented;
        }
        updatePhoneNotificationFieldStates(delivery.presentation, delivery.selectedFields);
        boolean presentedInStatusRow = prefs.phoneStatusBarNotificationsEnabled.get()
                && showPhoneStatusNotification(
                delivery.presentation, delivery.selectedFields);
        boolean presentedInPopup = prefs.phonePopupNotificationsEnabled.get()
                && showPhonePopupNotification(delivery.presentation);
        if (!presentedInStatusRow && !presentedInPopup) {
            clearPhoneNotificationFieldsIfInactive();
        }
        if (binding != null) {
            updateMediaInfo();
            applyBrickVisibility(currentBrickSet());
        }
        schedulePopupRefresh();
        boolean presented = presentedInStatusRow || presentedInPopup;
        if (presented) {
            DiagnosticJournal.info("phone-notification",
                    "delivery shown key=" + phoneNotificationDeliveryKey(delivery)
                            + " status=" + presentedInStatusRow
                            + " popup=" + presentedInPopup);
        }
        return presented;
    }

    private boolean phoneNotificationStillCurrent(
            @NonNull QueuedPhoneNotification delivery) {
        return delivery.presentation == null
                || phoneNotificationStillCurrent(delivery.presentation);
    }

    /** A fresh ANCS item list is authoritative; missing/stale telemetry must fail open. */
    private boolean phoneNotificationStillCurrent(
            @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation) {
        String key = presentation.key;
        if (TextUtils.isEmpty(key) || key.startsWith("overflow+")) return true;
        ConnectorValue items = phoneStatusValues.get("notifications.items");
        if (items == null || !items.fresh || !items.available || !items.readable
                || !(items.rawValue instanceof List<?>)) return true;
        // The connector may publish latest before its accompanying list snapshot. An older list
        // cannot prove the new delivery stale and therefore deliberately fails open.
        if (items.updatedAt < presentation.receivedAt) return true;
        for (Object item : (List<?>) items.rawValue) {
            if (key.equals(PhoneStatusBarPolicy.notificationKey(item))) return true;
        }
        return false;
    }

    @NonNull
    private static String phoneNotificationDeliveryKey(
            @NonNull QueuedPhoneNotification delivery) {
        if (delivery.presentation != null) return delivery.presentation.key;
        return "low-battery-" + delivery.lowBatteryStage;
    }

    private boolean isPhoneLowBatteryAlertPending(int stage) {
        if (stage == 1) return phoneLowBatteryAlertPending;
        if (stage == 2) return phoneLowBatteryAlertPending2;
        return false;
    }

    private void markPhoneLowBatteryAlertPresented(int stage) {
        if (prefs == null) return;
        if (stage == 1) {
            phoneLowBatteryAlertPending = false;
            if (!phoneLowBatteryAlertLatched) {
                phoneLowBatteryAlertLatched = true;
                prefs.phoneLowBatteryAlertLatched.set(true);
            }
        } else if (stage == 2) {
            phoneLowBatteryAlertPending2 = false;
            if (!phoneLowBatteryAlertLatched2) {
                phoneLowBatteryAlertLatched2 = true;
                prefs.phoneLowBatteryAlertLatched2.set(true);
            }
        }
    }

    private void onPhoneNotificationDeliveryDropped(
            @NonNull QueuedPhoneNotification delivery) {
        if (delivery.lowBatteryStage == 1) phoneLowBatteryAlertPending = false;
        if (delivery.lowBatteryStage == 2) phoneLowBatteryAlertPending2 = false;
    }

    private boolean hasActiveRoutinePhoneNotificationDestination() {
        long now = SystemClock.elapsedRealtime();
        boolean statusActive = activePhoneNotification != null
                && activePhoneNotificationExpiresAt > now;
        boolean popupActive = activePhonePopupNotificationExpiresAt > now;
        return statusActive || popupActive;
    }

    /** Normal configured expiry is suspended while the one-second burst clock owns the card. */
    private void holdPhoneNotificationDestinationsUntil(long elapsedDeadline) {
        long guardedDeadline = elapsedDeadline + 100L;
        if (activePhoneNotification != null) {
            activePhoneNotificationExpiresAt = Math.max(
                    activePhoneNotificationExpiresAt, guardedDeadline);
            mainHandler.removeCallbacks(phoneNotificationExpiry);
        }
        if (activePhonePopupNotificationExpiresAt > 0L) {
            activePhonePopupNotificationExpiresAt = Math.max(
                    activePhonePopupNotificationExpiresAt, guardedDeadline);
            mainHandler.removeCallbacks(phonePopupNotificationExpiry);
        }
    }

    private void finishPhoneNotificationBurst() {
        mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
        queuedPhoneNotifications.clear();
        queuedPhoneNotificationOverflowCount = 0;
        phoneNotificationBurstActive = false;
        if (activePhoneNotification != null) clearPhoneStatusNotification(true);
        if (activePhonePopupNotificationExpiresAt > 0L) clearPhonePopupNotification();
        if (binding != null) {
            updateMediaInfo();
            applyBrickVisibility(currentBrickSet());
        }
        schedulePopupRefresh();
    }

    /** The last item keeps the normal configured timer set by showPhone*Notification(). */
    private void releasePhoneNotificationBurstToConfiguredExpiry() {
        mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
        queuedPhoneNotifications.clear();
        queuedPhoneNotificationOverflowCount = 0;
        phoneNotificationBurstActive = false;
    }

    private boolean phoneNotificationAllowedByLockState() {
        return prefs != null && PhoneNotificationLockPolicy.mayPresent(
                prefs.phoneNotificationsOnlyWhenLocked.get(),
                phoneBoolean("device.locked"));
    }

    private void suppressPhoneNotificationsUnlessLockAllows() {
        if (prefs == null || phoneNotificationAllowedByLockState()) return;
        cancelPhoneNotificationQueue();
        if (hasActivePhoneStatusAlert()) clearPhoneStatusNotification(true);
        if (activePhonePopupNotificationExpiresAt > 0L) clearPhonePopupNotification();
    }

    private void cancelPhoneNotificationQueue() {
        mainHandler.removeCallbacks(phoneNotificationQueueAdvance);
        mainHandler.removeCallbacks(phoneNotificationDeferralDeadline);
        queuedPhoneNotifications.clear();
        deferredPhoneNotifications.clear();
        queuedPhoneNotificationOverflowCount = 0;
        deferredPhoneNotificationOverflowCount = 0;
        deferredPhoneNotificationOverflowStartedElapsed = 0L;
        phoneNotificationBurstActive = false;
        pausedPhoneNotificationQueueAdvance = false;
        phoneLowBatteryAlertPending = false;
        phoneLowBatteryAlertPending2 = false;
    }

    @NonNull
    private static QueuedPhoneNotification phoneNotificationOverflowDelivery(int count) {
        return new QueuedPhoneNotification(
                PhoneStatusBarPolicy.overflowSummary(count, System.currentTimeMillis()),
                new LinkedHashSet<>(PhoneStatusBarPolicy.notificationFieldIds()));
    }

    private static int saturatingIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private long activePhoneLowBatteryRemaining() {
        if (activePhoneBatteryAlertText == null) return 0L;
        return Math.max(0L, activePhoneNotificationExpiresAt
                - SystemClock.elapsedRealtime());
    }

    private static final class QueuedPhoneNotification {
        @Nullable final PhoneStatusBarPolicy.NotificationPresentation presentation;
        @NonNull final Set<String> selectedFields;
        @Nullable final Integer lowBatteryLevel;
        @NonNull final String lowBatteryColor;
        final int lowBatteryStage;

        QueuedPhoneNotification(
                @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation,
                @NonNull Set<String> selectedFields) {
            this.presentation = presentation;
            this.selectedFields = Collections.unmodifiableSet(
                    new LinkedHashSet<>(selectedFields));
            this.lowBatteryLevel = null;
            this.lowBatteryColor = "";
            this.lowBatteryStage = 0;
        }

        private QueuedPhoneNotification(int level, @NonNull String color, int stage) {
            this.presentation = null;
            this.selectedFields = Collections.emptySet();
            this.lowBatteryLevel = Math.max(0, Math.min(100, level));
            this.lowBatteryColor = color;
            this.lowBatteryStage = stage;
        }

        static QueuedPhoneNotification lowBattery(int level, @NonNull String color, int stage) {
            return new QueuedPhoneNotification(level, color, stage);
        }
    }

    /** Reconciles the selectable scalar iPhone values without owning another BLE connection. */
    private void renderPhoneStatusBricks() {
        renderPhoneStatusBricks(false);
    }

    private void renderPhoneStatusBricks(boolean forceStyle) {
        if (binding == null || prefs == null) return;
        LinearLayout container = binding.phoneStatusContainer;
        Set<String> selected = PhoneStatusBarPolicy.parseIds(
                prefs.phoneStatusBarItems.get(), PhoneStatusBarPolicy.statusIds());

        Map<String, OutlineTextView> existing = new LinkedHashMap<>();
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            Object tag = child.getTag();
            if (child instanceof OutlineTextView && tag instanceof String) {
                existing.put((String) tag, (OutlineTextView) child);
            }
        }

        List<OutlineTextView> desired = new ArrayList<>();
        for (PhoneStatusBarPolicy.StatusItem item : PhoneStatusBarPolicy.statusItems()) {
            if (!selected.contains(item.id)) continue;
            String value = PhoneStatusBarPolicy.display(
                    item, phoneStatusValues.get(item.resourceId));
            if (TextUtils.isEmpty(value)) continue;

            OutlineTextView view = existing.remove(item.id);
            boolean created = view == null;
            if (created) {
                view = new OutlineTextView(themedContext != null ? themedContext : this);
                view.setTag(item.id);
                view.setSingleLine(true);
                view.setIncludeFontPadding(false);
                view.setContentDescription(item.label);
                LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                layout.gravity = Gravity.CENTER_VERTICAL;
                view.setLayoutParams(layout);
            }
            if (created || forceStyle) applyPhoneStatusTextStyle(view);
            String rendered = desired.isEmpty() ? value : " · " + value;
            setTextIfChanged(view, rendered);
            desired.add(view);
        }

        for (OutlineTextView obsolete : existing.values()) {
            container.removeView(obsolete);
        }
        for (int index = 0; index < desired.size(); index++) {
            OutlineTextView view = desired.get(index);
            if (index < container.getChildCount() && container.getChildAt(index) == view) continue;
            ViewGroup.LayoutParams layout = view.getLayoutParams();
            if (view.getParent() == container) container.removeView(view);
            container.addView(view, index, layout);
        }
        if (forceStyle) {
            applyHorizontalMargins(container, prefs.phoneStatus.marginStart.get(),
                    prefs.phoneStatus.marginEnd.get());
            container.setTranslationY(prefs.phoneStatus.adjustY.get());
            container.setAlpha(prefs.phoneStatus.contentAlpha.get() / 255f);
        }
    }

    private void updatePhoneIndicators() {
        if (binding == null || prefs == null) return;

        Integer signal = phonePercent("network.signal");
        OutlineImageView cellular = binding.phoneCellularStatusIcon;
        cellular.setImageResource(R.drawable.ic_status_iphone_cellular_level);
        cellular.setImageLevel(cellularBars(signal) * 2500);
        cellular.setDrawIcon(true);
        ImageViewCompat.setImageTintList(cellular, null);
        applyConfiguredIconOutline(cellular, prefs.phoneCellular);
        cellular.setBadgeText(null, 0, 0);
        cellular.setBadgeDrawable(null);
        String operator = phoneText("network.operator");
        binding.phoneCellularOperatorText.setText(operator);
        binding.phoneCellularOperatorText.setVisibility(
                operator.isEmpty() ? View.GONE : View.VISIBLE);
        String networkType = phoneNetworkType();
        binding.phoneCellularNetworkTypeText.setText(networkType);
        binding.phoneCellularNetworkTypeText.setVisibility(
                prefs.phoneCellular.showNetworkType.get() && !networkType.isEmpty()
                        ? View.VISIBLE : View.GONE);
        applyPhoneCellularInternalSpacing();
        binding.phoneNetworkTypeText.setText(networkType);

        Integer battery = phonePercent("battery.level");
        OutlineImageView batteryIcon = binding.phoneBatteryStatusIcon;
        batteryIcon.setImageResource(R.drawable.ic_status_iphone_battery);
        batteryIcon.setImageLevel(battery == null ? 0 : battery * 100);
        batteryIcon.setDrawIcon(true);
        boolean charging = phoneChargingNow();
        int batteryColor = phoneBatteryColor(battery, charging);
        ImageViewCompat.setImageTintList(batteryIcon, ColorStateList.valueOf(batteryColor));
        batteryIcon.setBatteryPercent(
                prefs.phoneBattery.showPercentage.get() ? battery : null, batteryColor);
        batteryIcon.setBatteryCharging(charging);
        applyConfiguredIconOutline(batteryIcon, prefs.phoneBattery);
        batteryIcon.setBadgeText(null, 0, 0);
        batteryIcon.setBadgeDrawable(null);
    }

    @Nullable
    private Integer phonePercent(@NonNull String resourceId) {
        return PhoneStatusBarPolicy.percentValue(resourceId, currentPhoneValue(resourceId));
    }

    @Nullable
    private Boolean phoneBoolean(@NonNull String resourceId) {
        return PhoneStatusBarPolicy.booleanValue(resourceId, currentPhoneValue(resourceId));
    }

    @NonNull
    private String phoneText(@NonNull String resourceId) {
        String value = PhoneStatusBarPolicy.textValue(
                resourceId, currentPhoneValue(resourceId));
        return value == null ? "" : value;
    }

    /**
     * Reads the authoritative connector snapshot instead of relying on the UI projection map.
     *
     * <p>The map remains the event/notification bookkeeping owner, but its callback is posted to
     * the main thread. A HOME surface can render between registry publication and that posted
     * projection. Direct registry reads close that race and also recover when a visual surface is
     * created after the first Helper telemetry burst.</p>
     */
    @Nullable
    private ConnectorValue currentPhoneValue(@NonNull String resourceId) {
        ConnectorValueRegistry current = connectorValues;
        if (current != null) {
            ConnectorValue value = current.get(ConnectorType.PHONE,
                    SourceBinding.DEFAULT_CONNECTOR_ID, resourceId);
            if (value != null) return value;
        }
        return phoneStatusValues.get(resourceId);
    }

    private int phoneBatteryColor(@Nullable Integer battery, boolean charging) {
        Context context = themedContext != null ? themedContext : this;
        return battery != null && battery < 20
                ? ContextCompat.getColor(context, R.color.iphone_battery_critical)
                : charging
                ? ContextCompat.getColor(context, R.color.iphone_battery_charging)
                : ContextCompat.getColor(context, android.R.color.white);
    }

    private void applyPhoneCellularInternalSpacing() {
        if (binding == null || prefs == null) return;
        int iconSize = Math.max(1, prefs.phoneCellular.size.get());
        boolean typeVisible = binding.phoneCellularNetworkTypeText.getVisibility()
                == View.VISIBLE;
        boolean operatorVisible = binding.phoneCellularOperatorText.getVisibility()
                == View.VISIBLE;
        ViewGroup.MarginLayoutParams typeParams =
                (ViewGroup.MarginLayoutParams) binding.phoneCellularNetworkTypeText
                        .getLayoutParams();
        typeParams.setMarginStart(typeVisible
                ? PhoneIndicatorVisualPolicy.cellularIconTextGapPx(iconSize) : 0);
        binding.phoneCellularNetworkTypeText.setLayoutParams(typeParams);
        ViewGroup.MarginLayoutParams operatorParams =
                (ViewGroup.MarginLayoutParams) binding.phoneCellularOperatorText
                        .getLayoutParams();
        operatorParams.setMarginStart(!operatorVisible ? 0
                : typeVisible ? PhoneIndicatorVisualPolicy.cellularTextGapPx(iconSize)
                : PhoneIndicatorVisualPolicy.cellularIconTextGapPx(iconSize));
        binding.phoneCellularOperatorText.setLayoutParams(operatorParams);
    }

    private boolean phoneChargingNow() {
        return Boolean.TRUE.equals(phoneBoolean("battery.charging"))
                || Boolean.TRUE.equals(phoneBoolean("battery.external_power"));
    }

    @NonNull
    private String phoneNetworkType() {
        return PhoneNetworkTypePolicy.display(phoneText("network.type"));
    }

    private static int cellularBars(@Nullable Integer percent) {
        if (percent == null || percent <= 0) return 0;
        if (percent <= 25) return 1;
        if (percent <= 50) return 2;
        if (percent <= 75) return 3;
        return 4;
    }

    private void applyPhoneStatusTextStyle(@NonNull OutlineTextView view) {
        view.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        view.setOutlineColor(textOutlineColor(prefs.phoneStatus.outlineAlpha.get()));
        view.setOutlineWidth(prefs.phoneStatus.outlineWidth.get());
        view.setTypeface(Fonts.resolve(this, prefs.phoneStatus.fontFamily.get(),
                prefs.phoneStatus.fontBold.get(), prefs.phoneStatus.fontItalic.get()));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.phoneStatus.fontSize.get());
    }

    private boolean hasVisiblePhoneStatusValues() {
        if (binding == null) return false;
        for (int index = 0; index < binding.phoneStatusContainer.getChildCount(); index++) {
            View child = binding.phoneStatusContainer.getChildAt(index);
            if (child.getVisibility() == View.VISIBLE
                    && child instanceof android.widget.TextView
                    && !TextUtils.isEmpty(((android.widget.TextView) child).getText())) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private String joinedVisibleText(@NonNull LinearLayout container) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child.getVisibility() != View.VISIBLE
                    || !(child instanceof android.widget.TextView)) continue;
            CharSequence text = ((android.widget.TextView) child).getText();
            if (TextUtils.isEmpty(text)) continue;
            result.append(text);
        }
        return result.toString();
    }

    @NonNull
    private OutlineTextView firstPhoneStatusTextView() {
        if (binding != null) {
            for (int index = 0; index < binding.phoneStatusContainer.getChildCount(); index++) {
                View child = binding.phoneStatusContainer.getChildAt(index);
                if (child instanceof OutlineTextView) return (OutlineTextView) child;
            }
        }
        return binding.timeText;
    }

    private void applyDateBrickSettings() {
        applySingleLineTextBrick(binding.dateText, prefs.date);
        switch (prefs.date.alignment.get()) {
            case 1:
                binding.dateText.setGravity(Gravity.CENTER_HORIZONTAL);
                break;
            case 2:
                binding.dateText.setGravity(Gravity.END);
                break;
            default:
                binding.dateText.setGravity(Gravity.START);
                break;
        }
    }

    private void applyMediaBrickSettings() {
        int textColor = ContextCompat.getColor(themedContext, R.color.text_primary);

        // Source line: independent font, opacity, outline.
        Typeface sourceTypeface = Fonts.resolve(this, prefs.media.sourceFontFamily.get(),
                prefs.media.sourceFontBold.get(), prefs.media.sourceFontItalic.get());
        binding.mediaAppText.setOutlineColor(textOutlineColor(prefs.media.sourceOutlineAlpha.get()));
        binding.mediaAppText.setOutlineWidth(prefs.media.sourceOutlineWidth.get());
        binding.mediaAppText.setTextColor(textColor);
        binding.mediaAppText.setTypeface(sourceTypeface);
        binding.mediaAppText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.sourceFontSize.get());
        binding.mediaAppText.setAlpha(prefs.media.sourceContentAlpha.get() / 255f);

        // Title line: existing media.* font + opacity + outline (TextBrickPrefs inherited).
        Typeface titleTypeface = Fonts.resolve(this, prefs.media.fontFamily.get(),
                prefs.media.fontBold.get(), prefs.media.fontItalic.get());
        binding.mediaTitleText.setOutlineColor(textOutlineColor(prefs.media.outlineAlpha.get()));
        binding.mediaTitleText.setOutlineWidth(prefs.media.outlineWidth.get());
        binding.mediaTitleText.setTextColor(textColor);
        binding.mediaTitleText.setTypeface(titleTypeface);
        binding.mediaTitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.fontSize.get());
        binding.mediaTitleText.setAlpha(prefs.media.contentAlpha.get() / 255f);

        // Source line is always static + ellipsized; only the title scrolls. Source is short
        // and a constant moving marquee on it would be more distracting than helpful.
        binding.mediaAppText.setMarqueeEnabled(false);
        binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get());

        applyMediaStateIcon(textColor);
        applyMediaLineStructure();

        // Duration text — independent font size / alpha / outline so the user can dial it down
        // (typically the duration is rendered smaller and dimmer than the track subtitle).
        binding.mediaDurationText.setTypeface(titleTypeface);
        binding.mediaDurationText.setTextSize(TypedValue.COMPLEX_UNIT_PX, prefs.media.durationFontSize.get());
        binding.mediaDurationText.setTextColor(textColor);
        binding.mediaDurationText.setOutlineColor(textOutlineColor(prefs.media.durationOutlineAlpha.get()));
        binding.mediaDurationText.setOutlineWidth(prefs.media.durationOutlineWidth.get());
        binding.mediaDurationText.setAlpha(prefs.media.durationContentAlpha.get() / 255f);
        // An empty TextView still contributes its font line-box to the title row even though it
        // draws no characters. Before the first MediaSession callback that made the status row
        // measure against the (often larger) duration font, then shrink as soon as
        // updateMediaInfo() finally honoured "show duration = off". Keep an empty field gone;
        // an active track with a known duration is left alone and updateMediaInfo() remains the
        // sole place that promotes the field back to VISIBLE.
        if (!prefs.media.showDuration.get()
                || TextUtils.isEmpty(binding.mediaDurationText.getText())) {
            binding.mediaDurationText.setVisibility(View.GONE);
        }
        if (!prefs.media.progressBarEnabled.get() || lastMediaSubtitle == null) {
            binding.mediaProgressBar.setVisibility(View.GONE);
        }

        applyHorizontalMargins(binding.mediaContainer, prefs.media.marginStart.get(), prefs.media.marginEnd.get());
        binding.mediaContainer.setTranslationY(prefs.media.adjustY.get());
        // Container alpha back to full — per-line alpha is set above so the two values don't
        // multiply through the parent.
        binding.mediaContainer.setAlpha(1f);
        applyMediaMaxWidth(binding.mediaAppText);
        applyMediaMaxWidth(binding.mediaTitleText);
        // Alignment applies to the two ROWS — they, not the text views, are the children of the
        // vertical container, and layout_gravity on a child of a horizontal LinearLayout only
        // ever moves it vertically.
        applyMediaChildAlignment(binding.mediaSourceRow, prefs.media.sourceAlignment.get());
        applyMediaChildAlignment(binding.mediaTitleRow, prefs.media.alignment.get());
        applyMediaChildAlignment(binding.mediaProgressBar, prefs.media.alignment.get());
    }

    /**
     * Applies the configured one-line/two-line structure before any MediaSession exists.
     *
     * <p>The XML source row is visible by default. Previously it was hidden only from
     * {@link #updateMediaInfo()} after the first controller callback. With "show source" disabled,
     * a cold-start widget therefore measured one empty source line too many until playback began,
     * which made the whole status row temporarily taller. Keep this layout decision independent
     * of media availability and remove the inter-line gap when there is only one line.</p>
     */
    private void applyMediaLineStructure() {
        boolean showSource = prefs.media.showSource.get();
        int sourceVisibility = showSource ? View.VISIBLE : View.GONE;
        if (binding.mediaSourceRow.getVisibility() != sourceVisibility) {
            binding.mediaSourceRow.setVisibility(sourceVisibility);
        }

        LinearLayout.LayoutParams titleLp =
                (LinearLayout.LayoutParams) binding.mediaTitleRow.getLayoutParams();
        int topMargin = showSource ? prefs.media.lineGap.get() : 0;
        if (titleLp.topMargin != topMargin) {
            titleLp.topMargin = topMargin;
            binding.mediaTitleRow.setLayoutParams(titleLp);
        }
    }

    /**
     * Playback-state indicator. It lives at the head of the source row — "▶ Spotify" reads as one
     * statement — but the source line is optional, so when it's off the enabled icon is
     * re-parented to the head of the title row instead of vanishing with its host. The icon has an
     * independent visibility preference; when shown it takes the size, outline and opacity of the
     * line it sits on and flips colour with the widget theme like the text around it.
     */
    private void applyMediaStateIcon(int textColor) {
        boolean onSourceRow = prefs.media.showSource.get();
        LinearLayout host = onSourceRow ? binding.mediaSourceRow : binding.mediaTitleRow;
        ViewGroup parent = (ViewGroup) binding.mediaStateIcon.getParent();
        if (parent != host) {
            if (parent != null) parent.removeView(binding.mediaStateIcon);
            host.addView(binding.mediaStateIcon, 0);
        }
        binding.mediaStateIcon.setVisibility(
                prefs.media.showPlaybackStateIcon.get() ? View.VISIBLE : View.GONE);

        int fontSize = onSourceRow ? prefs.media.sourceFontSize.get() : prefs.media.fontSize.get();
        int outlineAlpha = onSourceRow
                ? prefs.media.sourceOutlineAlpha.get() : prefs.media.outlineAlpha.get();
        int outlineWidth = onSourceRow
                ? prefs.media.sourceOutlineWidth.get() : prefs.media.outlineWidth.get();
        int contentAlpha = onSourceRow
                ? prefs.media.sourceContentAlpha.get() : prefs.media.contentAlpha.get();
        binding.mediaStateIcon.setTextSizePx(fontSize);
        binding.mediaStateIcon.setIconColor(textColor);
        binding.mediaStateIcon.setOutlineColor(textOutlineColor(outlineAlpha));
        binding.mediaStateIcon.setOutlineWidth(outlineWidth);
        binding.mediaStateIcon.setAlpha(contentAlpha / 255f);

        // Gap to the text scales with that text too — a fixed one would glue the icon to a 60px
        // source line and strand it next to a 12px one.
        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) binding.mediaStateIcon.getLayoutParams();
        int gap = Math.round(fontSize * STATE_ICON_GAP_RATIO);
        if (lp.getMarginEnd() != gap) {
            lp.setMarginEnd(gap);
            binding.mediaStateIcon.setLayoutParams(lp);
        }
    }

    /**
     * Horizontal alignment of a single line within the vertical media container.
     * Container is wrap_content (sized to the wider of the two children), so the narrower
     * child shifts within that band via its own {@code layout_gravity}.
     */
    private static void applyMediaChildAlignment(View view, int alignment) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        int gravity;
        switch (alignment) {
            case 1: gravity = Gravity.CENTER_HORIZONTAL; break;
            case 2: gravity = Gravity.END; break;
            default: gravity = Gravity.START; break;
        }
        lp.gravity = gravity;
        view.setLayoutParams(lp);
    }

    private void applyMediaMaxWidth(MarqueeOutlineTextView view) {
        // The view itself toggles between WRAP_CONTENT (text fits) and a fixed maxWidth
        // (overflow + scrolling). All we need here is to tell it the upper bound.
        view.setMaxWidth(prefs.media.maxWidth.get());
    }

    private void applyWifiBrickSettings() {
        ViewGroup.LayoutParams ip = binding.wifiStatusIcon.getLayoutParams();
        ip.width = prefs.wifi.size.get();
        ip.height = prefs.wifi.size.get();
        binding.wifiStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.wifiStatusIcon, prefs.wifi.marginStart.get(), prefs.wifi.marginEnd.get());
        binding.wifiStatusIcon.setTranslationY(prefs.wifi.adjustY.get());
        binding.wifiStatusIcon.setAlpha(prefs.wifi.contentAlpha.get() / 255f);
    }

    private void applyGpsBrickSettings() {
        ViewGroup.LayoutParams ip = binding.gnssStatusIcon.getLayoutParams();
        ip.width = prefs.gps.size.get();
        ip.height = prefs.gps.size.get();
        binding.gnssStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.gnssStatusIcon, prefs.gps.marginStart.get(), prefs.gps.marginEnd.get());
        binding.gnssStatusIcon.setTranslationY(prefs.gps.adjustY.get());
        binding.gnssStatusIcon.setAlpha(prefs.gps.contentAlpha.get() / 255f);
    }

    private void applyBluetoothBrickSettings() {
        ViewGroup.LayoutParams ip = binding.bluetoothStatusIcon.getLayoutParams();
        ip.width = prefs.bluetooth.size.get();
        ip.height = prefs.bluetooth.size.get();
        binding.bluetoothStatusIcon.setLayoutParams(ip);
        applyHorizontalMargins(binding.bluetoothStatusIcon,
                prefs.bluetooth.marginStart.get(), prefs.bluetooth.marginEnd.get());
        binding.bluetoothStatusIcon.setTranslationY(prefs.bluetooth.adjustY.get());
        binding.bluetoothStatusIcon.setAlpha(prefs.bluetooth.contentAlpha.get() / 255f);
    }

    private void applyPhoneCellularBrickSettings() {
        ViewGroup.LayoutParams layout = binding.phoneCellularStatusIcon.getLayoutParams();
        layout.width = Math.round(prefs.phoneCellular.size.get() * 1.17f);
        layout.height = prefs.phoneCellular.size.get();
        binding.phoneCellularStatusIcon.setLayoutParams(layout);
        binding.phoneCellularOperatorText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.max(12, Math.round(prefs.phoneCellular.size.get() * .42f)));
        binding.phoneCellularOperatorText.setTextColor(
                ContextCompat.getColor(themedContext, R.color.text_primary));
        binding.phoneCellularOperatorText.setOutlineColor(
                textOutlineColor(prefs.phoneCellular.outlineAlpha.get()));
        binding.phoneCellularOperatorText.setOutlineWidth(
                prefs.phoneCellular.outlineWidth.get());
        binding.phoneCellularNetworkTypeText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                Math.max(12, Math.round(prefs.phoneCellular.size.get() * .42f)));
        binding.phoneCellularNetworkTypeText.setTextColor(
                ContextCompat.getColor(themedContext, R.color.text_primary));
        binding.phoneCellularNetworkTypeText.setOutlineColor(
                textOutlineColor(prefs.phoneCellular.outlineAlpha.get()));
        binding.phoneCellularNetworkTypeText.setOutlineWidth(
                prefs.phoneCellular.outlineWidth.get());
        int textEdgeReserve = PhoneIndicatorVisualPolicy.cellularTextEdgeReservePx(
                prefs.phoneCellular.size.get(), prefs.phoneCellular.outlineWidth.get());
        binding.phoneCellularNetworkTypeText.setPadding(
                textEdgeReserve, 0, textEdgeReserve, 0);
        binding.phoneCellularOperatorText.setPadding(
                textEdgeReserve, 0, textEdgeReserve, 0);
        applyPhoneCellularInternalSpacing();
        applyHorizontalMargins(binding.phoneCellularContainer,
                prefs.phoneCellular.marginStart.get(), prefs.phoneCellular.marginEnd.get());
        binding.phoneCellularContainer.setTranslationY(prefs.phoneCellular.adjustY.get());
        binding.phoneCellularContainer.setAlpha(prefs.phoneCellular.contentAlpha.get() / 255f);
    }

    private void applyPhoneBatteryBrickSettings() {
        ViewGroup.LayoutParams layout = binding.phoneBatteryStatusIcon.getLayoutParams();
        layout.width = Math.round(prefs.phoneBattery.size.get() * 1.6f);
        layout.height = prefs.phoneBattery.size.get();
        binding.phoneBatteryStatusIcon.setLayoutParams(layout);
        applyHorizontalMargins(binding.phoneBatteryStatusIcon,
                prefs.phoneBattery.marginStart.get(), prefs.phoneBattery.marginEnd.get());
        binding.phoneBatteryStatusIcon.setTranslationY(prefs.phoneBattery.adjustY.get());
        binding.phoneBatteryStatusIcon.setAlpha(prefs.phoneBattery.contentAlpha.get() / 255f);
    }

    private void applyPhoneNetworkTypeBrickSettings() {
        applySingleLineTextBrick(binding.phoneNetworkTypeText, prefs.phoneNetworkType);
    }

    private void applySingleLineTextBrick(OutlineTextView view, Preferences.TextBrickPrefs p) {
        view.setTextColor(ContextCompat.getColor(themedContext, R.color.text_primary));
        view.setOutlineColor(textOutlineColor(p.outlineAlpha.get()));
        view.setOutlineWidth(p.outlineWidth.get());
        view.setTypeface(Fonts.resolve(this, p.fontFamily.get(), p.fontBold.get(), p.fontItalic.get()));
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, p.fontSize.get());
        view.setTranslationY(p.adjustY.get());
        view.setAlpha(p.contentAlpha.get() / 255f);
        applyHorizontalMargins(view, p.marginStart.get(), p.marginEnd.get());
    }

    private int textOutlineColor(int alpha) {
        return (ContextCompat.getColor(themedContext, R.color.text_outline) & 0x00FFFFFF) | (alpha << 24);
    }

    /**
     * Rebuilds {@link #themedContext} so theme-dependent colour lookups respect the user's
     * "Widget theme" preference. Pref values: 0 = follow system, 1 = always light, 2 = always
     * dark, 3 = inverse of system. Cached so we don't allocate a new Context on every
     * {@code applyPreferences()}; {@code onConfigurationChanged} invalidates the cache so the
     * inverse mode picks up system theme changes too.
     */
    private void updateThemedContext() {
        int pref = prefs.widgetTheme.get();
        if (themedContext != null && pref == appliedThemePref) return;
        if (pref == 0) {
            themedContext = this;
        } else {
            int uiMode;
            if (pref == 1) {
                uiMode = Configuration.UI_MODE_NIGHT_NO;
            } else if (pref == 2) {
                uiMode = Configuration.UI_MODE_NIGHT_YES;
            } else {
                int systemNight = getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                uiMode = (systemNight == Configuration.UI_MODE_NIGHT_YES)
                        ? Configuration.UI_MODE_NIGHT_NO
                        : Configuration.UI_MODE_NIGHT_YES;
            }
            Configuration cfg = new Configuration(getResources().getConfiguration());
            cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | uiMode;
            themedContext = createConfigurationContext(cfg);
        }
        appliedThemePref = pref;
    }

    private static void applyHorizontalMargins(View view, int start, int end) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) view.getLayoutParams();
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        view.setLayoutParams(lp);
    }

    private final EnumMap<BrickType, Set<String>> effectiveHideLists = new EnumMap<>(BrickType.class);

    private void rebuildEffectiveHideLists() {
        effectiveHideLists.clear();
        for (BrickType type : BrickType.values()) {
            BrickType source = prefs.effectiveHideSourceFor(type);
            effectiveHideLists.put(type, prefs.hideListFor(source).get());
        }
    }

    private boolean isBrickHiddenByApp(BrickType type) {
        Set<String> list = effectiveHideLists.get(type);
        return matchesForegroundContext(list);
    }

    private boolean isLauncherHomeTopSurface() {
        // This process-local lifecycle token comes from LauncherActivity itself. On the target
        // Android 9 head unit only one Activity is resumed at a time, so waiting for a second
        // accessibility/UsageStats package sample merely leaves the freshly resumed HOME visible
        // with stale rules for one foreground-tracker pass.
        return StatusBarSurfaceContext.isLauncherHomeForeground();
    }

    private boolean matchesForegroundContext(@Nullable Set<String> targets) {
        boolean navigatorWindow = effectiveNavigatorWindowForeground();
        return StatusBarSurfaceContext.matches(
                targets, lastForegroundPackage,
                navigatorWindow ? false : isLauncherHomeTopSurface(), navigatorWindow);
    }

    private boolean anyBrickNeedsPackageTracking() {
        for (Set<String> s : effectiveHideLists.values()) {
            if (StatusBarSurfaceContext.requiresPackageTracking(s)) return true;
        }
        for (HaBrickConfig config : configuredMainBricks) {
            if (StatusBarSurfaceContext.requiresPackageTracking(config.hideInPackages)) {
                return true;
            }
        }
        return false;
    }

    private void applyBrickVisibility(Set<BrickType> bricksSet) {
        if (binding == null) return;
        boolean dateActive = bricksSet.contains(BrickType.DATE)
                && (prefs.date.showDate.get() || prefs.date.showDayOfWeek.get());
        // Car bricks only render when the vehicle supports the sensor — a preset imported from
        // another car may list them in brickOrder, and an unsupported sensor would otherwise
        // leave a permanently frozen placeholder brick in the row.
        // Before the delayed ECARX stage, reserve configured temperature slots using placeholders.
        // The later capability callback collapses unsupported sensors without blocking first draw.
        CarIntegration car = carTelemetryExporter == null ? null : CarIntegrations.get(this);
        boolean indoorTempActive = bricksSet.contains(BrickType.INDOOR_TEMP)
                && (car == null || car.isBrickSupported(BrickType.INDOOR_TEMP));
        boolean outdoorTempActive = bricksSet.contains(BrickType.OUTDOOR_TEMP)
                && (car == null || car.isBrickSupported(BrickType.OUTDOOR_TEMP));
        boolean homeAssistantActive = bricksSet.contains(BrickType.HOME_ASSISTANT)
                && binding.homeAssistantContainer.getChildCount() > 0;
        boolean phoneStatusActive = bricksSet.contains(BrickType.PHONE_STATUS)
                && hasVisiblePhoneStatusValues();
        boolean phoneCellularActive = bricksSet.contains(BrickType.PHONE_CELLULAR)
                && (phonePercent("network.signal") != null
                || !phoneText("network.operator").isEmpty()
                || prefs.phoneCellular.showNetworkType.get()
                && !phoneNetworkType().isEmpty());
        boolean phoneBatteryActive = bricksSet.contains(BrickType.PHONE_BATTERY)
                && phonePercent("battery.level") != null;
        boolean phoneNetworkTypeActive = bricksSet.contains(BrickType.PHONE_NETWORK_TYPE)
                && !phoneNetworkType().isEmpty();
        BrickTarget[] targets = {
                resolveTarget(BrickType.TIME, bricksSet.contains(BrickType.TIME),
                        binding.timeText, prefs.time.contentAlpha.get()),
                resolveTarget(BrickType.DATE, dateActive,
                        binding.dateText, prefs.date.contentAlpha.get()),
                resolveTarget(BrickType.WIFI, bricksSet.contains(BrickType.WIFI),
                        binding.wifiStatusIcon, prefs.wifi.contentAlpha.get()),
                resolveTarget(BrickType.GPS, bricksSet.contains(BrickType.GPS),
                        binding.gnssStatusIcon, prefs.gps.contentAlpha.get()),
                resolveTarget(BrickType.BLUETOOTH, bricksSet.contains(BrickType.BLUETOOTH),
                        binding.bluetoothStatusIcon, prefs.bluetooth.contentAlpha.get()),
                resolveTarget(BrickType.INDOOR_TEMP, indoorTempActive,
                        binding.indoorTempText, prefs.indoorTemp.contentAlpha.get()),
                resolveTarget(BrickType.OUTDOOR_TEMP, outdoorTempActive,
                        binding.outdoorTempText, prefs.outdoorTemp.contentAlpha.get()),
                resolveTarget(BrickType.HOME_ASSISTANT, homeAssistantActive,
                        binding.homeAssistantContainer, prefs.homeAssistant.contentAlpha.get()),
                resolveTarget(BrickType.PHONE_STATUS, phoneStatusActive,
                        binding.phoneStatusContainer, prefs.phoneStatus.contentAlpha.get()),
                resolveTarget(BrickType.PHONE_CELLULAR, phoneCellularActive,
                        binding.phoneCellularContainer, prefs.phoneCellular.contentAlpha.get()),
                resolveTarget(BrickType.PHONE_BATTERY, phoneBatteryActive,
                        binding.phoneBatteryStatusIcon, prefs.phoneBattery.contentAlpha.get()),
                resolveTarget(BrickType.PHONE_NETWORK_TYPE, phoneNetworkTypeActive,
                        binding.phoneNetworkTypeText, prefs.phoneNetworkType.contentAlpha.get()),
        };

        // Media has the extra session gate, so we build its BrickTarget here. In particular, the
        // deferred post-boot integration refresh must not make an empty mediaContainer visible
        // after enableMediaTracking already hid it: only real active media may occupy the row.
        boolean phoneNotificationActive = isPhoneNotificationActive();
        MediaController mediaController = pickActiveMediaController();
        boolean mediaSessionActive = StatusMediaVisibilityPolicy.hasVisibleContent(
                phoneNotificationActive,
                mediaController != null,
                isActuallyPlaying(mediaController),
                prefs.media.onlyWhilePlaying.get());
        boolean mediaShouldBeGone = !bricksSet.contains(BrickType.MEDIA)
                || !isRemotelyVisible(BrickType.MEDIA) || !mediaSessionActive;
        boolean mediaHiddenByApp = !mediaShouldBeGone
                && isBrickHiddenByApp(BrickType.MEDIA);
        BrickTarget mediaTarget;
        if (mediaShouldBeGone) {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
        } else if (mediaHiddenByApp) {
            if (prefs.hideKeepsSpaceFor(BrickType.MEDIA).get()) {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE, 0f);
            } else {
                mediaTarget = new BrickTarget(binding.mediaContainer, View.GONE, 1f);
            }
        } else {
            mediaTarget = new BrickTarget(binding.mediaContainer, View.VISIBLE,
                    prefs.media.contentAlpha.get() / 255f);
        }

        // Categorise the changes. Visibility flips (VISIBLE↔GONE) get the TransitionManager +
        // window-buffer treatment; pure alpha changes (keep-space mode where the brick stays
        // in the layout) just get a plain alpha animation.
        java.util.List<BrickTarget> visibilityFlips = new java.util.ArrayList<>();
        java.util.List<BrickTarget> alphaOnly = new java.util.ArrayList<>();
        boolean expanding = false;
        for (BrickTarget t : targets) {
            if (t.view.getVisibility() != t.visibility) {
                visibilityFlips.add(t);
                if (t.visibility == View.VISIBLE) expanding = true;
            } else if (t.visibility == View.VISIBLE) {
                alphaOnly.add(t);
            }
        }
        // Media too.
        if (mediaTarget.view.getVisibility() != mediaTarget.visibility) {
            visibilityFlips.add(mediaTarget);
            if (mediaTarget.visibility == View.VISIBLE) expanding = true;
        }
        boolean refreshVisibleMedia = mediaTarget.visibility == View.VISIBLE
                && !mediaShouldBeGone && !mediaHiddenByApp;

        if (!visibilityFlips.isEmpty() && overlayAttached) {
            // Scene root for TransitionManager is the INNER container — the outer FrameLayout
            // gets resized to a screen-width buffer via WindowManager, and we want the
            // transition to play inside the stable inner LinearLayout, not chase the buffer.
            beginVisibilityTransition(binding.overlayContainer, expanding);
        }

        // Apply all targets. For visibility flips Fade transition handles the alpha animation;
        // for alpha-only ones we run an explicit ViewPropertyAnimator.
        for (BrickTarget t : targets) {
            applyBrickTarget(t, overlayAttached && visibilityFlips.contains(t));
        }
        applyBrickTarget(mediaTarget,
                overlayAttached && visibilityFlips.contains(mediaTarget));
        // Populate the media rows on the very frame in which the brick becomes visible. The old
        // path refreshed only VISIBLE→VISIBLE; GONE→VISIBLE exposed the XML bootstrap state
        // (state icon plus an empty-but-measurable duration TextView) until the next player
        // callback. Depending on when Yandex Music published metadata, that could last seconds
        // after boot and then visibly change the status-row height.
        if (refreshVisibleMedia) {
            updateMediaInfo();
        }

        // Per-brick alpha not covered by the Fade transition (keep-space VISIBLE→VISIBLE).
        // The bricks in alphaOnly might still want a visible-alpha update if contentAlpha
        // pref changed — handled by applyXxxBrickSettings setAlpha which runs before this.
    }

    /** Snapshot of the desired end state for a brick view. */
    private static final class BrickTarget {
        final View view;
        final int visibility;
        /** Target alpha when {@link #visibility} is {@code VISIBLE}; ignored otherwise. */
        final float visibleAlpha;
        BrickTarget(View view, int visibility, float visibleAlpha) {
            this.view = view;
            this.visibility = visibility;
            this.visibleAlpha = visibleAlpha;
        }
    }

    /**
     * Decide the final view state for a brick. {@code activeInLayout=false} (brick not in
     * the layout / Date with both flags off) → {@code GONE}, hard collapse. Otherwise honour
     * {@link Preferences#hideKeepsSpaceFor}: if true, render an INVISIBLE-equivalent (VISIBLE
     * view, alpha animated to 0); if false, plain GONE.
     */
    private BrickTarget resolveTarget(BrickType type, boolean activeInLayout, View view,
                                      int contentAlphaPref) {
        float baseAlpha = contentAlphaPref / 255f;
        if (!activeInLayout || !isRemotelyVisible(type)) {
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        // HA children independently choose whether to inherit the group's app list; their
        // renderer has already removed or made transparent the matching children.
        if (type != BrickType.HOME_ASSISTANT && isBrickHiddenByApp(type)) {
            if (prefs.hideKeepsSpaceFor(type).get()) {
                // VISIBLE-with-alpha-0 replaces the old INVISIBLE constant — same effect on
                // layout (space preserved) but animatable.
                return new BrickTarget(view, View.VISIBLE, 0f);
            }
            return new BrickTarget(view, View.GONE, baseAlpha);
        }
        return new BrickTarget(view, View.VISIBLE, baseAlpha);
    }

    private boolean isRemotelyVisible(BrickType type) {
        return automationStates == null || automationStates
                .get(AutomationContract.SCOPE_BUILTIN, type.automationId()).visible;
    }

    /** Called after either an exported Broadcast or MQTT packet has been persisted. */
    public void onAutomationStateChanged(String scope, String id) {
        if (destroyed) return;
        if (scenarioController != null) {
            scenarioController.refreshSystemConditions();
        }
        synchronized (automationUiLock) {
            pendingAutomationUi.computeIfAbsent(scope, ignored -> new HashSet<>()).add(id);
        }
        schedulePendingAutomationUiRefresh();
    }

    private void dispatchAutomationPresentationTargets(@NonNull Set<String> targets) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (String target : targets) {
            int divider = target.indexOf('|');
            if (divider <= 0 || divider >= target.length() - 1) continue;
            grouped.computeIfAbsent(target.substring(0, divider), ignored -> new HashSet<>())
                    .add(target.substring(divider + 1));
        }
        dispatchAutomationPresentationChanges(grouped);
    }

    private void dispatchAutomationPresentationChanges(
            @NonNull Map<String, Set<String>> changed) {
        if (automationPresentationListeners.isEmpty()) return;
        for (Map.Entry<String, Set<String>> entry : changed.entrySet()) {
            Set<String> ids = Collections.unmodifiableSet(new LinkedHashSet<>(entry.getValue()));
            for (AutomationPresentationListener listener : automationPresentationListeners) {
                try {
                    listener.onAutomationPresentationChanged(entry.getKey(), ids);
                } catch (RuntimeException failure) {
                    Log.w(TAG, "Automation presentation listener failed", failure);
                }
            }
        }
    }

    private boolean automaticSurfaceRefreshSuppressed() {
        return initialIntegrationStartupInProgress || credentialRefreshScheduled
                || automaticRuntimeParked || automaticLifecycleQuiet
                || StartupWorkCoordinator.shouldDeferAutomaticStickyRestart(this);
    }

    private void schedulePendingIntegrationReconfigure() {
        if (!integrationReconfigurePending || automaticSurfaceRefreshSuppressed()
                || destroyed) return;
        integrationReconfigurePending = false;
        mainHandler.post(() -> {
            if (!destroyed) applyPreferences(true);
        });
    }

    private void schedulePendingAutomationUiRefresh() {
        synchronized (automationUiLock) {
            if (pendingAutomationUi.isEmpty() || automationUiRefreshScheduled
                    || automaticSurfaceRefreshSuppressed()) return;
            automationUiRefreshScheduled = true;
        }
        // One rendered frame per connector burst instead of rebuilding the row once per entity.
        mainHandler.postDelayed(automationUiRefresh, 32L);
    }

    /** Read-only snapshots let the second overlay reuse original brick data without duplicating
     * notification, eCarX, GNSS or connectivity listeners. Called only on the main thread. */
    @Nullable
    private PopupOverlayController.BuiltinValue popupBuiltinValue(@NonNull String id) {
        if (binding == null) return null;
        switch (id) {
            case "builtin.time":
                return new PopupOverlayController.BuiltinValue(timeFormat.format(new Date()),
                        "#FFFFFFFF", null, true);
            case "builtin.date":
                return new PopupOverlayController.BuiltinValue(String.valueOf(binding.dateText.getText()),
                        "#FFFFFFFF", null, true);
            case "builtin.media":
                return new PopupOverlayController.BuiltinValue(lastMediaSubtitle,
                        "#FFFFFFFF", null, !isEmpty(lastMediaSubtitle));
            case "builtin.wifi":
                return new PopupOverlayController.BuiltinValue("", "#FFFFFFFF", "wifi",
                        true);
            case "builtin.gps":
                return new PopupOverlayController.BuiltinValue("", "#FFFFFFFF", "gps",
                        true);
            case "builtin.bluetooth":
                return new PopupOverlayController.BuiltinValue("", "#FFFFFFFF", "bluetooth",
                        true);
            case "builtin.indoor_temp":
                return popupTextValue(binding.indoorTempText, "temperature", true);
            case "builtin.outdoor_temp":
                return popupTextValue(binding.outdoorTempText, "temperature", true);
            case "builtin.home_assistant":
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < binding.homeAssistantContainer.getChildCount(); i++) {
                    View child = binding.homeAssistantContainer.getChildAt(i);
                    if (!(child instanceof android.widget.TextView)
                            || child.getVisibility() != View.VISIBLE) continue;
                    if (text.length() > 0) text.append(' ');
                    text.append(((android.widget.TextView) child).getText());
                }
                return new PopupOverlayController.BuiltinValue(text.toString(), "#FFFFFFFF", null,
                        binding.homeAssistantContainer.getVisibility() == View.VISIBLE);
            case "builtin.phone_status":
                return new PopupOverlayController.BuiltinValue(
                        joinedVisibleText(binding.phoneStatusContainer), "#FFFFFFFF",
                        "phone", binding.phoneStatusContainer.getVisibility() == View.VISIBLE);
            case "builtin.phone_cellular":
                Integer signal = phonePercent("network.signal");
                String operator = phoneText("network.operator");
                return new PopupOverlayController.BuiltinValue(
                        !operator.isEmpty() ? operator : signal == null ? "" : signal + "%",
                        "#FFFFFFFF",
                        "phone", signal != null || !operator.isEmpty());
            case "builtin.phone_battery":
                Integer battery = phonePercent("battery.level");
                return new PopupOverlayController.BuiltinValue(
                        battery == null ? "" : battery + "%", "#FFFFFFFF",
                        "battery", battery != null);
            default:
                return null;
        }
    }

    private static PopupOverlayController.BuiltinValue popupTextValue(
            android.widget.TextView view, @Nullable String iconId, boolean visible) {
        return new PopupOverlayController.BuiltinValue(String.valueOf(view.getText()),
                String.format(Locale.ROOT, "#%08X", view.getCurrentTextColor()), iconId,
                visible);
    }

    /**
     * Applies a brick's target state. For visibility flips the heavy lifting is done by the
     * {@code TransitionManager} scene set up by {@link #beginVisibilityTransition} — we
     * just toggle {@code setVisibility} and the Fade transition cross-fades alpha while
     * ChangeBounds slides siblings into place. For alpha-only changes (keep-space hide)
     * we animate alpha explicitly.
     */
    private void applyBrickTarget(BrickTarget target, boolean handledByTransition) {
        if (!overlayAttached) {
            // Pre-addView geometry normalization must be synchronous and final: animators on a
            // detached tree can preserve the XML alpha/visibility into its first attached frame.
            target.view.animate().cancel();
            target.view.setVisibility(target.visibility);
            if (target.visibility == View.VISIBLE) {
                target.view.setAlpha(target.visibleAlpha);
            }
            return;
        }
        if (target.visibility == View.GONE) {
            target.view.animate().cancel();
            target.view.setVisibility(View.GONE);
            return;
        }
        target.view.setVisibility(View.VISIBLE);
        if (handledByTransition) {
            // Fade transition animates the alpha for us; make sure the final value is the
            // brick's contentAlpha pref (not 1.0 from Fade's default).
            target.view.setAlpha(target.visibleAlpha);
        } else {
            target.view.animate().cancel();
            target.view.animate()
                    .alpha(target.visibleAlpha)
                    .setDuration(BRICK_ALPHA_DURATION_MS)
                    .start();
        }
    }

    /**
     * Runs the "buffer window" animation. Trick: before triggering the
     * scene change we either expand the window to screen width (when something is about to
     * appear) or pin it to its current width (when something is about to disappear). With
     * the window's outer rectangle frozen the children's Fade + ChangeBounds animations
     * play cleanly inside it; the listener restores the window to WRAP_CONTENT after the
     * transition so it snaps to the new natural size in one go. This sidesteps the
     * per-frame {@code updateViewLayout} approach that was visually broken on real hardware.
     */
    private void beginVisibilityTransition(ViewGroup sceneRoot, boolean expanding) {
        if (binding == null) return;
        beginBufferedTransition(expanding);

        android.transition.TransitionSet tx = new android.transition.TransitionSet();
        tx.addTransition(new android.transition.ChangeBounds());
        tx.addTransition(new android.transition.Fade());
        tx.setOrdering(android.transition.TransitionSet.ORDERING_TOGETHER);
        tx.setDuration(BRICK_TRANSITION_DURATION_MS);
        tx.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        // Listener can leak the buffer counter if TransitionManager decides nothing
        // animatable changed and never fires the lifecycle callbacks — known foot-gun.
        // Guard with a single-shot close flag and a safety runnable that runs unconditionally
        // after slightly longer than the transition's own duration. Whichever fires first
        // closes the buffer; the other becomes a no-op.
        final boolean[] closed = {false};
        Runnable closeOnce = () -> {
            if (closed[0]) return;
            closed[0] = true;
            endBufferedTransition();
        };
        tx.addListener(new android.transition.Transition.TransitionListener() {
            @Override public void onTransitionStart(android.transition.Transition t) {}
            @Override public void onTransitionEnd(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionCancel(android.transition.Transition t) {
                closeOnce.run();
            }
            @Override public void onTransitionPause(android.transition.Transition t) {}
            @Override public void onTransitionResume(android.transition.Transition t) {}
        });
        android.transition.TransitionManager.beginDelayedTransition(sceneRoot, tx);
        mainHandler.postDelayed(closeOnce, BRICK_TRANSITION_DURATION_MS + 500);
    }

    /**
     * Open a window-buffered transition: if no other buffered transition is in flight, pre-resize
     * the WindowManager window to either screen width ({@code expanding}) or its current width
     * (shrinking), so the animation that follows plays inside a stable rectangle instead of
     * fighting wrap-content. Idempotent under nesting: re-entrant callers just bump the counter.
     */
    private void beginBufferedTransition(boolean expanding) {
        if (binding == null) return;
        if (pendingBufferedTransitions++ == 0) {
            if (params != null && prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                int oldWidth = params.width;
                if (expanding) {
                    params.width = getResources().getDisplayMetrics().widthPixels;
                } else {
                    int currentWidth = binding.getRoot().getWidth();
                    if (currentWidth > 0) params.width = currentWidth;
                }
                try {
                    windowManager.updateViewLayout(binding.getRoot(), params);
                } catch (Exception ignored) {
                    params.width = oldWidth;
                }
            }
        }
    }

    /** Closes a transition opened by {@link #beginBufferedTransition}. When the last in-flight
     *  transition ends, restores the window to WRAP_CONTENT so it snaps to natural size. */
    private void endBufferedTransition() {
        if (pendingBufferedTransitions <= 0) return;
        if (--pendingBufferedTransitions == 0) {
            restoreWindowToWrapContent();
        }
    }

    private void restoreWindowToWrapContent() {
        if (params == null || binding == null) return;
        if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
        } else {
            params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        }
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {}
    }

    private Set<BrickType> currentBrickSet() {
        Set<BrickType> set = EnumSet.noneOf(BrickType.class);
        set.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        return set;
    }

    @NonNull
    private Set<BrickType> driverInformationBrickTypes() {
        boolean enabled = prefs != null && prefs.driverPanelEnabled.get();
        String json = prefs == null ? ""
                : prefs.activeDriverPanelProfile().shortcutsJson.get();
        if (configuredDriverInformationJson != null
                && enabled == configuredDriverPanelEnabled
                && Objects.equals(json, configuredDriverInformationJson)) {
            return configuredDriverInformationTypes;
        }
        Set<BrickType> result = immutableBrickTypes(loadDriverInformationBrickTypes());
        configuredDriverPanelEnabled = enabled;
        configuredDriverInformationJson = prefs == null ? ""
                : prefs.activeDriverPanelProfile().shortcutsJson.get();
        configuredDriverInformationTypes = result;
        return result;
    }

    @NonNull
    private Set<BrickType> loadDriverInformationBrickTypes() {
        Set<BrickType> result = EnumSet.noneOf(BrickType.class);
        if (prefs == null || !prefs.driverPanelEnabled.get()) return result;
        for (LauncherShortcutStore.Shortcut shortcut :
                LauncherShortcutStore.forDriverPanel(prefs).all()) {
            if (!shortcut.enabled || shortcut.kind != LauncherShortcutStore.Kind.INFO) continue;
            BrickType type = StatusBarInformationCatalog.typeForTarget(shortcut.target);
            if (type != null) result.add(type);
        }
        return result;
    }

    /**
     * Computes the tallest brick height (in pixels) over all bricks currently in
     * {@code brickOrder}, regardless of per-app visibility. Used as the widget's minimum height so
     * a brick disappearing on a particular app doesn't shrink the row.
     *
     * Text bricks use {@link Paint#getFontMetrics()} on a copy of the TextView's paint at the
     * given pixel size — this matches exactly the height the TextView itself would measure for a
     * single line (with {@code includeFontPadding=true}, the default).
     */
    private int computeMinWidgetHeight(Set<BrickType> bricks) {
        int h = 0;
        if (bricks.contains(BrickType.TIME)) {
            h = Math.max(h, textLineHeight(binding.timeText, prefs.time.fontSize.get()));
        }
        if (bricks.contains(BrickType.DATE)) {
            // Two lines when day-of-week + date are both shown and not collapsed into one line.
            int lines = (prefs.date.showDate.get() && prefs.date.showDayOfWeek.get()
                    && !prefs.date.oneLineLayout.get()) ? 2 : 1;
            h = Math.max(h, textLineHeight(binding.dateText, prefs.date.fontSize.get()) * lines);
        }
        if (bricks.contains(BrickType.MEDIA)) {
            // Reserve the complete configured media geometry even before the first track
            // arrives. Duration and progress are metadata-dependent children: if they are not
            // included in this floor, the WindowManager's WRAP_CONTENT status window can change
            // height when the first MediaSession snapshot populates them.
            int titleHeight = textLineHeight(binding.mediaTitleText, prefs.media.fontSize.get());
            int titleRowHeight = titleHeight;
            if (prefs.media.showDuration.get()) {
                titleRowHeight = Math.max(titleRowHeight, textLineHeight(
                        binding.mediaDurationText, prefs.media.durationFontSize.get()));
            }
            int mediaHeight = titleRowHeight;
            if (prefs.media.showSource.get()) {
                int sourceHeight = textLineHeight(binding.mediaAppText,
                        prefs.media.sourceFontSize.get());
                mediaHeight = sourceHeight + titleRowHeight + prefs.media.lineGap.get();
            }
            if (prefs.media.progressBarEnabled.get()) {
                ViewGroup.LayoutParams raw = binding.mediaProgressBar.getLayoutParams();
                int progressHeight = Math.max(0, raw.height);
                if (raw instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) raw;
                    progressHeight += Math.max(0, margins.topMargin)
                            + Math.max(0, margins.bottomMargin);
                }
                mediaHeight += progressHeight;
            }
            h = Math.max(h, mediaHeight);
        }
        if (bricks.contains(BrickType.WIFI)) {
            h = Math.max(h, prefs.wifi.size.get());
        }
        if (bricks.contains(BrickType.GPS)) {
            h = Math.max(h, prefs.gps.size.get());
        }
        if (bricks.contains(BrickType.BLUETOOTH)) {
            h = Math.max(h, prefs.bluetooth.size.get());
        }
        if (bricks.contains(BrickType.PHONE_CELLULAR)) {
            h = Math.max(h, prefs.phoneCellular.size.get());
        }
        if (bricks.contains(BrickType.PHONE_BATTERY)) {
            h = Math.max(h, prefs.phoneBattery.size.get());
        }
        if (bricks.contains(BrickType.PHONE_NETWORK_TYPE)) {
            h = Math.max(h, textLineHeight(
                    binding.phoneNetworkTypeText, prefs.phoneNetworkType.fontSize.get()));
        }
        // Car bricks only contribute to the height floor when the vehicle actually renders them
        // (same isBrickSupported gate as applyBrickVisibility) — otherwise a preset from another
        // car would inflate the widget height for bricks that never appear.
        CarIntegration car = carTelemetryExporter == null ? null : CarIntegrations.get(this);
        if (bricks.contains(BrickType.INDOOR_TEMP)
                && (car == null || car.isBrickSupported(BrickType.INDOOR_TEMP))) {
            h = Math.max(h, textLineHeight(binding.indoorTempText, prefs.indoorTemp.fontSize.get()));
        }
        if (bricks.contains(BrickType.OUTDOOR_TEMP)
                && (car == null || car.isBrickSupported(BrickType.OUTDOOR_TEMP))) {
            h = Math.max(h, textLineHeight(binding.outdoorTempText, prefs.outdoorTemp.fontSize.get()));
        }
        if (bricks.contains(BrickType.HOME_ASSISTANT)) {
            for (int i = 0; i < binding.homeAssistantContainer.getChildCount(); i++) {
                View child = binding.homeAssistantContainer.getChildAt(i);
                if (child instanceof OutlineTextView) {
                    OutlineTextView text = (OutlineTextView) child;
                    h = Math.max(h, textLineHeight(text, Math.round(text.getTextSize()))
                            + text.getPaddingTop() + text.getPaddingBottom());
                }
            }
        }
        if (bricks.contains(BrickType.PHONE_STATUS)) {
            h = Math.max(h, textLineHeight(
                    firstPhoneStatusTextView(), prefs.phoneStatus.fontSize.get()));
        }
        return h;
    }

    private static int textLineHeight(OutlineTextView view, int fontSizePx) {
        // Copy so we don't mutate the live drawing paint. The copy preserves typeface, which is
        // crucial because Roboto Condensed Medium has different metrics from the default.
        Paint p = new Paint(view.getPaint());
        p.setTextSize(fontSizePx);
        Paint.FontMetrics fm = p.getFontMetrics();
        // All text TextViews in the widget have includeFontPadding=false — layout bounds use
        // ascent/descent (just the glyph metrics, no extra accent/descender reserve).
        return (int) Math.ceil(fm.descent - fm.ascent);
    }

    public void setOverlayStateListener(@Nullable OverlayStateListener listener) {
        this.overlayStateListener = listener;
        if (listener != null) {
            notifyOverlayState();
        }
    }

    private void notifyOverlayState() {
        if (overlayStateListener == null || params == null || binding == null) return;
        overlayStateListener.onOverlayStateChanged(
                params.x, params.y,
                binding.getRoot().getWidth(),
                binding.getRoot().getHeight());
    }

    /**
     * Pushes the saved widget position and mode-specific window params into the WindowManager.
     * Called from {@link #applyPreferences()} so the position sliders / mode switcher in
     * settings affect the widget live. Skipped when the widget isn't drawn yet.
     */
    private void applyOverlayPosition() {
        if (params == null || binding == null || windowManager == null) return;
        boolean statusBar = prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR;
        int newWidth = statusBar
                ? WindowManager.LayoutParams.MATCH_PARENT
                : WindowManager.LayoutParams.WRAP_CONTENT;
        // During a buffered transition the window is intentionally pinned wider than
        // wrap_content so children can animate without being clipped. Overwriting
        // params.width here would snap the window mid-animation and also strand the
        // TransitionManager listener (no scene change → no onTransitionEnd → counter
        // leak). The buffer closer will restore wrap_content when it ends.
        if (pendingBufferedTransitions > 0 && !statusBar) {
            newWidth = params.width;
        }
        int newX = statusBar ? 0 : prefs.overlayX.get();
        int newY = statusBar ? 0 : prefs.overlayY.get();
        if (params.x == newX && params.y == newY && params.width == newWidth) return;
        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        try {
            windowManager.updateViewLayout(binding.getRoot(), params);
        } catch (Exception ignored) {
        }
    }

    private void enableMediaTracking() {
        if (mediaSessionManager != null) {
            // applyBrickVisibility() runs before this method and may have made the configured
            // media brick VISIBLE. Reconcile it even when tracking was already registered:
            // without an active controller the empty container must return to GONE immediately,
            // rather than occupying a blank row until the first MediaSession callback.
            updateMediaInfo();
            return;
        }
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) return;
        ComponentName component = new ComponentName(this, MediaNotificationListener.class);
        int generation = ++mediaBindingGeneration;
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, mainHandler);
            requestInitialMediaControllers(mediaSessionManager, component, generation);
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not granted; media tracking disabled", e);
            mediaSessionManager = null;
        }
    }

    private void disableMediaTracking() {
        if (mediaSessionManager == null) return;
        mediaBindingGeneration++;
        try {
            mediaSessionManager.removeOnActiveSessionsChangedListener(activeSessionsChangedListener);
        } catch (Exception ignored) {
        }
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        mediaSessionManager = null;
    }

    private void requestInitialMediaControllers(@NonNull MediaSessionManager source,
                                                @NonNull ComponentName component,
                                                int generation) {
        try {
            startupStateWorker.execute(() -> {
                List<MediaController> controllers = null;
                RuntimeException failure = null;
                try {
                    controllers = source.getActiveSessions(component);
                } catch (RuntimeException error) {
                    failure = error;
                }
                List<MediaController> result = controllers;
                RuntimeException queryFailure = failure;
                mainHandler.post(() -> {
                    if (destroyed || mediaSessionManager != source
                            || generation != mediaBindingGeneration) return;
                    if (queryFailure == null) rebindMediaControllers(result);
                    else updateMediaInfo();
                });
            });
        } catch (RuntimeException stopped) {
            updateMediaInfo();
        }
    }

    private void rebindMediaControllers(@Nullable List<MediaController> controllers) {
        for (MediaController c : activeMediaControllers) {
            c.unregisterCallback(mediaControllerCallback);
        }
        activeMediaControllers.clear();
        if (controllers != null) {
            for (MediaController c : controllers) {
                activeMediaControllers.add(c);
                c.registerCallback(mediaControllerCallback, mainHandler);
            }
        }
        updateMediaInfo();
    }

    private void handlePhoneLowBatteryAlert(@NonNull ConnectorValue value) {
        if (prefs == null || !value.fresh || !value.available || !value.readable
                || !(value.rawValue instanceof Number)) return;
        int level = ((Number) value.rawValue).intValue();
        if (level < 0 || level > 100) return;
        reconcilePhoneLowBatteryAlertStage(level, 1,
                prefs.phoneLowBatteryAlertThreshold.get(),
                prefs.phoneLowBatteryAlertColor.get());
        reconcilePhoneLowBatteryAlertStage(level, 2,
                prefs.phoneLowBatteryAlertThreshold2.get(),
                prefs.phoneLowBatteryAlertColor2.get());
    }

    private void reconcilePhoneLowBatteryAlertStage(int level, int stage, int threshold,
                                                    @NonNull String color) {
        boolean first = stage == 1;
        boolean latched = first ? phoneLowBatteryAlertLatched
                : phoneLowBatteryAlertLatched2;
        boolean pending = first ? phoneLowBatteryAlertPending
                : phoneLowBatteryAlertPending2;
        PhoneLowBatteryAlertPolicy.Result result = PhoneLowBatteryAlertPolicy.evaluate(
                prefs.phoneLowBatteryAlertEnabled.get(),
                threshold, latched || pending, level);
        if (!result.latched) {
            if (first) {
                phoneLowBatteryAlertPending = false;
                if (phoneLowBatteryAlertLatched) {
                    phoneLowBatteryAlertLatched = false;
                    prefs.phoneLowBatteryAlertLatched.set(false);
                }
            } else {
                phoneLowBatteryAlertPending2 = false;
                if (phoneLowBatteryAlertLatched2) {
                    phoneLowBatteryAlertLatched2 = false;
                    prefs.phoneLowBatteryAlertLatched2.set(false);
                }
            }
            return;
        }
        if (latched || pending || !result.trigger) return;

        if (first) phoneLowBatteryAlertPending = true;
        else phoneLowBatteryAlertPending2 = true;
        if (!enqueuePhoneLowBatteryAlert(level, color, stage)) {
            // Lock/destination/queue rejection is not a delivered warning. Keep the persistent
            // latch false so a later gate change or telemetry sample can retry it.
            if (first) phoneLowBatteryAlertPending = false;
            else phoneLowBatteryAlertPending2 = false;
        }
    }

    /**
     * Publishes the three fields before either destination renders. Local scenarios therefore
     * resolve the exact same application/topic/text visibility for the status row and popup.
     */
    private void updatePhoneNotificationFieldStates(
            @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation,
            @NonNull Set<String> selectedFields) {
        if (automationStates == null || prefs == null) return;
        int seconds = Math.max(1, Math.min(120,
                prefs.phoneStatusBarNotificationSeconds.get()));
        long now = System.currentTimeMillis();
        long expiresAt = phoneNotificationOverlayPaused
                ? 0L : now + seconds * 1_000L;
        try {
            for (String fieldId : PhoneStatusBarPolicy.notificationFieldIds()) {
                String automationId =
                        PhoneNotificationAutomation.automationIdForField(fieldId);
                String text = PhoneStatusBarPolicy.notificationFieldText(
                        presentation, fieldId);
                boolean visible = selectedFields.contains(fieldId) && !text.isEmpty();
                JSONObject patch = new JSONObject()
                        .put("text", text)
                        .put("visible", visible)
                        .put("fresh", true)
                        .put("source", "phone")
                        .put("updated_at", now)
                        .put("expires_at", expiresAt);
                if (PhoneStatusBarPolicy.FIELD_APPLICATION.equals(fieldId)) {
                    patch.put("icon", presentation.iconCached
                            && !presentation.appIdentifier.isEmpty()
                            ? "phone-app:" + presentation.appIdentifier
                            : "");
                }
                automationStates.apply(AutomationContract.SCOPE_POPUP, automationId,
                        patch);
                onAutomationStateChanged(AutomationContract.SCOPE_POPUP, automationId);
            }
        } catch (JSONException | RuntimeException failure) {
            Log.e(TAG, "Could not publish phone notification fields", failure);
        }
    }

    private boolean showPhoneStatusNotification(
            @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation,
            @NonNull Set<String> selectedFields) {
        // A low-battery warning is rarer and safety-relevant; keep it visible for its full
        // configured interval instead of letting a routine ANCS event replace it.
        if (activePhoneBatteryAlertText != null && isPhoneNotificationActive()) return false;
        boolean replacingPresentation = hasActivePhoneStatusAlert();
        if (!replacingPresentation && binding != null) {
            mediaDurationVisibilityBeforePhoneNotification =
                    binding.mediaDurationText.getVisibility();
            mediaProgressVisibilityBeforePhoneNotification =
                    binding.mediaProgressBar.getVisibility();
        }
        if (binding != null) {
            // A second notification can have identical text. Force a fresh marquee cycle for
            // the new delivery instead of continuing halfway through the previous one.
            binding.mediaTitleText.setMarqueeText("");
        }
        activePhoneNotification = presentation;
        activePhoneNotificationFields = Collections.unmodifiableSet(
                new LinkedHashSet<>(selectedFields));
        activePhoneBatteryAlertText = null;
        activePhoneBatteryAlertColor = null;
        schedulePhoneStatusAlert();
        return true;
    }

    /** Opens the reserved window in place; its three field states were already replaced above. */
    private boolean showPhonePopupNotification(
            @NonNull PhoneStatusBarPolicy.NotificationPresentation presentation) {
        if (automationStates == null || prefs == null) return false;
        try {
            ensurePhoneNotificationPopupConfigured();
            if (!phoneNotificationPopupConfigured) return false;
            applyPopupPreferencesSafely();
            int seconds = Math.max(1, Math.min(120,
                    prefs.phoneStatusBarNotificationSeconds.get()));
            long now = System.currentTimeMillis();
            long expiresAt = phoneNotificationOverlayPaused
                    ? 0L : now + seconds * 1_000L;
            JSONObject overlay = new JSONObject()
                    .put("visible", true)
                    .put("fresh", true)
                    .put("source", "phone")
                    .put("updated_at", now)
                    .put("expires_at", expiresAt);
            boolean useIconLayout = presentation.iconCached
                    && !presentation.appIdentifier.isEmpty()
                    && PhoneAppIconStore.get(this).hasIcon(presentation.appIdentifier);
            String shownOverlay = useIconLayout
                    ? PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID
                    : PhoneNotificationAutomation.OVERLAY_ID;
            String hiddenOverlay = useIconLayout
                    ? PhoneNotificationAutomation.OVERLAY_ID
                    : PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID;
            automationStates.apply(AutomationContract.SCOPE_OVERLAY,
                    hiddenOverlay,
                    new JSONObject().put("visible", false).put("fresh", false)
                            .put("updated_at", now));
            onAutomationStateChanged(AutomationContract.SCOPE_OVERLAY, hiddenOverlay);
            automationStates.apply(AutomationContract.SCOPE_OVERLAY,
                    shownOverlay, overlay);
            onAutomationStateChanged(AutomationContract.SCOPE_OVERLAY,
                    shownOverlay);
            activePhonePopupNotification = presentation;
            if (phoneNotificationOverlayPaused) {
                pausedPhonePopupRemainingMs = seconds * 1_000L;
                activePhonePopupNotificationExpiresAt = Long.MAX_VALUE;
            } else {
                activePhonePopupNotificationExpiresAt =
                        android.os.SystemClock.elapsedRealtime() + seconds * 1_000L;
            }
            activePhoneLowBatteryPopup = false;
            mainHandler.removeCallbacks(phonePopupNotificationExpiry);
            if (!phoneNotificationOverlayPaused) {
                mainHandler.postDelayed(phonePopupNotificationExpiry, seconds * 1_000L);
            }
            return true;
        } catch (JSONException | RuntimeException failure) {
            Log.e(TAG, "Could not present phone notification popup", failure);
            return false;
        }
    }

    private void clearPhonePopupNotification() {
        mainHandler.removeCallbacks(phonePopupNotificationExpiry);
        activePhonePopupNotificationExpiresAt = 0L;
        activePhonePopupNotification = null;
        pausedPhonePopupRemainingMs = 0L;
        activePhoneLowBatteryPopup = false;
        if (automationStates == null) return;
        try {
            long now = System.currentTimeMillis();
            for (String overlayId : new String[]{
                    PhoneNotificationAutomation.OVERLAY_ID,
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID}) {
                automationStates.apply(AutomationContract.SCOPE_OVERLAY,
                        overlayId,
                        new JSONObject().put("visible", false).put("fresh", false)
                                .put("updated_at", now));
                onAutomationStateChanged(AutomationContract.SCOPE_OVERLAY, overlayId);
            }
            clearPhoneNotificationFieldsIfInactive();
        } catch (JSONException | RuntimeException failure) {
            Log.w(TAG, "Could not clear phone notification popup", failure);
        }
    }

    /**
     * Clears shared field state only after both destinations have finished. This keeps status-row
     * conditions working when the popup is disabled, and popup conditions working when the main
     * status surface is disabled.
     */
    private void clearPhoneNotificationFieldsIfInactive() {
        if (automationStates == null) return;
        long elapsed = android.os.SystemClock.elapsedRealtime();
        boolean statusStillUsesFields = activePhoneNotification != null
                && prefs != null
                && prefs.phoneStatusBarNotificationsEnabled.get()
                && elapsed < activePhoneNotificationExpiresAt;
        boolean popupStillUsesFields = activePhonePopupNotificationExpiresAt > 0L
                && elapsed < activePhonePopupNotificationExpiresAt;
        if (statusStillUsesFields || popupStillUsesFields) return;
        try {
            long now = System.currentTimeMillis();
            for (String automationId : PhoneNotificationAutomation.fieldAutomationIds()) {
                automationStates.apply(AutomationContract.SCOPE_POPUP, automationId,
                        new JSONObject().put("text", "").put("visible", false)
                                .put("fresh", false).put("updated_at", now));
                onAutomationStateChanged(AutomationContract.SCOPE_POPUP, automationId);
            }
        } catch (JSONException | RuntimeException failure) {
            Log.w(TAG, "Could not clear phone notification fields", failure);
        }
    }

    private boolean showPhoneLowBatteryStatus(int level, @NonNull String color) {
        boolean replacingPresentation = hasActivePhoneStatusAlert();
        if (!replacingPresentation && binding != null) {
            mediaDurationVisibilityBeforePhoneNotification =
                    binding.mediaDurationText.getVisibility();
            mediaProgressVisibilityBeforePhoneNotification =
                    binding.mediaProgressBar.getVisibility();
        }
        if (binding != null) binding.mediaTitleText.setMarqueeText("");
        activePhoneNotification = null;
        activePhoneNotificationFields = Collections.emptySet();
        activePhoneBatteryAlertText =
                getString(R.string.phone_low_battery_alert_text, level);
        activePhoneBatteryAlertColor = color;
        clearPhoneNotificationFieldsIfInactive();
        schedulePhoneStatusAlert();
        if (phoneNotificationBurstActive && !queuedPhoneNotifications.isEmpty()) {
            schedulePhoneNotificationQueueAdvanceAfter(
                    activePhoneLowBatteryRemaining());
        }
        return true;
    }

    /**
     * Publishes the low-battery warning into the configured icon notification card. The caller
     * applies the same popup destination switch used by ordinary ANCS notifications.
     */
    private boolean showPhoneLowBatteryPopup(int level) {
        if (automationStates == null || prefs == null) return false;
        try {
            ensurePhoneNotificationPopupConfigured();
            if (!phoneNotificationPopupConfigured) return false;
            applyPopupPreferencesSafely();
            int seconds = Math.max(1, Math.min(120,
                    prefs.phoneStatusBarNotificationSeconds.get()));
            long now = System.currentTimeMillis();
            long expiresAt = phoneNotificationOverlayPaused
                    ? 0L : now + seconds * 1_000L;
            String[] text = new String[]{
                    getString(R.string.phone_low_battery_popup_application),
                    getString(R.string.phone_low_battery_popup_title),
                    getString(R.string.phone_low_battery_popup_body, level)
            };
            String[] ids = new String[]{
                    PhoneNotificationAutomation.APPLICATION_AUTOMATION_ID,
                    PhoneNotificationAutomation.TOPIC_AUTOMATION_ID,
                    PhoneNotificationAutomation.TEXT_AUTOMATION_ID
            };
            for (int index = 0; index < ids.length; index++) {
                JSONObject field = new JSONObject()
                        .put("text", text[index])
                        .put("visible", true)
                        .put("fresh", true)
                        .put("source", "phone-low-battery")
                        .put("updated_at", now)
                        .put("expires_at", expiresAt);
                if (index == 0) {
                    field.put("icon", PhoneNotificationAutomation.LOW_BATTERY_ICON_ID);
                }
                automationStates.apply(AutomationContract.SCOPE_POPUP, ids[index], field);
                onAutomationStateChanged(AutomationContract.SCOPE_POPUP, ids[index]);
            }
            automationStates.apply(AutomationContract.SCOPE_OVERLAY,
                    PhoneNotificationAutomation.OVERLAY_ID,
                    new JSONObject().put("visible", false).put("fresh", false)
                            .put("updated_at", now));
            onAutomationStateChanged(AutomationContract.SCOPE_OVERLAY,
                    PhoneNotificationAutomation.OVERLAY_ID);
            automationStates.apply(AutomationContract.SCOPE_OVERLAY,
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID,
                    new JSONObject().put("visible", true).put("fresh", true)
                            .put("source", "phone-low-battery")
                            .put("updated_at", now).put("expires_at", expiresAt));
            onAutomationStateChanged(AutomationContract.SCOPE_OVERLAY,
                    PhoneNotificationAutomation.OVERLAY_WITH_ICON_ID);
            if (phoneNotificationOverlayPaused) {
                pausedPhonePopupRemainingMs = seconds * 1_000L;
                activePhonePopupNotificationExpiresAt = Long.MAX_VALUE;
            } else {
                activePhonePopupNotificationExpiresAt =
                        SystemClock.elapsedRealtime() + seconds * 1_000L;
            }
            activePhoneLowBatteryPopup = true;
            activePhonePopupNotification = null;
            mainHandler.removeCallbacks(phonePopupNotificationExpiry);
            if (!phoneNotificationOverlayPaused) {
                mainHandler.postDelayed(phonePopupNotificationExpiry, seconds * 1_000L);
            }
            schedulePopupRefresh();
            return true;
        } catch (JSONException | RuntimeException failure) {
            Log.e(TAG, "Could not present low-phone-battery popup", failure);
            return false;
        }
    }

    private void schedulePhoneStatusAlert() {
        int seconds = Math.max(1, Math.min(120,
                prefs.phoneStatusBarNotificationSeconds.get()));
        if (phoneNotificationOverlayPaused) {
            pausedPhoneNotificationRemainingMs = seconds * 1_000L;
            activePhoneNotificationExpiresAt = Long.MAX_VALUE;
        } else {
            activePhoneNotificationExpiresAt = android.os.SystemClock.elapsedRealtime()
                    + seconds * 1_000L;
        }
        mainHandler.removeCallbacks(phoneNotificationExpiry);
        if (!phoneNotificationOverlayPaused) {
            mainHandler.postDelayed(phoneNotificationExpiry, seconds * 1_000L);
        }
        if (binding != null) updateMediaInfo();
    }

    private boolean hasActivePhoneStatusAlert() {
        return activePhoneNotification != null
                || !TextUtils.isEmpty(activePhoneBatteryAlertText);
    }

    private boolean isPhoneNotificationActive() {
        if (!hasActivePhoneStatusAlert() || prefs == null) return false;
        if (android.os.SystemClock.elapsedRealtime()
                >= activePhoneNotificationExpiresAt) return false;
        if (activePhoneBatteryAlertText != null) {
            return prefs.phoneLowBatteryAlertEnabled.get()
                    && prefs.phoneStatusBarNotificationsEnabled.get();
        }
        return prefs.phoneStatusBarNotificationsEnabled.get()
                && !activePhoneNotificationText().isEmpty();
    }

    private void clearPhoneStatusNotification(boolean restoreMediaVisibility) {
        mainHandler.removeCallbacks(phoneNotificationExpiry);
        activePhoneNotification = null;
        activePhoneNotificationFields = Collections.emptySet();
        activePhoneBatteryAlertText = null;
        activePhoneBatteryAlertColor = null;
        activePhoneNotificationExpiresAt = 0L;
        pausedPhoneNotificationRemainingMs = 0L;
        if (binding != null) {
            // Clearing the alert must also stop the custom marquee when MEDIA is disabled or
            // removed. In that path updateMediaInfo() is intentionally not called.
            binding.mediaAppText.setMarqueeText("");
            binding.mediaTitleText.setMarqueeText("");
            binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get());
            binding.mediaTitleText.setTextColor(
                    ContextCompat.getColor(themedContext == null ? this : themedContext,
                            R.color.text_primary));
        }
        if (restoreMediaVisibility && binding != null) {
            binding.mediaDurationText.setVisibility(
                    mediaDurationVisibilityBeforePhoneNotification);
            binding.mediaProgressBar.setVisibility(
                    mediaProgressVisibilityBeforePhoneNotification);
        }
        clearPhoneNotificationFieldsIfInactive();
    }

    @NonNull
    private String activePhoneNotificationText() {
        if (!TextUtils.isEmpty(activePhoneBatteryAlertText)) {
            return activePhoneBatteryAlertText;
        }
        PhoneStatusBarPolicy.NotificationPresentation presentation =
                activePhoneNotification;
        if (presentation == null) return "";
        Set<String> visibleFields = new LinkedHashSet<>();
        for (String fieldId : activePhoneNotificationFields) {
            String value = PhoneStatusBarPolicy.notificationFieldText(
                    presentation, fieldId);
            if (value.isEmpty()) continue;
            String automationId =
                    PhoneNotificationAutomation.automationIdForField(fieldId);
            boolean visible = automationStates == null
                    || automationStates.effectiveVisibility(
                    AutomationContract.SCOPE_POPUP, automationId, true);
            if (visible) visibleFields.add(fieldId);
        }
        return PhoneStatusBarPolicy.notificationText(presentation, visibleFields);
    }

    /**
     * Temporarily reuses the configured Now Playing geometry. This is presentation-only:
     * MediaSession callbacks and playback continue while the ANCS text occupies the rows.
     */
    private void renderPhoneStatusNotification() {
        if (binding == null || !isPhoneNotificationActive()) return;

        stopMediaProgressTicker();
        binding.mediaStateIcon.setVisibility(View.GONE);
        binding.mediaDurationText.setVisibility(View.GONE);
        binding.mediaProgressBar.setVisibility(View.GONE);

        binding.mediaSourceRow.setVisibility(View.GONE);
        binding.mediaTitleRow.setVisibility(View.VISIBLE);
        binding.mediaAppText.setMarqueeText("");
        binding.mediaTitleText.setMarqueeEnabled(true);
        int defaultColor = ContextCompat.getColor(
                themedContext == null ? this : themedContext, R.color.text_primary);
        String configuredColor = activePhoneBatteryAlertText != null
                ? (TextUtils.isEmpty(activePhoneBatteryAlertColor)
                ? prefs.phoneLowBatteryAlertColor.get() : activePhoneBatteryAlertColor)
                : prefs.phoneStatusBarNotificationColor.get();
        binding.mediaTitleText.setTextColor(
                AutomationState.parseColor(configuredColor, defaultColor));
        binding.mediaTitleText.setMarqueeText(activePhoneNotificationText());

        LinearLayout.LayoutParams titleLayout =
                (LinearLayout.LayoutParams) binding.mediaTitleRow.getLayoutParams();
        if (titleLayout.topMargin != 0) {
            titleLayout.topMargin = 0;
            binding.mediaTitleRow.setLayoutParams(titleLayout);
        }

        // Visibility remains solely owned by applyBrickVisibility(). In particular, the
        // notification may not bypass remote visibility or the current app's hide rule.
        schedulePopupRefresh();
    }

    private void updateMediaInfo() {
        if (binding == null) return;
        if (isPhoneNotificationActive()) {
            renderPhoneStatusNotification();
            return;
        }
        // Restore every view property temporarily changed by the ANCS presentation before
        // rendering the current MediaSession snapshot.
        binding.mediaStateIcon.setVisibility(
                prefs.media.showPlaybackStateIcon.get() ? View.VISIBLE : View.GONE);
        binding.mediaTitleText.setMarqueeEnabled(prefs.media.marqueeEnabled.get());
        boolean mainMediaRequested = currentBrickSet().contains(BrickType.MEDIA)
                && isRemotelyVisible(BrickType.MEDIA);
        boolean mainMediaHidden = mainMediaRequested && isBrickHiddenByApp(BrickType.MEDIA);
        boolean popupMediaRequested = isPopupBuiltinRequested(BrickType.MEDIA);
        boolean driverMediaRequested =
                driverInformationBrickTypes().contains(BrickType.MEDIA);
        MediaController playing = pickActiveMediaController();
        PlaybackState playbackState = playing == null ? null : playing.getPlaybackState();
        boolean musicPresentationVisible = StatusMediaVisibilityPolicy.hasVisibleContent(
                false,
                playing != null,
                isActuallyPlaying(playbackState),
                prefs.media.onlyWhilePlaying.get());
        boolean mainMediaKeepsSpace = musicPresentationVisible && mainMediaHidden
                && prefs.hideKeepsSpaceFor(BrickType.MEDIA).get();
        boolean mainMediaVisible = musicPresentationVisible
                && mainMediaRequested && !mainMediaHidden;
        if (!mainMediaVisible && !mainMediaKeepsSpace
                && !popupMediaRequested && !driverMediaRequested) {
            binding.mediaContainer.setVisibility(View.GONE);
            stopMediaProgressTicker();
            binding.mediaAppText.setMarqueeText("");
            binding.mediaTitleText.setMarqueeText("");
            lastMediaSubtitle = null;
            schedulePopupRefresh();
            return;
        }
        if (playing == null) {
            binding.mediaContainer.setVisibility(View.GONE);
            stopMediaProgressTicker();
            binding.mediaAppText.setMarqueeText("");
            binding.mediaTitleText.setMarqueeText("");
            lastMediaSubtitle = null;
            schedulePopupRefresh();
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        String title = pickMediaTitle(metadata);
        String artist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (isUnknownArtistPlaceholder(artist)) {
            // Some players (notably stock Android Music) fill the artist field with a literal
            // "Unknown artist" / "Неизвестный исполнитель" string when the tag is missing.
            // Treat that as no artist so the subtitle falls back to the title alone.
            artist = null;
        }
        String subtitle;
        boolean titleFirst = prefs.media.titleFirst.get();
        String first = titleFirst ? title : artist;
        String second = titleFirst ? artist : title;
        if (!isEmpty(first) && !isEmpty(second)) {
            subtitle = first + " — " + second;
        } else if (!isEmpty(title)) {
            subtitle = title;
        } else if (!isEmpty(artist)) {
            subtitle = artist;
        } else {
            // Something is playing but the player exposes no metadata at all — at least show a
            // placeholder so the user can see that media playback is active.
            subtitle = getString(R.string.media_unknown_track);
        }
        if (playbackState != null
                && (playbackState.getState() == PlaybackState.STATE_PLAYING
                || playbackState.getState() == PlaybackState.STATE_PAUSED)) {
            MediaPlaybackHistoryStore.record(this, playing.getPackageName(),
                    playbackState.getState() == PlaybackState.STATE_PLAYING);
        }
        // Pause shape only for an actual PAUSED; transient states (buffering / seeking) keep the
        // play shape so the icon doesn't flicker every time the user scrubs.
        // Players republish PlaybackState continuously (Yandex Music every second), and
        // TextView.setText unconditionally drops its layout and requests a full re-layout even
        // for identical text. On OEM head units that per-second layout storm makes the whole
        // title row visibly jitter while the marquee scrolls — so every setter here must be
        // a no-op when the value didn't actually change (MediaStateIconView.setPaused is).
        binding.mediaStateIcon.setPaused(playbackState != null
                && playbackState.getState() == PlaybackState.STATE_PAUSED);
        binding.mediaAppText.setMarqueeText(getAppLabel(playing.getPackageName()));
        // Reconcile the row structure too: settings may have changed while a controller callback
        // was queued. The same method already ran during applyPreferences(), so playback starting
        // cannot be the first event that establishes the configured widget height.
        applyMediaLineStructure();
        binding.mediaTitleText.setMarqueeText(subtitle);
        syncMediaProgressWidth();

        // Duration: format ms → "M:SS" / "H:MM:SS". Hidden when the user opted out or the
        // player doesn't expose a positive duration (live streams, podcast pre-buffer).
        long durationMs = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        // Track identity: duration/progress visibility may only COLLAPSE on a real track
        // change. Players republish metadata continuously (Yandex Music: every second) and
        // the duration is transiently absent in some republishes — hiding on those blips
        // collapsed the row height once a second, which read as the whole widget "regrouping"
        // while the marquee scrolls.
        boolean trackChanged = !TextUtils.equals(subtitle, lastMediaSubtitle);
        lastMediaSubtitle = subtitle;

        if (!prefs.media.showDuration.get()) {
            binding.mediaDurationText.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            // Leading space gives the gap between title and duration without an extra layout
            // margin pref — scales naturally with the duration font size.
            setTextIfChanged(binding.mediaDurationText, " " + formatTrackDuration(durationMs));
            binding.mediaDurationText.setVisibility(View.VISIBLE);
        } else if (trackChanged) {
            // New track with no usable duration (live stream) — hide for real.
            binding.mediaDurationText.setVisibility(View.GONE);
        }
        // else: transient blip on the same track — keep the last shown value.

        // Progress bar visibility is decided here ONLY (updateMediaProgress never touches it —
        // see the comment there). Same blip-tolerant policy as the duration text.
        if (!prefs.media.progressBarEnabled.get()) {
            binding.mediaProgressBar.setVisibility(View.GONE);
        } else if (durationMs > 0L) {
            if (binding.mediaProgressBar.getVisibility() != View.VISIBLE) {
                binding.mediaProgressBar.setColor(
                        ContextCompat.getColor(themedContext != null ? themedContext : this,
                                R.color.text_primary));
                binding.mediaProgressBar.setVisibility(View.VISIBLE);
            }
        } else if (trackChanged) {
            binding.mediaProgressBar.setVisibility(View.GONE);
        }

        if (mainMediaKeepsSpace && !mainMediaVisible) {
            // A session can begin while the current foreground app is excluded. In that case
            // applyBrickVisibility previously saw no active controller and left the view GONE
            // with normal alpha; establish the keep-space alpha before revealing its layout.
            binding.mediaContainer.setAlpha(0f);
        }
        binding.mediaContainer.setVisibility(
                mainMediaVisible || mainMediaKeepsSpace ? View.VISIBLE : View.GONE);

        updateMediaProgress(playing);
        schedulePopupRefresh();
    }

    /**
     * Keep the timeline under the actually rendered title, not under the whole title row.
     * The row may additionally contain duration and the play indicator, while marquee mode keeps
     * a second internal copy of the text. Neither must make the line longer than the visible song.
     */
    private void syncMediaProgressWidth() {
        if (binding == null) return;
        binding.mediaTitleText.post(() -> {
            if (binding == null) return;
            CharSequence source = binding.mediaTitleText.getMarqueeSourceText();
            float measured = binding.mediaTitleText.getPaint()
                    .measureText(source, 0, source.length());
            int viewportWidth = binding.mediaTitleText.getWidth();
            int targetWidth = MediaProgressWidthPolicy.width(
                    measured,
                    binding.mediaTitleText.getCompoundPaddingLeft(),
                    binding.mediaTitleText.getCompoundPaddingRight(),
                    viewportWidth);
            int leadingMargin = MediaProgressWidthPolicy.leadingMargin(
                    binding.mediaTitleText.getLeft(), binding.mediaTitleRow.getPaddingLeft());
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) binding.mediaProgressBar.getLayoutParams();
            if (params.width != targetWidth || params.getMarginStart() != leadingMargin) {
                params.width = targetWidth;
                params.setMarginStart(leadingMargin);
                binding.mediaProgressBar.setLayoutParams(params);
            }
        });
    }

    /**
     * Format a positive duration in milliseconds as {@code M:SS} (under an hour) or
     * {@code H:MM:SS} (one hour or longer). Locale-independent — uses the same digit forms
     * everywhere because the duration is displayed alongside the marquee subtitle, where
     * regional digit substitutions would look out of place.
     */
    private static String formatTrackDuration(long ms) {
        long totalSeconds = ms / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds);
    }

    /**
     * Snap the progress bar to the current playback position and arm/disarm the periodic ticker.
     * Called both from {@link #updateMediaInfo} (state/metadata flips) and from
     * {@link #mediaProgressTick} (once per second while playing) to advance the bar smoothly.
     */
    private void updateMediaProgress(@Nullable MediaController playing) {
        if (binding == null) return;
        // Visibility policy: this method NEVER changes the bar's visibility. Flipping
        // GONE/VISIBLE changes the media container's height and relayouts the whole brick
        // row — and players like Yandex Music republish state/metadata every second, with
        // the duration transiently missing, which turned that flip into a once-a-second
        // visible "regroup" of the row while the marquee scrolls. Visibility is decided
        // solely in updateMediaInfo (real track/state changes); here we only advance the
        // fill fraction — a pure repaint.
        if (!prefs.media.progressBarEnabled.get() || playing == null
                || binding.mediaProgressBar.getVisibility() != View.VISIBLE) {
            stopMediaProgressTicker();
            return;
        }
        MediaMetadata metadata = playing.getMetadata();
        long duration = metadata != null
                ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
                : 0L;
        PlaybackState state = playing.getPlaybackState();
        if (duration <= 0L || state == null) {
            // Timeline transiently unavailable (metadata republish in flight) — keep the last
            // rendered fill and let the next tick catch up rather than touching layout.
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        long lastUpdate = state.getLastPositionUpdateTime();
        long basePosition = state.getPosition();
        // PlaybackState.getPosition() returns the position as of getLastPositionUpdateTime();
        // for the *current* moment we extrapolate with the reported playback speed (typically 1.0).
        long actualPosition = basePosition
                + (long) ((now - lastUpdate) * state.getPlaybackSpeed());
        if (actualPosition < 0L) actualPosition = 0L;
        if (actualPosition > duration) actualPosition = duration;

        binding.mediaProgressBar.setProgress((float) actualPosition / (float) duration);

        if (state.getState() == PlaybackState.STATE_PLAYING) {
            // Re-arm — the new postDelayed replaces any previously queued one, idempotent.
            mainHandler.removeCallbacks(mediaProgressTick);
            mainHandler.postDelayed(mediaProgressTick, MEDIA_PROGRESS_TICK_MS);
        } else {
            stopMediaProgressTicker();
        }
    }

    private void stopMediaProgressTicker() {
        mainHandler.removeCallbacks(mediaProgressTick);
    }

    private final Runnable mediaProgressTick = () -> updateMediaProgress(pickActiveMediaController());

    /**
     * Best-effort extraction of a track title from the media metadata. Falls back through several
     * standard keys, then to the file name parsed out of the media URI, so we still show something
     * useful for players that don't populate {@link MediaMetadata#METADATA_KEY_TITLE}.
     */
    @Nullable
    private static String pickMediaTitle(@Nullable MediaMetadata metadata) {
        if (metadata == null) return null;
        String[] keys = {
                MediaMetadata.METADATA_KEY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_TITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION,
        };
        for (String key : keys) {
            String value = metadata.getString(key);
            if (!isEmpty(value)) return value;
        }
        String uriFilename = filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI));
        if (!isEmpty(uriFilename)) return uriFilename;
        return filenameFromUri(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID));
    }

    /**
     * Recognise the literal "Unknown artist" / "Неизвестный исполнитель" placeholders that
     * some players write into the artist field when the tag is missing — case-insensitive
     * and whitespace-tolerant.
     */
    private static boolean isUnknownArtistPlaceholder(@Nullable String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        return trimmed.equalsIgnoreCase("unknown artist")
                || trimmed.equalsIgnoreCase("неизвестный исполнитель");
    }

    @Nullable
    private static String filenameFromUri(@Nullable String raw) {
        if (isEmpty(raw)) return null;
        String last = null;
        try {
            android.net.Uri uri = android.net.Uri.parse(raw);
            last = uri.getLastPathSegment();
        } catch (Exception ignored) {
        }
        if (isEmpty(last)) {
            int slash = Math.max(raw.lastIndexOf('/'), raw.lastIndexOf('\\'));
            last = (slash >= 0 && slash < raw.length() - 1) ? raw.substring(slash + 1) : raw;
        }
        if (isEmpty(last)) return null;
        int dot = last.lastIndexOf('.');
        if (dot > 0) {
            last = last.substring(0, dot);
        }
        return android.net.Uri.decode(last);
    }

    @Nullable
    private MediaController pickActiveMediaController() {
        // Prefer a controller that is currently playing. If none is playing, fall back to any
        // controller in a transient "media is loaded and the user is doing something with it"
        // state — paused, buffering, fast-forwarding, rewinding, skipping. Keeping the brick
        // visible across these short-lived transitions avoids a VISIBLE→GONE→VISIBLE blink
        // (which would re-layout the title text from zero size and reset the marquee scroll)
        // every time the user seeks or the player briefly buffers.
        MediaController fallback = null;
        for (MediaController c : activeMediaControllers) {
            PlaybackState s = c.getPlaybackState();
            if (s == null) continue;
            int state = s.getState();
            if (state == PlaybackState.STATE_PLAYING) {
                return c;
            }
            if (fallback == null && isMediaActiveState(state)) {
                fallback = c;
            }
        }
        return fallback;
    }

    private static boolean isMediaActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_PAUSED:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
            case PlaybackState.STATE_CONNECTING:
                return true;
            default:
                return false;
        }
    }

    private static boolean isActuallyPlaying(@Nullable MediaController controller) {
        return controller != null && isActuallyPlaying(controller.getPlaybackState());
    }

    private static boolean isActuallyPlaying(@Nullable PlaybackState state) {
        return state != null && state.getState() == PlaybackState.STATE_PLAYING;
    }

    private String getAppLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(info);
            return label != null ? label.toString() : pkg;
        } catch (Exception e) {
            return pkg;
        }
    }

    private static boolean isEmpty(@Nullable String s) {
        return s == null || s.isEmpty();
    }

    private void registerSatelliteStatusReceiver() {
        if (satelliteReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(GNSSSHARE_SATELLITE_STATUS_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(satelliteStatusReceiver, filter, RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(satelliteStatusReceiver, filter);
            }
            satelliteReceiverRegistered = true;
        } catch (RuntimeException failure) {
            satelliteReceiverRegistered = false;
            Log.w(TAG, "Could not register satellite status receiver", failure);
        }
    }

    private void unregisterSatelliteStatusReceiver() {
        if (!satelliteReceiverRegistered) return;
        try {
            unregisterReceiver(satelliteStatusReceiver);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Satellite status receiver was already unregistered", failure);
        }
        satelliteReceiverRegistered = false;
        mainHandler.removeCallbacks(satellitesCountResetRunnable);
        satellitesCount = -1;
        gnssModeFlags = 0;
    }

    private void registerBluetoothReceiver() {
        if (btReceiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            registerReceiver(bluetoothReceiver, filter);
            btReceiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register Bluetooth receiver", t);
        }
    }

    private void unregisterBluetoothReceiver() {
        // Profile-proxy callbacks can outlive this receiver. Invalidate them before clearing the
        // status surface so a late callback cannot repopulate a stale connected-device set.
        bluetoothTrackingGeneration++;
        if (!btReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Bluetooth receiver was already unregistered", failure);
        }
        btReceiverRegistered = false;
    }

    @Nullable
    private static BluetoothAdapter getBluetoothAdapter() {
        try {
            return BluetoothAdapter.getDefaultAdapter();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Seed the connected-device set from whatever the system can synchronously tell us, with
     * an async profile-proxy refresh on top.
     * <p>
     * The synchronous path iterates {@link BluetoothAdapter#getBondedDevices()} and reflects on
     * the hidden {@code BluetoothDevice.isConnected()} method — this works on AOSP and the
     * typical car-HU ROMs derived from it, returns instantly, and crucially covers the
     * "brick was just added, BT is already on and the device is paired" case that pure
     * profile-proxy seeding misses.
     * <p>
     * The async path keeps querying HEADSET / A2DP proxies as a safety net for OEM ROMs where
     * the reflection trick is unavailable, and for unbonded but momentarily connected devices.
     * ACL_CONNECTED / ACL_DISCONNECTED broadcasts (registered separately) handle live updates
     * once the receiver is in place.
     */
    private void refreshBtConnectedFromProxies() {
        final int generation = ++bluetoothTrackingGeneration;
        btConnectedAddrs.clear();
        BluetoothAdapter adapter = getBluetoothAdapter();
        if (adapter == null) return;
        try {
            if (!adapter.isEnabled()) {
                btConnectedAddrs.clear();
                return;
            }
        } catch (Throwable t) {
            return;
        }

        seedConnectedFromBondedDevices(adapter);

        BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                boolean current = generation == bluetoothTrackingGeneration
                        && binding != null && prefs != null && prefs.widgetEnabled.get();
                if (current) {
                    try {
                        for (BluetoothDevice d : proxy.getConnectedDevices()) {
                            if (d != null && d.getAddress() != null) {
                                btConnectedAddrs.add(d.getAddress());
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    adapter.closeProfileProxy(profile, proxy);
                } catch (Throwable ignored) {
                }
                if (current) updateBluetoothStatus();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        };
        try {
            adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET);
            adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to query Bluetooth profile proxies", t);
        }
    }

    /**
     * Synchronously populate {@link #btConnectedAddrs} from bonded devices via the hidden
     * {@code BluetoothDevice.isConnected()} method. Safe to call repeatedly — the set is a
     * union, so a stale entry would only be cleared by the ACL_DISCONNECTED broadcast or by
     * a full Bluetooth-off transition.
     */
    private void seedConnectedFromBondedDevices(BluetoothAdapter adapter) {
        java.lang.reflect.Method isConnected;
        try {
            isConnected = BluetoothDevice.class.getMethod("isConnected");
        } catch (NoSuchMethodException nsm) {
            return;
        } catch (Throwable t) {
            return;
        }
        Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (Throwable t) {
            return;
        }
        if (bonded == null) return;
        for (BluetoothDevice device : bonded) {
            if (device == null || device.getAddress() == null) continue;
            try {
                Object result = isConnected.invoke(device);
                if (result instanceof Boolean && (Boolean) result) {
                    btConnectedAddrs.add(device.getAddress());
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void updateBluetoothStatus() {
        BluetoothAdapter adapter = getBluetoothAdapter();
        boolean enabled;
        try {
            enabled = adapter != null && adapter.isEnabled();
        } catch (Throwable t) {
            enabled = false;
        }
        BluetoothState newState;
        if (!enabled) {
            newState = BluetoothState.OFF;
            btConnectedAddrs.clear();
        } else if (btConnectedAddrs.isEmpty()) {
            newState = BluetoothState.NO_DEVICE;
        } else {
            newState = BluetoothState.CONNECTED;
        }
        bluetoothState = newState;
        if (binding != null) {
            updateIconStatus(ICON_TYPE_BT, binding.bluetoothStatusIcon, bluetoothState.ordinal());
            PhoneBluetoothIndicatorPolicy.Appearance phoneAppearance =
                    PhoneBluetoothIndicatorPolicy.resolve(
                            bluetoothState == BluetoothState.CONNECTED,
                            hasSelectedPhoneConfiguration(),
                            isPhoneNotificationPathAvailable());
            if (phoneAppearance != PhoneBluetoothIndicatorPolicy.Appearance.DEFAULT) {
                // One flat SF-style rune and one tint. ANCS readiness is intentionally not
                // encoded by a separately coloured body/outline anymore.
                binding.bluetoothStatusIcon.setImageResource(
                        R.drawable.ic_status_iphone_bluetooth_solid);
                binding.bluetoothStatusIcon.setDrawIcon(true);
                Context context = themedContext == null ? this : themedContext;
                ImageViewCompat.setImageTintList(binding.bluetoothStatusIcon,
                        ColorStateList.valueOf(ContextCompat.getColor(
                                context, R.color.status_bluetooth)));
                binding.bluetoothStatusIcon.setOutlineWidth(0);
            }
        }
        schedulePopupRefresh();
    }

    private boolean isPhoneConnectorLinkPresent() {
        ConnectorValue connected = phoneStatusValues.get("connected");
        return connected != null && connected.fresh && connected.available
                && connected.readable && Boolean.TRUE.equals(connected.rawValue);
    }

    private boolean hasSelectedPhoneConfiguration() {
        return prefs != null && !prefs.phoneDeviceAddress.get().trim().isEmpty();
    }

    /** A live ANCS subscription and a currently readable notification feed are both required. */
    private boolean isPhoneNotificationPathAvailable() {
        ConnectorValue profile = phoneStatusValues.get("profiles.ancs");
        boolean profileActive = profile != null && profile.fresh && profile.available
                && profile.readable && Boolean.TRUE.equals(profile.rawValue);
        ConnectorValue notifications = phoneStatusValues.get("notifications.items");
        boolean feedActive = notifications != null && notifications.fresh
                && notifications.available && notifications.readable;
        return phoneAncsReady && profileActive && feedActive;
    }

    private void updateForegroundAppTracking() {
        boolean phoneTrackingNeeded = phoneNotificationForegroundTrackingNeeded();
        boolean vehicleOverlayTrackingNeeded = prefs != null
                && prefs.phoneNotificationDelayInAppsEnabled.get()
                && prefs.phoneNotificationDelayForExternalOverlays.get();
        boolean shadeSafetyTrackingNeeded = prefs != null && prefs.systemShadeEnabled.get();
        if (!vehicleOverlayTrackingNeeded && !shadeSafetyTrackingNeeded) {
            phoneVehicleOverlayActive = false;
        }
        recomputePhoneExternalOverlayActive();
        try {
            reconcileCarExternalOverlayListener(CarIntegrations.get(this));
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not update vehicle overlay tracking", failure);
        }
        boolean surfaceVisibilityNeeded = StatusBarSurfaceContext.requiresPackageTracking(
                hiddenInPackages) || anyBrickNeedsPackageTracking();
        if (binding == null && !phoneTrackingNeeded && !surfaceVisibilityNeeded) {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            return;
        }
        boolean needTracking = surfaceVisibilityNeeded || phoneTrackingNeeded;
        boolean accessibilityActive = WidgetAccessibilityService.getInstance() != null;
        boolean usageGranted = Permissions.isUsageAccessGranted(this);
        // Two paths to the foreground package:
        //   - AccessibilityService (preferred): per-display data, multi-display safe.
        //   - UsageStatsManager (fallback): global, single foreground across all displays.
        // We only poll when neither path is being driven by events: the accessibility service
        // pushes via {@link #onForegroundDisplayMapUpdated()}, no polling needed.
        boolean shouldPoll = needTracking && !accessibilityActive && usageGranted;
        if (needTracking && (accessibilityActive || usageGranted)) {
            if (usageGranted && usageStatsManager == null) {
                usageStatsManager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            }
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            if (shouldPoll) {
                mainHandler.post(foregroundAppCheckRunnable);
            }
            // If accessibility just connected, recompute once now — we won't get an event
            // until something actually changes on a display.
            if (accessibilityActive) {
                safeCheckForegroundApp("tracking path changed");
            }
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            String fallbackPackage = StatusBarSurfaceContext.isLauncherHomeForeground()
                    ? getPackageName() : null;
            boolean packageChanged = !Objects.equals(
                    lastForegroundPackage, fallbackPackage);
            lastForegroundPackage = fallbackPackage;
            // A synthetic HOME-only rule is fully event-driven and deliberately requires no
            // package tracker, but it still owns current visibility during initial service bind.
            applyOverlayVisibility(matchesForegroundContext(hiddenInPackages));
            if (packageChanged) onPhoneNotificationForegroundChanged();
        }
    }

    /**
     * Called by {@link WidgetAccessibilityService} when the per-display foreground map changes.
     * Recomputes visibility based on the package on <i>our</i> display.
     */
    public void onForegroundDisplayMapUpdated() {
        if (ecarxNavigatorWindowObserver != null) {
            ecarxNavigatorWindowObserver.refresh("accessibility-display-map");
        }
        mainHandler.post(() -> safeCheckForegroundApp("display map update"));
    }

    private void onVehicleExternalOverlayChanged(boolean active) {
        mainHandler.post(() -> {
            if (destroyed) return;
            phoneVehicleOverlayActive = active;
            SystemShadeService.setVehicleOverlayActive(active);
            recomputePhoneExternalOverlayActive();
        });
    }

    private void reconcileCarExternalOverlayListener(@NonNull CarIntegration car) {
        boolean phoneNeeded = prefs != null
                && prefs.phoneNotificationDelayInAppsEnabled.get()
                && prefs.phoneNotificationDelayForExternalOverlays.get();
        boolean shadeNeeded = prefs != null && prefs.systemShadeEnabled.get();
        boolean needed = phoneNeeded || shadeNeeded;
        car.setExternalOverlayListener(needed ? phoneVehicleOverlayListener : null);
        phoneVehicleOverlayListenerInstalled = needed;
        if (!needed) {
            phoneVehicleOverlayActive = false;
            SystemShadeService.setVehicleOverlayActive(false);
            recomputePhoneExternalOverlayActive();
        }
    }

    private void recomputePhoneExternalOverlayActive() {
        setPhoneExternalOverlayActive(phoneVehicleOverlayActive);
    }

    private void setPhoneExternalOverlayActive(boolean active) {
        boolean changed = phoneExternalOverlayActive != active;
        phoneExternalOverlayActive = active;
        syncPhoneNotificationExternalOverlayPause();
        if (changed) onPhoneNotificationForegroundChanged();
    }

    /** Event-driven lifecycle update for non-package targets such as our HOME Activity. */
    public void onForegroundSurfaceContextChanged() {
        boolean beganOptimisticConfirmation = false;
        NavigatorWindowSourcePolicy.OptimisticAction optimisticAction =
                NavigatorWindowSourcePolicy.optimisticActionAfterSurfaceChange(
                        ecarxNavigatorOptimisticConfirmationPending,
                        StatusBarSurfaceContext.isNavigatorWindowForeground(),
                        StatusBarSurfaceContext.isNavigatorWindowOptimistic());
        switch (optimisticAction) {
            case START_OR_RESTART:
                // A fresh successful startActivity hand-off owns a fresh bounded grace. The
                // following exact TransparentSplash a11y event converts the fallback token but
                // deliberately leaves this independent vendor confirmation running.
                beginEcarxNavigatorOptimisticConfirmation();
                beganOptimisticConfirmation = true;
                break;
            case CANCEL:
                cancelEcarxNavigatorOptimisticConfirmation();
                break;
            case KEEP:
            case IDLE:
            default:
                break;
        }
        if (!beganOptimisticConfirmation && ecarxNavigatorWindowObserver != null) {
            // The app-owned launch/focus token is intentionally optimistic. Reconcile it with the
            // real vendor frame after every transition, even on firmware that omits callbacks.
            ecarxNavigatorWindowObserver.refresh("surface-context");
        }
        recomputeForegroundSurfacePresentation();
    }

    /** Applies both root and per-element rules without recursively requesting another snapshot. */
    private void recomputeForegroundSurfacePresentation() {
        Runnable update = () -> {
            boolean packageChanged = false;
            if (StatusBarSurfaceContext.isLauncherHomeForeground()
                    && !effectiveNavigatorWindowForeground()) {
                // API 28 deliberately ignores our own accessibility events. Replace a closed
                // freeform Navigator's stale package immediately when HOME regains focus.
                packageChanged = !getPackageName().equals(lastForegroundPackage);
                lastForegroundPackage = getPackageName();
            }
            applyOverlayVisibility(matchesForegroundContext(hiddenInPackages));
            if (packageChanged) onPhoneNotificationForegroundChanged();
            if (binding != null) {
                renderHomeAssistantBricks();
                applyBrickVisibility(currentBrickSet());
            }
        };
        // Launcher lifecycle callbacks already run on main. Applying inline avoids a one-loop
        // flash of stale visibility while preserving a safe path for any future non-UI caller.
        if (Looper.myLooper() == Looper.getMainLooper()) update.run();
        else mainHandler.post(update);
    }

    /**
     * Called by {@link WidgetAccessibilityService} when its connection state flips — connect
     * or disconnect. Re-evaluates which foreground-tracking pipeline to use (accessibility
     * push vs. UsageStats poll).
     */
    public void onForegroundTrackingPathChanged() {
        mainHandler.post(() -> safeUpdateForegroundAppTracking("accessibility state"));
    }

    /** Vendor geometry is authoritative when available; UNKNOWN preserves the safe fallback. */
    private void onEcarxNavigatorWindowStateChanged(
            @NonNull NavigatorWindowFramePolicy.Result result) {
        if (destroyed) return;
        NavigatorWindowSourcePolicy.VendorDecision decision =
                NavigatorWindowSourcePolicy.decisionFor(
                        result, ecarxNavigatorOptimisticConfirmationPending,
                        ecarxNavigatorWindowDecision);
        mainHandler.removeCallbacks(ecarxNavigatorWindowLeaseExpiry);
        if (decision == NavigatorWindowSourcePolicy.VendorDecision.NONE) {
            ecarxNavigatorWindowDecision = decision;
            ecarxNavigatorWindowDecisionAtElapsed = -1L;
            recomputeForegroundSurfacePresentation();
            return;
        }

        cancelEcarxNavigatorOptimisticConfirmation();
        ecarxNavigatorWindowDecision = decision;
        ecarxNavigatorWindowDecisionAtElapsed = SystemClock.elapsedRealtime();
        mainHandler.postDelayed(ecarxNavigatorWindowLeaseExpiry,
                ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS + 1L);

        // startActivity's optimistic token led us here, but it must not outlive the independent
        // vendor lease. Transfer ownership by consuming only the pre-existing fallback assertion;
        // a later accessibility/lifecycle event remains free to publish a fresh fallback.
        StatusBarSurfaceContext.consumeNavigatorWindowFallback();
        recomputeForegroundSurfacePresentation();
    }

    private void beginEcarxNavigatorOptimisticConfirmation() {
        ecarxNavigatorOptimisticConfirmationPending = true;
        ecarxNavigatorOptimisticStartedAtElapsed = SystemClock.elapsedRealtime();
        ecarxNavigatorOptimisticRetryIndex = 0;
        mainHandler.removeCallbacks(ecarxNavigatorOptimisticRetry);
        mainHandler.removeCallbacks(ecarxNavigatorOptimisticExpiry);
        mainHandler.removeCallbacks(ecarxNavigatorWindowLeaseExpiry);
        ecarxNavigatorWindowDecision = NavigatorWindowSourcePolicy.VendorDecision.NONE;
        ecarxNavigatorWindowDecisionAtElapsed = -1L;
        mainHandler.post(ecarxNavigatorOptimisticRetry);
        mainHandler.postDelayed(ecarxNavigatorOptimisticExpiry,
                ECARX_NAVIGATOR_OPTIMISTIC_GRACE_MS + 1L);
    }

    private void runEcarxNavigatorOptimisticRetry() {
        if (destroyed || !ecarxNavigatorOptimisticConfirmationPending) return;
        long age = SystemClock.elapsedRealtime() - ecarxNavigatorOptimisticStartedAtElapsed;
        if (age < 0L || age > ECARX_NAVIGATOR_OPTIMISTIC_GRACE_MS) return;
        if (ecarxNavigatorOptimisticRetryIndex
                >= ECARX_NAVIGATOR_OPTIMISTIC_RETRY_OFFSETS_MS.length) return;
        int attempt = ecarxNavigatorOptimisticRetryIndex++;
        if (ecarxNavigatorWindowObserver != null) {
            ecarxNavigatorWindowObserver.refresh("optimistic-confirmation-" + attempt);
        }
        if (ecarxNavigatorOptimisticRetryIndex
                < ECARX_NAVIGATOR_OPTIMISTIC_RETRY_OFFSETS_MS.length) {
            long nextOffset = ECARX_NAVIGATOR_OPTIMISTIC_RETRY_OFFSETS_MS[
                    ecarxNavigatorOptimisticRetryIndex];
            mainHandler.postDelayed(ecarxNavigatorOptimisticRetry,
                    Math.max(1L, nextOffset - age));
        }
    }

    private void cancelEcarxNavigatorOptimisticConfirmation() {
        ecarxNavigatorOptimisticConfirmationPending = false;
        ecarxNavigatorOptimisticStartedAtElapsed = -1L;
        ecarxNavigatorOptimisticRetryIndex = 0;
        mainHandler.removeCallbacks(ecarxNavigatorOptimisticRetry);
        mainHandler.removeCallbacks(ecarxNavigatorOptimisticExpiry);
    }

    private void expireEcarxNavigatorOptimisticConfirmation() {
        if (destroyed || !ecarxNavigatorOptimisticConfirmationPending) return;
        long age = SystemClock.elapsedRealtime() - ecarxNavigatorOptimisticStartedAtElapsed;
        if (age >= 0L && age <= ECARX_NAVIGATOR_OPTIMISTIC_GRACE_MS) {
            mainHandler.postDelayed(ecarxNavigatorOptimisticExpiry,
                    ECARX_NAVIGATOR_OPTIMISTIC_GRACE_MS - age + 1L);
            return;
        }
        cancelEcarxNavigatorOptimisticConfirmation();
        boolean consumedLaunchAssertion =
                StatusBarSurfaceContext.consumeNavigatorWindowOptimistic();
        if (!consumedLaunchAssertion
                && StatusBarSurfaceContext.isNavigatorWindowForeground()
                && ecarxNavigatorWindowObserver != null) {
            // TransparentSplash a11y may already have converted the launch marker into an exact
            // fallback assertion. At the end of the vendor grace, actively re-query once more so
            // two confirmed ABSENT samples can bound that fallback too; UNKNOWN still fails back
            // to the exact accessibility evidence.
            ecarxNavigatorWindowObserver.refresh("optimistic-expiry");
        }
        try {
            DiagnosticJournal.warn("navigator-window", "optimistic confirmation expired");
        } catch (RuntimeException | LinkageError ignored) {}
        recomputeForegroundSurfacePresentation();
    }

    private boolean effectiveNavigatorWindowForeground() {
        return NavigatorWindowSourcePolicy.effectiveWindow(
                StatusBarSurfaceContext.isNavigatorWindowForeground(),
                ecarxNavigatorWindowDecision, ecarxNavigatorWindowDecisionAtElapsed,
                SystemClock.elapsedRealtime(), ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS);
    }

    private boolean hasLiveEcarxNavigatorWindowConfirmation() {
        return ecarxNavigatorWindowDecision
                == NavigatorWindowSourcePolicy.VendorDecision.WINDOWED
                && NavigatorWindowSourcePolicy.isLive(
                        ecarxNavigatorWindowDecision, ecarxNavigatorWindowDecisionAtElapsed,
                        SystemClock.elapsedRealtime(), ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS);
    }

    /** Active expiry is required: with a dead Binder there may be no event to trigger a lazy read. */
    private void expireEcarxNavigatorWindowLease() {
        if (destroyed || ecarxNavigatorWindowDecision
                == NavigatorWindowSourcePolicy.VendorDecision.NONE) return;
        long now = SystemClock.elapsedRealtime();
        long age = now - ecarxNavigatorWindowDecisionAtElapsed;
        if (age >= 0L && age <= ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS) {
            mainHandler.postDelayed(ecarxNavigatorWindowLeaseExpiry,
                    ECARX_NAVIGATOR_CONFIRMATION_LEASE_MS - age + 1L);
            return;
        }
        boolean expiredWindow = ecarxNavigatorWindowDecision
                == NavigatorWindowSourcePolicy.VendorDecision.WINDOWED;
        ecarxNavigatorWindowDecision = NavigatorWindowSourcePolicy.VendorDecision.NONE;
        ecarxNavigatorWindowDecisionAtElapsed = -1L;
        try {
            if (expiredWindow) {
                DiagnosticJournal.debug("navigator-window",
                        "windowed vendor confirmation lease expired");
            } else {
                DiagnosticJournal.debug("navigator-window",
                        "non-windowed vendor confirmation lease expired");
            }
        } catch (RuntimeException | LinkageError ignored) {}
        recomputeForegroundSurfacePresentation();
    }

    private void safeCheckForegroundApp(@NonNull String operation) {
        try {
            checkForegroundApp();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            reportForegroundFailure(operation, failure);
        }
    }

    private void safeUpdateForegroundAppTracking(@NonNull String operation) {
        try {
            updateForegroundAppTracking();
        } catch (RuntimeException | LinkageError | OutOfMemoryError failure) {
            reportForegroundFailure(operation, failure);
        }
    }

    private void reportForegroundFailure(@NonNull String operation,
                                         @NonNull Throwable failure) {
        if (failure instanceof OutOfMemoryError) return;
        long now = SystemClock.elapsedRealtime();
        if (lastForegroundFailureLogElapsed != 0L
                && now - lastForegroundFailureLogElapsed < FOREGROUND_FAILURE_LOG_INTERVAL_MS) {
            return;
        }
        lastForegroundFailureLogElapsed = now;
        String detail = operation + " rejected " + failure.getClass().getSimpleName();
        try { Log.w(TAG, detail); }
        catch (RuntimeException | LinkageError ignored) {}
        try { DiagnosticJournal.warn("navigator-foreground", detail); }
        catch (RuntimeException | LinkageError ignored) {}
    }

    private void checkForegroundApp() {
        if (!StatusBarSurfaceContext.requiresPackageTracking(hiddenInPackages)
                && !anyBrickNeedsPackageTracking()
                && !phoneNotificationForegroundTrackingNeeded()) return;

        WidgetAccessibilityService a11y = WidgetAccessibilityService.getInstance();
        String latestPackage;
        if (a11y != null) {
            // Display-aware: look up the foreground package on our overlay's display only.
            // If the accessibility framework hasn't reported anything for that display yet,
            // fall through to the UsageStats path so we're not blind on first start.
            int myDisplayId = currentOverlayDisplayId();
            latestPackage = a11y.getForegroundPackageOnDisplay(myDisplayId);
            if (latestPackage == null && usageStatsManager != null
                    && Permissions.isUsageAccessGranted(this)) {
                latestPackage = latestPackageFromUsageStats();
            }
        } else {
            // Global path — works on single-display devices.
            if (usageStatsManager == null) return;
            if (!Permissions.isUsageAccessGranted(this)) {
                updateForegroundAppTracking();
                return;
            }
            latestPackage = latestPackageFromUsageStats();
        }
        if (latestPackage == null) return;
        if (StatusBarSurfaceContext.isNavigatorWindowForeground()
                && !hasLiveEcarxNavigatorWindowConfirmation()
                && !ecarxNavigatorOptimisticConfirmationPending
                && !StatusBarSurfaceContext.isYandexPackage(latestPackage)) {
            // UsageStats has no Activity class and may lag behind a slow freeform hand-off, so it
            // cannot cancel the independent launch grace. Once that grace has ended it can still
            // prove that the floating surface is no longer topmost and prevent a fallback token
            // from leaking into the next ordinary application.
            StatusBarSurfaceContext.setNavigatorWindowForeground(false);
        }

        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(matchesForegroundContext(hiddenInPackages));
        if (changed) {
            onPhoneNotificationForegroundChanged();
            if (binding != null) {
                renderHomeAssistantBricks();
                applyBrickVisibility(currentBrickSet());
            }
        }
    }

    /** Display ID our overlay's window is attached to. Defaults to {@code DEFAULT_DISPLAY}
     *  if we can't determine it (single-display devices or pre-attach). */
    private int currentOverlayDisplayId() {
        if (binding == null) return android.view.Display.DEFAULT_DISPLAY;
        android.view.Display display = binding.getRoot().getDisplay();
        return display != null ? display.getDisplayId() : android.view.Display.DEFAULT_DISPLAY;
    }

    /** Extracts the most recent foreground package from {@link UsageStatsManager}. Null if
     *  nothing was reported in the lookback window. */
    @Nullable
    private String latestPackageFromUsageStats() {
        if (usageStatsManager == null) return null;
        long now = System.currentTimeMillis();
        UsageEvents events = usageStatsManager.queryEvents(now - FOREGROUND_APP_LOOKBACK_MS, now);
        UsageEvents.Event event = new UsageEvents.Event();
        String latest = lastForegroundPackage;
        long latestTimestamp = 0;
        while (events.getNextEvent(event)) {
            int type = event.getEventType();
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            && type == UsageEvents.Event.ACTIVITY_RESUMED)) {
                if (event.getTimeStamp() >= latestTimestamp) {
                    latestTimestamp = event.getTimeStamp();
                    latest = event.getPackageName();
                }
            }
        }
        return latest;
    }

    private void applyOverlayVisibility(boolean hide) {
        if (overlayHiddenByApp == hide) {
            return;
        }
        overlayHiddenByApp = hide;
        if (binding == null) return;
        View root = binding.getRoot();
        root.animate().cancel();
        if (hide) {
            // Animate to fully transparent, then collapse so the window stops occupying space.
            root.animate()
                    .alpha(0f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .withEndAction(() -> {
                        if (overlayHiddenByApp) root.setVisibility(View.GONE);
                    })
                    .start();
        } else {
            // The animate().cancel() above leaves alpha at whatever it was mid-animation;
            // start the fade-in from the current value to its target of 1f.
            root.setVisibility(View.VISIBLE);
            root.animate()
                    .alpha(1f)
                    .setDuration(OVERLAY_FADE_DURATION_MS)
                    .start();
        }
    }

    private void updateBackground() {
        if (binding == null) {
            return;
        }
        if (themedContext == null) {
            updateThemedContext();
        }
        // Read from the inner container, which is where the background drawable lives and what
        // TransitionManager animates. Reading from getRoot() would, during a visibility
        // transition, briefly return the screen-width window buffer and cap maxRadius too high.
        int width = binding.overlayContainer.getWidth();
        int height = binding.overlayContainer.getHeight();
        if (width == 0 || height == 0) {
            return;
        }
        int maxRadius = Math.min(width, height) / 2;
        int backgroundCornerRadius = (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR)
                ? 0
                : maxRadius * prefs.backgroundCornerRadius.get() / 100;
        int backgroundColor = ContextCompat.getColor(themedContext, R.color.widget_background) & 0x00FFFFFF | (prefs.backgroundAlpha.get() << 24);
        binding.overlayContainer.setBackground(getBackground(backgroundColor, backgroundCornerRadius));
    }

    private Drawable getBackground(int color, int cornerRadius) {
        if (this.background == null || color != this.bgColor || cornerRadius != this.bgCornerRadius) {
            this.background = new GradientDrawable();
            this.background.setColor(color);
            this.background.setCornerRadius(cornerRadius);
            this.bgColor = color;
            this.bgCornerRadius = cornerRadius;
        }

        return this.background;
    }

    private void updateDateTime() {
        if (binding == null) return;
        Set<BrickType> bricks = EnumSet.noneOf(BrickType.class);
        bricks.addAll(BrickType.parseOrder(prefs.brickOrder.get()));
        boolean showTime = bricks.contains(BrickType.TIME) || isPopupBuiltinRequested(BrickType.TIME);
        boolean dateBrickActive = bricks.contains(BrickType.DATE)
                || isPopupBuiltinRequested(BrickType.DATE);
        boolean showDate = dateBrickActive && prefs.date.showDate.get();
        boolean showDayOfTheWeek = dateBrickActive && prefs.date.showDayOfWeek.get();

        if (!showTime && !showDate && !showDayOfTheWeek) {
            return;
        }

        boolean showFullDayAndMonth = prefs.date.showFullName.get();

        String divider = (showDate && showDayOfTheWeek) ? (prefs.date.oneLineLayout.get() ? "," : " \n") : "";
        String dayOfTheWeekFormatStr = showFullDayAndMonth ? "EEEE" : "EEE";
        String dateFormatStr = showFullDayAndMonth ? "d MMMM" : "d MMM";

        // We add spaces at the start/end to avoid outline cropping by canvas which is not ready for the outline
        String dayPart = showDayOfTheWeek ? " " + dayOfTheWeekFormatStr : "";
        String datePart = showDate ? " " + dateFormatStr : "";
        String fullFormatStr = prefs.date.dateBeforeDayOfWeek.get()
                ? datePart + (showDate && showDayOfTheWeek ? divider : "") + dayPart + " "
                : dayPart + (showDate && showDayOfTheWeek ? divider : "") + datePart + " ";

        if (!fullFormatStr.equals(currentDateFormatPattern)) {
            dateFormat = new SimpleDateFormat(fullFormatStr, Locale.getDefault());
            currentDateFormatPattern = fullFormatStr;
        }

        Date now = new Date();
        if (showTime) {
            String timeStr = timeFormat.format(now);
            if (!timeStr.contentEquals(binding.timeText.getText())) {
                binding.timeText.setText(timeStr);
            }
        }
        if (showDate || showDayOfTheWeek) {
            String dateStr = dateFormat.format(now);
            if (!dateStr.contentEquals(binding.dateText.getText())) {
                binding.dateText.setText(dateStr);
            }
        }
        schedulePopupRefresh();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDragListener() {
        binding.getRoot().setOnTouchListener((v, event) -> {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) binding.getRoot().getLayoutParams();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) {
                        // Pinned to (0, 0) full-width — drag is disabled, but consume the event so
                        // ACTION_UP still arrives for click handling.
                        return true;
                    }
                    params.x = initialX + (int) (event.getRawX() - initialTouchX);
                    params.y = initialY + (int) (event.getRawY() - initialTouchY);
                    windowManager.updateViewLayout(binding.getRoot(), params);
                    notifyOverlayState();
                    return true;

                case MotionEvent.ACTION_UP:
                    if (prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR) {
                        savePosition();
                    }

                    // Handle click
                    if (Math.abs(event.getRawX() - initialTouchX) < touchSlop && Math.abs(event.getRawY() - initialTouchY) < touchSlop) {
                        if (binding.wifiStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.wifiStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }
                        if (binding.gnssStatusIcon.getVisibility() == View.VISIBLE &&
                                getBounds(binding.gnssStatusIcon).contains((int) event.getX(), (int) event.getY())) {
                            Intent intent = getPackageManager().getLaunchIntentForPackage(GNSSSHARE_CLIENT_PACKAGE);
                            if (intent == null) {
                                intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            }
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            safeStartActivity(intent);
                            return true;
                        }

                        startMainActivity();
                    }
                    return true;
            }
            return false;
        });
    }

    private void startMainActivity() {
        Intent startIntent = new Intent(WidgetService.this, MainActivity.class);
        startIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        safeStartActivity(startIntent);
    }

    /**
     * Some car head units don't ship the system Wi-Fi / location / app-info activities at all,
     * so launching them from the overlay throws ActivityNotFoundException and tears down the
     * service process. Swallow the failure — the icon tap is non-essential.
     */
    private void safeStartActivity(Intent intent) {
        try {
            startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "startActivity failed for " + intent.getAction(), t);
        }
    }

    private void setWifiStatus(WiFiState newState) {
        if (destroyed || binding == null || prefs == null || !prefs.widgetEnabled.get()) return;
        if (wifiState == newState) return;
        wifiState = newState;
        if (newState == WiFiState.OFF) wifiSignalLevel = 0;
        else wifiSignalLevel = readWifiSignalLevel();
        updateWifiStatus();
    }

    private void updateWifiStatus() {
        if (binding != null) {
            OutlineImageView icon = binding.wifiStatusIcon;
            icon.setImageResource(R.drawable.ic_status_iphone_wifi_level);
            icon.setImageLevel(wifiSignalLevel * 2500);
            icon.setDrawIcon(true);
            ImageViewCompat.setImageTintList(icon, null);
            applyConfiguredIconOutline(icon, prefs.wifi);
            icon.setBadgeText(null, 0, 0);
            if (wifiState == WiFiState.LIMITED_INTERNET) {
                Drawable flag = ContextCompat.getDrawable(this, R.drawable.ic_badge_ru_flag);
                icon.setBadgeDrawable(flag == null ? null : flag.mutate());
            } else {
                icon.setBadgeDrawable(null);
            }
        }
        schedulePopupRefresh();
    }

    private void refreshWifiSignalLevel() {
        int next = wifiState == WiFiState.OFF ? 0 : readWifiSignalLevel();
        if (next == wifiSignalLevel) return;
        wifiSignalLevel = next;
        updateWifiStatus();
    }

    private int readWifiSignalLevel() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            if (info == null || info.getNetworkId() < 0) return 0;
            return Math.max(1, Math.min(4,
                    WifiManager.calculateSignalLevel(info.getRssi(), 4) + 1));
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not read Wi-Fi RSSI", unavailable);
            return wifiState == WiFiState.OFF ? 0 : 1;
        }
    }

    private void setGnssStatus(GnssState newState) {
        if (destroyed || binding == null || prefs == null || !prefs.widgetEnabled.get()) return;
        if (gnssState == newState) {
            return;
        }
        gnssState = newState;
        updateGnssStatus();
    }

    private void updateGnssStatus() {
        if (binding != null) {
            updateIconStatus(ICON_TYPE_GNSS, binding.gnssStatusIcon, gnssState.ordinal());
        }
        schedulePopupRefresh();
    }

    private void updateIconStatus(int iconType, OutlineImageView icon, int state) {
        int designIdx = Math.min(Math.max(0, prefs.iconDesign.get()), ICON_DESIGNS.length - 1);
        int[][] design = ICON_DESIGNS[designIdx];
        int stateIdx = Math.min(Math.max(0, state), design[iconType].length - 1);
        icon.setImageResource(design[iconType][stateIdx]);
        icon.setDrawIcon(true);

        int iconStyle = Math.min(Math.max(0, prefs.iconStyle.get()), 1);
        int[] colorRes;
        Preferences.IconBrickPrefs iconPrefs;
        switch (iconType) {
            case ICON_TYPE_GNSS:
                colorRes = GNSS_STATE_COLOR_RES;
                iconPrefs = prefs.gps;
                break;
            case ICON_TYPE_BT:
                colorRes = BT_STATE_COLOR_RES;
                iconPrefs = prefs.bluetooth;
                break;
            case ICON_TYPE_WIFI:
            default:
                colorRes = WIFI_STATE_COLOR_RES;
                iconPrefs = prefs.wifi;
                break;
        }
        // themedContext is momentarily null between onConfigurationChanged (which invalidates it)
        // and the next applyPreferences that rebuilds it. A status update landing in that window
        // must not crash, so fall back to the service context (matches the guard at getOutlineColor).
        Context ctx = themedContext != null ? themedContext : this;
        int tint = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(ctx, colorRes[stateIdx])
                : ContextCompat.getColor(ctx, R.color.text_primary);
        // Skip the no-op tint set: applyImageTint invalidates the drawable unconditionally,
        // and this runs on every periodic status broadcast.
        ColorStateList currentTint = ImageViewCompat.getImageTintList(icon);
        if (currentTint == null || currentTint.getDefaultColor() != tint) {
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint));
        }

        applyConfiguredIconOutline(icon, iconPrefs);

        // Whitelist (Russian-only internet) — overlay a small flag badge regardless of style.
        if (iconType == ICON_TYPE_WIFI && stateIdx == WiFiState.LIMITED_INTERNET.ordinal()) {
            Drawable flag = ContextCompat.getDrawable(this, R.drawable.ic_badge_ru_flag);
            // mutate() ensures setBounds() doesn't affect a shared cached instance.
            icon.setBadgeDrawable(flag != null ? flag.mutate() : null);
        } else {
            icon.setBadgeDrawable(null);
        }

        // Text badge: GNSS satellite count / DR / spoof marker for GPS, connected-device count
        // for Bluetooth.
        String badgeText = null;
        int badgeBg = 0;
        // Foreground defaults to the widget text colour (flips with the theme, pairs with the
        // style-driven backgrounds below); the coloured GNSS markers override it to a fixed dark
        // ink so the label stays legible on their amber / red pills (white on amber is ~1.9:1).
        int badgeFg = ContextCompat.getColor(ctx, R.color.text_outline) | 0xFF000000;
        // Default badge background follows the icon's own colouring; the GNSS markers override it
        // below with a fixed semantic colour so the meaning reads the same in both icon styles.
        int styleBg = (iconStyle == STYLE_COLOR)
                ? ContextCompat.getColor(ctx, colorRes[stateIdx])
                : ContextCompat.getColor(ctx, R.color.text_primary);
        if (iconType == ICON_TYPE_GNSS && prefs.gps.showSatelliteBadge.get()
                && android.os.SystemClock.uptimeMillis() - satellitesCountTimestamp < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
            // Two independent flags: dead reckoning drives the text, spoofing drives the colour,
            // so both read off the same pill (e.g. "DR" on red = fell back to DR because of a spoof).
            boolean deadReckoning = (gnssModeFlags & GNSSSHARE_MODE_DR) != 0;
            boolean spoofDetected = (gnssModeFlags & GNSSSHARE_MODE_SPOOF) != 0;
            if (deadReckoning) {
                badgeText = getString(R.string.gnss_dr_badge);
            } else if (spoofDetected) {
                // Spoofing but still on GPS: show the marker, not the count — the count is
                // untrustworthy under a spoof and may be absent (some clients report -1).
                badgeText = getString(R.string.gnss_spoof_badge);
            } else if (satellitesCount > 0) {
                badgeText = String.valueOf(satellitesCount);
            }
            if (badgeText != null) {
                if (spoofDetected) {
                    // Spoofing detected — red, whether we're on DR or still on GPS.
                    badgeBg = ContextCompat.getColor(ctx, R.color.status_error);
                    badgeFg = ContextCompat.getColor(ctx, R.color.status_badge_text);
                } else if (deadReckoning) {
                    // Dead reckoning without a spoof — amber (degraded, not an attack).
                    badgeBg = ContextCompat.getColor(ctx, R.color.status_warning);
                    badgeFg = ContextCompat.getColor(ctx, R.color.status_badge_text);
                } else {
                    badgeBg = styleBg;
                }
            }
        } else if (iconType == ICON_TYPE_BT && prefs.bluetooth.showDeviceCountBadge.get()
                && bluetoothState == BluetoothState.CONNECTED && !btConnectedAddrs.isEmpty()) {
            badgeText = String.valueOf(btConnectedAddrs.size());
            badgeBg = styleBg;
        }
        if (badgeText != null) {
            icon.setBadgeText(badgeText, badgeBg, badgeFg);
        } else {
            icon.setBadgeText(null, 0, 0);
        }
    }

    private void applyConfiguredIconOutline(@NonNull OutlineImageView icon,
                                            @NonNull Preferences.IconBrickPrefs iconPrefs) {
        Context context = themedContext != null ? themedContext : this;
        int outlineAlpha = Math.max(0, Math.min(255, iconPrefs.outlineAlpha.get()));
        if (outlineAlpha <= 0) {
            icon.setOutlineWidth(0);
            return;
        }
        int haloColor = (ContextCompat.getColor(context, R.color.text_outline) & 0x00FFFFFF)
                | (outlineAlpha << 24);
        icon.setOutlineColor(haloColor);
        icon.setOutlineWidth(iconPrefs.outlineWidth.get());
    }

    // Wi-Fi state colours by ordinal (OFF, NO_INTERNET, LIMITED_INTERNET, INTERNET).
    private static final int[] WIFI_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_error,
            R.color.status_warning,
            R.color.status_ok
    };
    // GNSS state colours by ordinal (OFF, BAD, GOOD).
    private static final int[] GNSS_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_warning,
            R.color.status_ok
    };
    // Bluetooth state colours by ordinal (OFF, NO_DEVICE, CONNECTED).
    private static final int[] BT_STATE_COLOR_RES = {
            R.color.status_off,
            R.color.status_off,
            R.color.status_bluetooth
    };

    private void createNotificationChannel() {
        NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_title), NotificationManager.IMPORTANCE_LOW);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_status_iphone_gps_active).setContentIntent(pendingIntent).setOngoing(true).build();
    }

    private void savePosition() {
        if (params != null) {
            prefs.overlayX.set(params.x);
            prefs.overlayY.set(params.y);
        }
    }


    @Override
    public void onDestroy() {
        destroyed = true;
        instance = null;
        mainHandler.removeCallbacks(serviceWatchdogHeartbeat);
        if (prefs != null && WidgetServiceStarter.requiresAutomaticIntegrationHost(prefs)) {
            WidgetServiceWatchdog.arm(this,
                    WidgetServiceWatchdog.DESTROY_RECOVERY_DELAY_MS);
        } else {
            WidgetServiceWatchdog.cancel(this);
        }
        automationPresentationListeners.clear();
        // A first-useful-surface event may have been waiting solely for this host's readiness.
        // Once the host is gone, let Application finish its immediate event-driven initialization.
        StatusWidgetApplication.resumeSurfaceOwnedInitialization(this);
        long ownerToken = startupStateOwnerToken;
        if (ownerToken != 0L) {
            STARTUP_STATE_OWNER.compareAndSet(ownerToken, ownerToken + 1L);
            startupStateOwnerToken = 0L;
        }
        if (!runtimeInitialized) {
            stopForeground(true);
            super.onDestroy();
            return;
        }
        if (ecarxNavigatorWindowObserver != null) {
            ecarxNavigatorWindowObserver.stop();
            ecarxNavigatorWindowObserver = null;
        }
        mainHandler.removeCallbacks(ecarxNavigatorWindowLeaseExpiry);
        cancelEcarxNavigatorOptimisticConfirmation();
        ecarxNavigatorWindowDecision = NavigatorWindowSourcePolicy.VendorDecision.NONE;
        ecarxNavigatorWindowDecisionAtElapsed = -1L;
        integrationStartupScheduled = false;
        cancelDeferredIntegrationStart();
        startupStateWorker.shutdownNow();
        initialIntegrationWorkerInFlight = false;
        PreparedInitialIntegrationStage unpublished =
                pendingInitialIntegrationStage.getAndSet(null);
        if (unpublished != null) discardPreparedInitialIntegrationStage(unpublished);

        mainHandler.removeCallbacksAndMessages(null);

        // Unregister derived listeners first. Connector shutdown emits synchronous stale events;
        // with the guards above and no scenario/popup listeners left, none can recreate a window.
        if (connectorValues != null) {
            connectorValues.removeListener(phoneStatusListener);
            connectorValues.removeListener(crossSourceRuleListener);
        }
        phoneStatusValues.clear();
        observedPhoneNotificationKeys.clear();
        queuedPhoneNotifications.clear();
        deferredPhoneNotifications.clear();
        queuedPhoneNotificationOverflowCount = 0;
        deferredPhoneNotificationOverflowCount = 0;
        deferredPhoneNotificationOverflowStartedElapsed = 0L;
        pendingIntentScenarioCommands.clear();
        phoneNotificationBurstActive = false;
        phoneAncsReady = false;
        activePhoneNotification = null;
        activePhoneNotificationFields = Collections.emptySet();
        activePhoneBatteryAlertText = null;
        activePhoneBatteryAlertColor = null;
        phoneLowBatteryAlertLatched = false;
        phoneLowBatteryAlertLatched2 = false;
        phoneLowBatteryAlertPending = false;
        phoneLowBatteryAlertPending2 = false;
        phoneExternalOverlayActive = false;
        phoneVehicleOverlayActive = false;
        phoneNotificationOverlayPaused = false;
        pausedPhoneNotificationRemainingMs = 0L;
        pausedPhonePopupRemainingMs = 0L;
        pausedPhoneNotificationQueueAdvance = false;
        activePhoneNotificationExpiresAt = 0L;
        activePhonePopupNotificationExpiresAt = 0L;
        activePhoneLowBatteryPopup = false;
        phoneNotificationPopupConfigured = false;
        crossSourceRuleRefreshScheduled.set(false);
        synchronized (automationUiLock) {
            pendingAutomationUi.clear();
            automationUiRefreshScheduled = false;
        }
        if (intentScenarioController != null) {
            runCleanupStep("intent scenarios", intentScenarioController::destroy);
        }
        intentScenarioController = null;
        if (scenarioController != null) {
            runCleanupStep("visual scenarios", scenarioController::destroy);
        }
        scenarioController = null;
        if (popupOverlay != null) runCleanupStep("popup overlays", popupOverlay::destroy);
        popupOverlay = null;
        // Keep Sprut alive until both the exact-device disconnect callback and the exporter's
        // final compensating OFF have been submitted.
        if (phoneController != null) {
            runCleanupStep("phone", phoneController::stop);
        }
        phoneController = null;
        if (phonePresenceExporter != null) {
            runCleanupStep("phone presence", phonePresenceExporter::stop);
        }
        phonePresenceExporter = null;
        if (phoneAncsPresenceExporter != null) {
            runCleanupStep("phone ANCS presence", phoneAncsPresenceExporter::stop);
        }
        phoneAncsPresenceExporter = null;
        boolean carRuntimeWasInitialized = carTelemetryExporter != null;
        boolean carIntegrationNeedsCleanup = carRuntimeWasInitialized
                || phoneVehicleOverlayListenerInstalled;
        if (carRuntimeWasInitialized) {
            runCleanupStep("car telemetry", carTelemetryExporter::stop);
        }
        carTelemetryExporter = null;
        if (mqttController != null) runCleanupStep("MQTT", mqttController::stop);
        mqttController = null;
        if (sprutController != null) runCleanupStep("Sprut.hub", sprutController::stop);
        sprutController = null;
        if (haApiController != null) {
            runCleanupStep("Home Assistant", haApiController::stop);
        }
        haApiController = null;
        actionDispatcher = null;
        mainHandler.removeCallbacksAndMessages(null);

        removeStatusOverlaySafely("service shutdown");
        binding = null;
        params = null;

        stopLocationTracking();
        stopConnectivityTracking();

        if (reachabilityChecker != null) {
            ReachabilityChecker checker = reachabilityChecker;
            reachabilityChecker = null;
            runCleanupStep("reachability checker", checker::shutdown);
        }

        unregisterSatelliteStatusReceiver();
        unregisterBluetoothReceiver();
        runCleanupStep("media tracking", this::disableMediaTracking);
        // Drop car sensor subscriptions but keep the process-wide integration alive — the
        // settings UI may still query isBrickSupported after the overlay service stops.
        if (carIntegrationNeedsCleanup) {
            runCleanupStep("car sensor subscriptions", () -> {
                CarIntegration car = CarIntegrations.get(this);
                car.setAvailabilityChangedListener(null);
                car.setExternalOverlayListener(null);
                car.unsubscribe(BrickType.INDOOR_TEMP);
                car.unsubscribe(BrickType.OUTDOOR_TEMP);
            });
        }
        phoneVehicleOverlayListenerInstalled = false;
        super.onDestroy();
    }

    private void refreshServiceWatchdog() {
        mainHandler.removeCallbacks(serviceWatchdogHeartbeat);
        if (destroyed || prefs == null
                || !WidgetServiceStarter.requiresAutomaticIntegrationHost(prefs)) {
            WidgetServiceWatchdog.cancel(this);
            return;
        }
        WidgetServiceWatchdog.arm(this);
        mainHandler.postDelayed(serviceWatchdogHeartbeat, SERVICE_WATCHDOG_HEARTBEAT_MS);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static WidgetService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    boolean isIntegrationRuntimeReadyForApplication() {
        return !destroyed && integrationsStarted;
    }

    /** Current live ANCS subscription, used by driver rows that explicitly opt into this gate. */
    public boolean isPhoneAncsReady() {
        return !destroyed && phoneAncsReady;
    }

    /**
     * Read-only same-process geometry for HOME safe-area calculation.
     *
     * <p>The returned value is the actual measured top-row window, not a duplicated estimate
     * from font/icon settings. Zero means that no status-bar-mode overlay currently occupies the
     * top edge.</p>
     */
    public int getStatusBarOverlayHeight() {
        if (destroyed || prefs == null || !prefs.widgetEnabled.get()
                || prefs.widgetMode.get() != WIDGET_MODE_STATUS_BAR || binding == null) {
            return 0;
        }
        View root = binding.getRoot();
        return Math.max(root.getHeight(), root.getMeasuredHeight());
    }

    /**
     * Current status-bar brick presentation for same-process secondary surfaces.
     *
     * <p>This is deliberately a read-only snapshot: the driver panel reuses the exact selected
     * icon family, semantic state colour, outline and badge without registering a second set of
     * Bluetooth, GNSS, connectivity, media or vehicle listeners.</p>
     */
    @Nullable
    public StatusBrickSnapshot statusBrickSnapshot(@NonNull BrickType type) {
        if (destroyed || prefs == null) return null;
        // PHONE telemetry belongs to the service runtime, not to the optional status-row
        // WindowManager root. Launcher/driver information tiles must keep receiving signal,
        // battery and radio generation while the status overlay itself is disabled, detached or
        // still retrying its attachment. Other brick snapshots still read live binding views.
        boolean headlessPhoneSnapshot = type == BrickType.PHONE_CELLULAR
                || type == BrickType.PHONE_BATTERY
                || type == BrickType.PHONE_NETWORK_TYPE;
        if (binding == null && !headlessPhoneSnapshot) return null;
        String text = "";
        int iconResource = 0;
        int iconTint = ContextCompat.getColor(
                themedContext != null ? themedContext : this, R.color.text_primary);
        int iconLevel = 10000;
        Integer batteryPercent = null;
        boolean batteryCharging = false;
        Integer cellularSignalPercent = null;
        String cellularOperator = "";
        String cellularNetworkType = "";
        int outlineColor = ContextCompat.getColor(
                themedContext != null ? themedContext : this, R.color.text_outline);
        int outlineWidth = 0;
        String badgeText = null;
        int badgeBackground = 0;
        int badgeForeground = ContextCompat.getColor(
                themedContext != null ? themedContext : this, R.color.text_outline)
                | 0xFF000000;
        int badgeDrawableResource = 0;
        boolean known = true;
        boolean active = true;

        int iconType = -1;
        int state = 0;
        Preferences.IconBrickPrefs iconPrefs = null;
        switch (type) {
            case TIME:
                text = timeFormat.format(new Date());
                break;
            case DATE:
                text = String.valueOf(binding.dateText.getText());
                known = !text.trim().isEmpty();
                break;
            case MEDIA:
                text = lastMediaSubtitle == null ? "" : lastMediaSubtitle;
                known = !text.isEmpty();
                active = known;
                break;
            case WIFI:
                iconResource = R.drawable.ic_status_iphone_wifi_level;
                iconTint = 0; // Preserve per-arc gray/green vector colors.
                iconLevel = wifiSignalLevel * 2500;
                iconPrefs = prefs.wifi;
                active = wifiState == WiFiState.INTERNET
                        || wifiState == WiFiState.LIMITED_INTERNET;
                switch (wifiState) {
                    case INTERNET: text = "Интернет"; break;
                    case LIMITED_INTERNET: text = "Ограниченная сеть"; break;
                    case NO_INTERNET: text = "Без интернета"; break;
                    case OFF:
                    default: text = "Wi‑Fi выключен"; break;
                }
                break;
            case GPS:
                iconType = ICON_TYPE_GNSS;
                state = gnssState.ordinal();
                iconPrefs = prefs.gps;
                active = gnssState == GnssState.GOOD;
                text = gnssState == GnssState.GOOD ? "GPS"
                        : gnssState == GnssState.BAD ? "Нет фиксации" : "GPS выключен";
                break;
            case BLUETOOTH:
                iconType = ICON_TYPE_BT;
                state = bluetoothState.ordinal();
                iconPrefs = prefs.bluetooth;
                active = bluetoothState == BluetoothState.CONNECTED;
                text = bluetoothState == BluetoothState.CONNECTED
                        ? "Подключено" : bluetoothState == BluetoothState.NO_DEVICE
                        ? "Нет устройств" : "Bluetooth выключен";
                break;
            case INDOOR_TEMP:
                text = String.valueOf(binding.indoorTempText.getText());
                known = !text.isEmpty() && !TEMP_PLACEHOLDER.equals(text);
                active = known;
                break;
            case OUTDOOR_TEMP:
                text = String.valueOf(binding.outdoorTempText.getText());
                known = !text.isEmpty() && !TEMP_PLACEHOLDER.equals(text);
                active = known;
                break;
            case PHONE_STATUS:
                text = joinedVisibleText(binding.phoneStatusContainer);
                known = !text.isEmpty();
                active = known;
                break;
            case PHONE_CELLULAR:
                Integer signal = phonePercent("network.signal");
                String operator = phoneText("network.operator");
                String rawCellularType = phoneNetworkType();
                String cellularType = prefs.phoneCellular.showNetworkType.get()
                        ? rawCellularType : "";
                cellularSignalPercent = signal;
                cellularOperator = operator;
                cellularNetworkType = rawCellularType;
                known = signal != null || !operator.isEmpty() || !cellularType.isEmpty();
                active = !operator.isEmpty() || !cellularType.isEmpty()
                        || signal != null && signal > 0;
                text = !cellularType.isEmpty() && !operator.isEmpty()
                        ? cellularType + " · " + operator
                        : !cellularType.isEmpty() ? cellularType
                        : !operator.isEmpty() ? operator : known ? signal + "%" : "";
                iconResource = R.drawable.ic_status_iphone_cellular_level;
                iconTint = 0; // Active/inactive bars carry their own colors.
                iconLevel = cellularBars(signal) * 2500;
                iconPrefs = prefs.phoneCellular;
                break;
            case PHONE_BATTERY:
                Integer battery = phonePercent("battery.level");
                known = battery != null;
                active = known;
                text = known ? battery + "%" : "";
                iconResource = R.drawable.ic_status_iphone_battery;
                iconLevel = battery == null ? 0 : battery * 100;
                iconPrefs = prefs.phoneBattery;
                batteryCharging = phoneChargingNow();
                iconTint = phoneBatteryColor(battery, batteryCharging);
                batteryPercent = prefs.phoneBattery.showPercentage.get() ? battery : null;
                break;
            case PHONE_NETWORK_TYPE:
                text = phoneNetworkType();
                known = !text.isEmpty();
                active = known;
                break;
            default:
                return null;
        }

        if (iconType >= 0 && iconPrefs != null) {
            int designIndex = Math.min(Math.max(0, prefs.iconDesign.get()),
                    ICON_DESIGNS.length - 1);
            int[][] design = ICON_DESIGNS[designIndex];
            state = Math.min(Math.max(0, state), design[iconType].length - 1);
            iconResource = design[iconType][state];
            int[] colorResources = iconType == ICON_TYPE_WIFI ? WIFI_STATE_COLOR_RES
                    : iconType == ICON_TYPE_GNSS ? GNSS_STATE_COLOR_RES : BT_STATE_COLOR_RES;
            if (Math.min(Math.max(0, prefs.iconStyle.get()), 1) == STYLE_COLOR) {
                iconTint = ContextCompat.getColor(
                        themedContext != null ? themedContext : this, colorResources[state]);
            }
            int outlineAlpha = iconPrefs.outlineAlpha.get();
            outlineWidth = outlineAlpha <= 0 ? 0 : iconPrefs.outlineWidth.get();
            outlineColor = (outlineColor & 0x00FFFFFF)
                    | (Math.min(255, Math.max(0, outlineAlpha)) << 24);
            int styleBackground = iconTint;

            if (iconType == ICON_TYPE_WIFI
                    && state == WiFiState.LIMITED_INTERNET.ordinal()) {
                badgeDrawableResource = R.drawable.ic_badge_ru_flag;
            } else if (iconType == ICON_TYPE_GNSS && prefs.gps.showSatelliteBadge.get()
                    && android.os.SystemClock.uptimeMillis() - satellitesCountTimestamp
                    < GNSSSHARE_SATELLITE_STATUS_TIMEOUT_MS) {
                boolean deadReckoning = (gnssModeFlags & GNSSSHARE_MODE_DR) != 0;
                boolean spoofDetected = (gnssModeFlags & GNSSSHARE_MODE_SPOOF) != 0;
                if (deadReckoning) badgeText = getString(R.string.gnss_dr_badge);
                else if (spoofDetected) badgeText = getString(R.string.gnss_spoof_badge);
                else if (satellitesCount > 0) badgeText = String.valueOf(satellitesCount);
                if (badgeText != null) {
                    if (spoofDetected) {
                        badgeBackground = ContextCompat.getColor(this, R.color.status_error);
                        badgeForeground = ContextCompat.getColor(
                                this, R.color.status_badge_text);
                    } else if (deadReckoning) {
                        badgeBackground = ContextCompat.getColor(this, R.color.status_warning);
                        badgeForeground = ContextCompat.getColor(
                                this, R.color.status_badge_text);
                    } else {
                        badgeBackground = styleBackground;
                    }
                }
            } else if (iconType == ICON_TYPE_BT
                    && prefs.bluetooth.showDeviceCountBadge.get()
                    && bluetoothState == BluetoothState.CONNECTED
                    && !btConnectedAddrs.isEmpty()) {
                badgeText = String.valueOf(btConnectedAddrs.size());
                badgeBackground = styleBackground;
            }
            if (iconType == ICON_TYPE_BT) {
                PhoneBluetoothIndicatorPolicy.Appearance appearance =
                        PhoneBluetoothIndicatorPolicy.resolve(
                                bluetoothState == BluetoothState.CONNECTED,
                                hasSelectedPhoneConfiguration(),
                                isPhoneNotificationPathAvailable());
                if (appearance != PhoneBluetoothIndicatorPolicy.Appearance.DEFAULT) {
                    iconResource = R.drawable.ic_status_iphone_bluetooth_solid;
                    iconTint = ContextCompat.getColor(
                            themedContext != null ? themedContext : this,
                            R.color.status_bluetooth);
                    outlineWidth = 0;
                }
            }
        } else if (iconResource != 0 && iconPrefs != null) {
            int outlineAlpha = Math.max(0, Math.min(255, iconPrefs.outlineAlpha.get()));
            outlineWidth = outlineAlpha <= 0 ? 0 : iconPrefs.outlineWidth.get();
            outlineColor = (outlineColor & 0x00FFFFFF) | (outlineAlpha << 24);
            if (type == BrickType.WIFI && wifiState == WiFiState.LIMITED_INTERNET) {
                badgeDrawableResource = R.drawable.ic_badge_ru_flag;
            }
        }
        return new StatusBrickSnapshot(text, iconResource, iconTint, iconLevel, batteryPercent,
                batteryCharging, cellularSignalPercent, cellularOperator, cellularNetworkType,
                outlineColor, outlineWidth, badgeText, badgeBackground, badgeForeground,
                badgeDrawableResource, known, active);
    }

    /** Immutable read-only connector snapshot for settings/catalog pickers. */
    @NonNull
    public List<ConnectorValue> connectorValueSnapshot() {
        ConnectorValueRegistry current = connectorValues;
        return current == null ? java.util.Collections.emptyList() : current.snapshot();
    }

    /**
     * Subscribes a same-process HOME surface to raw HA/MQTT/Sprut value changes.
     *
     * <p>The returned initial snapshot closes the first-launch race: the connector may have
     * completed synchronization before LauncherActivity obtained the service singleton.</p>
     */
    @NonNull
    public List<ConnectorValue> addConnectorValueListener(
            @NonNull ConnectorValueRegistry.Listener listener) {
        ConnectorValueRegistry current = connectorValues;
        if (current == null) return java.util.Collections.emptyList();
        // Subscribe before reading: an update racing this snapshot is either already included or
        // arrives through the listener immediately afterwards, never lost between the two steps.
        current.addListener(listener);
        return current.snapshot();
    }

    public void removeConnectorValueListener(
            @NonNull ConnectorValueRegistry.Listener listener) {
        ConnectorValueRegistry current = connectorValues;
        if (current != null) current.removeListener(listener);
    }

    /** Registers a same-process visual surface for already-coalesced automation invalidations. */
    public void addAutomationPresentationListener(
            @NonNull AutomationPresentationListener listener) {
        synchronized (automationPresentationListeners) {
            if (automationPresentationListeners.contains(listener)) return;
            if (automationPresentationListeners.size()
                    >= MAX_AUTOMATION_PRESENTATION_LISTENERS) {
                throw new IllegalStateException("Too many automation presentation listeners");
            }
            automationPresentationListeners.add(listener);
        }
    }

    public void removeAutomationPresentationListener(
            @NonNull AutomationPresentationListener listener) {
        automationPresentationListeners.remove(listener);
    }

    /** Complete scenario/broadcast presentation state for one external HUD element. */
    @NonNull
    public AutomationState hudAutomationState(@NonNull String automationId) {
        AutomationStateStore current = automationStates;
        return current == null ? AutomationState.missing() : current.get(
                AutomationContract.SCOPE_HUD, automationId);
    }

    /** Scenario-resolved visibility for one driver-panel shortcut. */
    public boolean driverShortcutVisible(@NonNull String shortcutId, boolean defaultValue) {
        AutomationStateStore current = automationStates;
        return current == null ? defaultValue : current.effectiveVisibility(
                AutomationContract.SCOPE_DRIVER, shortcutId, defaultValue);
    }

    /** Scenario-resolved interaction gate for one driver-panel shortcut. */
    public boolean driverShortcutActionEnabled(@NonNull String shortcutId,
                                               boolean defaultValue) {
        AutomationStateStore current = automationStates;
        return current == null ? defaultValue : current.effectiveActionEnabled(
                AutomationContract.SCOPE_DRIVER, shortcutId, defaultValue);
    }

    /** Complete effective driver style, including the in-memory scenario precedence layer. */
    @NonNull
    public AutomationState driverAutomationState(@NonNull String targetId) {
        AutomationStateStore current = automationStates;
        return current == null ? AutomationState.missing() : current.get(
                AutomationContract.SCOPE_DRIVER, targetId);
    }

    /** Complete effective HOME shortcut style, including the in-memory scenario layer. */
    @NonNull
    public AutomationState launcherAutomationState(@NonNull String targetId) {
        AutomationStateStore current = automationStates;
        return current == null ? AutomationState.missing() : current.get(
                AutomationContract.SCOPE_LAUNCHER, targetId);
    }

    /** Explicit automation decision for one transient Favorites panel; null preserves manual UI. */
    @Nullable
    public Boolean driverFavoritePanelVisibility(@NonNull String panelId) {
        AutomationStateStore current = automationStates;
        return current == null ? null : current.explicitVisibility(
                AutomationContract.SCOPE_DRIVER, panelId);
    }

    private static Rect getBounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
