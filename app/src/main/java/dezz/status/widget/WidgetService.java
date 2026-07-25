Warning: truncated output (original token count: 47343)
Total output lines: 3932

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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.car.CarTelemetryExporter;
import dezz.status.widget.databinding.OverlayStatusWidgetBinding;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.automation.ScenarioTriggerReceiver;
import dezz.status.widget.ha.HaBrickConfig;
import dezz.status.widget.ha.HaBrickConfigStore;
import dezz.status.widget.integration.ConnectorActionDispatcher;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.IntentScenarioController;
import dezz.status.widget.integration.LocalScenarioController;
import dezz.status.widget.driver.DriverPanelService;
import dezz.status.widget.ha.api.HaApiController;
import dezz.status.widget.ha.api.HaEntityCatalog;
import dezz.status.widget.ha.api.HaWebSocketConnector;
import dezz.status.widget.mqtt.MqttController;
import dezz.status.widget.phone.PhoneConnectorController;
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
            { R.drawable.ic_status_gps_off, R.drawable.ic_status_gps_bad, R.drawable.ic_status_gps_good },
            { R.drawable.ic_status_bt_off, R.drawable.ic_status_bt_no_device, R.drawable.ic_status_bt_connected }
    };
    private static final int[][] DESIGN_SOLID = {
            {
                    R.drawable.ic_status_filled_wifi_off,
                    R.drawable.ic_status_filled_wifi_no_internet,
                    R.drawable.ic_status_filled_wifi_whitelist,
                    R.drawable.ic_status_filled_wifi_internet
            },
            { R.drawable.ic_status_filled_gps_off, R.drawable.ic_status_filled_gps_bad, R.drawable.ic_status_filled_gps_good },
            { R.drawable.ic_status_filled_bt_off, R.drawable.ic_status_filled_bt_no_device, R.drawable.ic_status_filled_bt_connected }
    };
    private static final int[][] DESIGN_BARS = {
            {
                    R.drawable.ic_status_bars_wifi_off,
                    R.drawable.ic_status_bars_wifi_no_internet,
                    R.drawable.ic_status_bars_wifi_whitelist,
                    R.drawable.ic_status_bars_wifi_internet
            },
            { R.drawable.ic_status_bars_gps_off, R.drawable.ic_status_bars_gps_bad, R.drawable.ic_status_bars_gps_good },
            { R.drawable.ic_status_bars_bt_off, R.drawable.ic_status_bars_bt_no_device, R.drawable.ic_status_bars_bt_connected }
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
    private static final long OVERLAY_ATTACH_RETRY_MS = 1_500L;
    private static final long MAX_OVERLAY_ATTACH_RETRY_MS = 30_000L;
    /**
     * Duration of the combined Fade + ChangeBounds transition that handles per-brick
     * visibility flips. See {@link #beginVisibilityTransition} for the "window-buffer"
     * trick that makes this transition stay inside a stable window rectangle.
     */
    private static final int BRICK_TRANSITION_DURATION_MS = 450;
    /**
     * Duration of {@link android.animation.LayoutTransition#CHANGING} animations that fire
     * when a child changes its own size (clock minute, date, media track, icon swap). Shorter
     * than visibility flips because the user sees small frequent updates as snappy when
     * animated under ~300ms; longer feels sluggish for tiny shifts.
     */
    private static final int CONTENT_CHANGE_DURATION_MS = 250;
    /** Duration of the alpha animation used when a brick is hidden in keeps-space mode. */
    private static final int BRICK_ALPHA_DURATION_MS = 300;

    private static final String TAG = "WidgetService";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "WidgetServiceChannel";
    private static final long GNSS_STATUS_CHECK_INTERVAL = 2_000L;
    private static final long GNSS_LOCATION_INTERVAL_MS = 2_000L;
    private static final long DATETIME_UPDATE_INTERVAL_MS = 60_000L;
    /** Cadence for advancing the media progress bar while a track is actively playing. 250ms
     *  is fast enough to look smooth on a thin bar and slow enough to not show up in profilers. */
    // One repaint per second is visually sufficient for a compact status-row progress line and
    // halves MediaSession polling/layout invalidation versus HA1048 on low-end head units.
    private static final long MEDIA_PROGRESS_TICK_MS = 1_000L;
    /** Gap between the play/pause indicator and the text it precedes, as a fraction of that
     *  text's size — same rationale as the icon's own size: it must track the font sliders. */
    private static final float STATE_ICON_GAP_RATIO = 0.25f;
    private static final long FOREGROUND_APP_CHECK_INTERVAL_MS = 2_000L;
    private static final long FOREGROUND_APP_LOOKBACK_MS = 60_000L;
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

    private Preferences prefs;
    private AutomationStateStore automationStates;
    private ConnectorValueRegistry connectorValues;
    private LocalScenarioController scenarioController;
    private IntentScenarioController intentScenarioController;
    private ConnectorActionDispatcher actionDispatcher;
    private HaBrickConfigStore haConfigs;
    private HaApiController haApiController;
    private MqttController mqttController;
    private SprutHubController sprutController;
    private PhoneConnectorController phoneController;
    private PhoneSprutPresenceExporter phonePresenceExporter;
    private CarTelemetryExporter carTelemetryExporter;
    private PopupOverlayManager popupOverlay;
    /** Parsed only when settings change; connector packets must never reparse the JSON document. */
    private List<HaBrickConfig> configuredMainBricks = Collections.emptyList();
    private final Object automationUiLock = new Object();
    private final Map<String, Set<String>> pendingAutomationUi = new LinkedHashMap<>();
    private boolean automationUiRefreshScheduled;
    private final Runnable automationUiRefresh = () -> {
        Map<String, Set<String>> changed = new LinkedHashMap<>();
        synchronized (automationUiLock) {
            for (Map.Entry<String, Set<String>> entry : pendingAutomationUi.entrySet()) {
                changed.put(entry.getKey(), new HashSet<>(entry.getValue()));
            }
            pendingAutomationUi.clear();
            automationUiRefreshScheduled = false;
        }
        if (WidgetService.this.destroyed || changed.isEmpty()) return;
        boolean affectsStatusRow = changed.containsKey(AutomationContract.SCOPE_MAIN)
                || changed.containsKey(AutomationContract.SCOPE_BUILTIN);
        if (popupOverlay != null) {
            for (Map.Entry<String, Set<String>> entry : changed.entrySet()) {
                for (String id : entry.getValue()) {
                    popupOverlay.onStateChanged(entry.getKey(), id);
                }
            }
        }
        // Popup windows have an independent WindowManager lifecycle. A failed/retrying status-row
        // attachment must not discard their connector updates.
        if (WidgetService.this.binding == null) return;
        if (changed.containsKey(AutomationContract.SCOPE_MAIN)) renderHomeAssistantBricks();
        // A popup-only temperature/sensor stream must not remeasure and animate the independent
        // status row. HA1048 did that for every packet even when no status brick had changed.
        if (affectsStatusRow) applyBrickVisibility(currentBrickSet());
    };
    private volatile boolean destroyed;
    private final AtomicBoolean crossSourceRuleRefreshScheduled = new AtomicBoolean();
    private final ConnectorValueRegistry.Listener crossSourceRuleListener =
            changedValues -> scheduleCrossSourceRuleRefresh();
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

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;

    private OverlayStatusWidgetBinding binding;
    private int overlayAttachAttempts;
    private boolean overlayAttachRetryScheduled;
    private final Runnable overlayAttachRetry = () -> {
        overlayAttachRetryScheduled = false;
        if (destroyed || binding != null || !prefs.widgetEnabled.get()) return;
        if (!Permissions.allPermissionsGranted(this)) {
            // Location AppOps are shared with the status row but are not required by the
            // independently attached driver rail or its HA/MQTT/Sprut live-state host. Keep that
            // host alive while the status surface waits for permissions to be restored.
            if (prefs.driverPanelEnabled.get()) {
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
    private BluetoothState bluetoothState = BluetoothState.OFF;
    private final Set<String> btConnectedAddrs = new HashSet<>();
    private boolean btReceiverRegistered = false;
    /** Invalidates asynchronous profile snapshots after status tracking is stopped or reseeded. */
    private int bluetoothTrackingGeneration;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * Connector startup is deliberately independent from the status-window binding. WindowManager
     * can transiently reject addView during boot while an already-running connector still needs to
     * re-read Keystore credentials on USER_UNLOCKED.
     */
    private boolean integrationsStarted;
    private boolean integrationStartupScheduled;
    private boolean initialIntegrationStartupInProgress;
    private final Runnable integrationStartup = this::runInitialIntegrationStartup;
    private final Choreographer.FrameCallback integrationStartupFrame = frameTimeNanos ->
            // Frame callbacks run before traversal. Posting once more lets traversal draw the
            // attached status row before connector JSON/Keystore work begins on the main Looper.
            mainHandler.post(integrationStartup);
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
    private boolean overlayAttached;
    private long lastLocationUpdateTime = 0;

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
    private boolean overlayHiddenByApp = false;

    /**
     * Number of in-flight transitions that have widened the WindowManager window to the
     * screen-width "buffer" so animations can play in a stable rectangle. Incremented when
     * a transition starts the buffer, decremented when it ends; the window is restored to
     * WRAP_CONTENT only when the counter reaches zero. Shared between:
     * <ul>
     *   <li>{@link #beginVisibilityTransition} (brick show/hide)</li>
     *   <li>The always-on {@link android.animation.LayoutTransition#CHANGING} on
     *       overlayContainer (any child changing measured size)</li>
     *   <li>The eager pre-empt in the {@code onLayoutChange} listener that catches a
     *       shrink one frame before {@code LayoutTransition.startTransition} would,
     *       so the window doesn't snap below the children that are still animating
     *       at their old positions</li>
     * </ul>
     */
    private int pendingBufferedTransitions = 0;

    /**
     * Closes the buffer opened eagerly by {@code onLayoutChange} when the content shrinks.
     * Posted with a delay slightly longer than {@link #BRICK_TRANSITION_DURATION_MS}; the
     * happy-path {@code LayoutTransition.endTransition} usually fires first and the
     * counter goes to zero on its own — this is the safety net for the case where no
     * {@code LayoutTransition} actually runs (e.g. a same-size measure that still
     * propagated through), so the window doesn't stay screen-wide forever.
     */
    private final Runnable shrinkBufferSafetyClose = this::endBufferedTransition;

    /**
     * Always-on {@link android.animation.LayoutTransition#CHANGING} animation installed on the
     * overlay container. Held as a field so {@link #beginVisibilityTransition} can disable
     * CHANGING for the duration of a visibility flip — otherwise the explicit ChangeBounds
     * inside the visibility {@link android.transition.TransitionSet} and the implicit CHANGING
     * triggered by sibling bricks shifting both play at once, producing the visible "double
     * animation". Re-enabled when the visibility transition's close runnable fires.
     */
    @Nullable
    private android.animation.LayoutTransition contentLayoutTransition;

    private Context themedContext;
    private int appliedThemePref = -1;

    /** Fires when the overlay's position or size changes so the settings UI can stay in sync. */
    public interface OverlayStateListener {
        void onOverlayStateChanged(int x, int y, int width, int height);
    }

    @Nullable private OverlayStateListener overlayStateListener;

    private MediaSessionManager mediaSessionManager;
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
            this::rebindMediaControllers;

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

    private final Runnable foregroundAppCheckRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            mainHandler.postDelayed(this, FOREGROUND_APP_CHECK_INTERVAL_MS);
        }
    };

    private final Runnable updateGnssStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (System.currentTimeMillis() - lastLocationUpdateTime > 10000) {
                setGnssStatus(GnssState.OFF);
            } else if (System.currentTimeMillis() - lastLocationUpdateTime > 5000) {
                setGnssStatus(GnssState.BAD);
            }

            mainHandler.postDelayed(this, GNSS_STATUS_CHECK_INTERVAL);
        }
    };

    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onStarted() {
            Log.d(TAG, "GNSS is started");
            setGnssStatus(GnssState.BAD);
        }

        @Override
        public void onStopped() {
            Log.d(TAG, "GNSS is stopped");
            setGnssStatus(GnssState.OFF);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            Log.d(TAG, "GNSS has first fix");
            setGnssStatus(GnssState.BAD);
        }
    };

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            lastLocationUpdateTime = System.currentTimeMillis();
            if (location.hasAccuracy() && location.getAccuracy() < 20.0) {
                setGnssStatus(GnssState.GOOD);
            } else {
                setGnssStatus(GnssState.BAD);
            }
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            Log.d(TAG, "Wi-Fi is connected");
            if (wifiState == WiFiState.OFF) {
                setWifiStatus(WiFiState.NO_INTERNET);
            }
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
            } else {
                setWifiStatus(WiFiState.OFF);
            }
        }
    };

    private final Runnable reachabilityProbeRunnable = new Runnable() {
        @Override
        public void run() {
            if (wifiState != WiFiState.OFF) {
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

        prefs = new Preferences(this);
        automationStates = new AutomationStateStore(this);
        connectorValues = new ConnectorValueRegistry();
        connectorValues.addListener(crossSourceRuleListener);
        // A value persisted before ignition-off is useful context, but it is not authoritative
        // after a new process starts. Each connector promotes only values returned by its fresh
        // startup snapshot/retained replay, preventing a missed offline change from looking live.
        automationStates.markAllStale();
        haConfigs = new HaBrickConfigStore(prefs);
        configuredMainBricks = haConfigs.loadMain();
        mqttController = new MqttController(this, prefs, automationStates, connectorValues,
                new MqttController.StateListener() {
                    @Override public void onStateChanged(String scope, String id) {
                        onAutomationStateChanged(scope, id);
                    }

                    @Override public void onConnectionChanged(boolean connected, String detail) {
                        Log.i(TAG, "MQTT " + (connected ? "connected" : "disconnected")
                                + ": " + detail);
                    }
                });
        sprutController = new SprutHubController(this, prefs, automationStates, connectorValues,
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
                    }

                    @Override public void onCharacteristicChanged(
                            @NonNull dezz.status.widget.sprut.SprutPath path) {
                        if (carTelemetryExporter != null) {
                            carTelemetryExporter.onSprutCharacteristicChanged(path);
                        }
                        if (phonePresenceExporter != null) {
                            phonePresenceExporter.onSprutCharacteristicChanged(path);
                        }
                    }
                });
        phonePresenceExporter = new PhoneSprutPresenceExporter(
                prefs, sprutController, mainHandler);
        phoneController = new PhoneConnectorController(this, prefs, connectorValues,
                connected -> {
                    PhoneSprutPresenceExporter exporter = phonePresenceExporter;
                    if (exporter != null) exporter.onPhoneConnectionChanged(connected);
                });
        carTelemetryExporter = new CarTelemetryExporter(prefs, CarIntegrations.get(this),
                sprutController, mainHandler);
        haApiController = new HaApiController(this, prefs, automationStates, connectorValues,
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
        actionDispatcher = new ConnectorActionDispatcher(
                mqttController, sprutController, haApiController);
        scenarioController = new LocalScenarioController(prefs, automationStates, connectorValues,
                targets -> {
                    // Initial startup performs one consolidated render after all providers and
                    // scenarios are configured. Do not enqueue a second popup/layout pass.
                    if (initialIntegrationStartupInProgress) return;
                    mainHandler.post(() -> {
                        if (destroyed) return;
                        if (binding != null) renderHomeAssistantBricks();
                        applyPopupPreferencesSafely();
                        if (binding != null) applyBrickVisibility(currentBrickSet());
                        for (String target : targets) {
                            if (target.startsWith(AutomationContract.SCOPE_DRIVER + "|")) {
                                DriverPanelService.apply(this);
                                break;
                            }
                        }
                    });
                });
        intentScenarioController = new IntentScenarioController(this, prefs, actionDispatcher);
        ensurePopupOverlayManager();

        if (!Permissions.allPermissionsGranted(this)) {
            // Locked boot and a few OEM AppOps implementations can report a temporary denial.
            // Never turn that transient state into a permanent user preference and never pull
            // the settings activity over HOME without an explicit user action.
            Log.w(TAG, "Overlay permissions are not available yet; keeping widget enabled");
            stopSelf();
            return;
        }

        instance = this;

        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        timeFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

        windowManager = getSystemService(WindowManager.class);

        // Re-evaluate brick visibility when the car SDK's asynchronous service connect finally
        // answers whether the car sensors exist — critical on the boot-autostart path, where
        // the first applyPreferences runs before the vendor service is up and would otherwise
        // hide configured car bricks until the user happens to open the settings UI.
        CarIntegrations.get(this).setAvailabilityChangedListener(() -> {
            // Only supported/unsupported car bricks changed. Connector credentials and large
            // catalogs are unrelated and must not be reparsed when the vendor service binds.
            if (binding != null) applyPreferences(false);
        });

        if (prefs.widgetEnabled.get()) {
            createOverlayView();
        } else if (prefs.driverPanelEnabled.get()) {
            // The driver rail is an independent UI surface. Keep HA/MQTT/Sprut and scenarios
            // alive without attaching the status-row window when only that rail is enabled.
            runInitialIntegrationStartup();
        } else {
            stopSelf();
        }
    }

    /** Starts the long-lived integrations once, after the first attached status frame was drawn. */
    private void runInitialIntegrationStartup() {
        integrationStartupScheduled = false;
        if (destroyed || integrationsStarted) return;
        integrationsStarted = true;
        initialIntegrationStartupInProgress = true;
        try {
            reconfigureIntegrationControllers();
        } finally {
            initialIntegrationStartupInProgress = false;
        }
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
        if (prefs.driverPanelEnabled.get()) DriverPanelService.apply(this);
        mainHandler.removeCallbacks(automationFreshnessTick);
        mainHandler.postDelayed(automationFreshnessTick, 30_000L);
    }

    private void scheduleInitialIntegrationStartupAfterFrame() {
        if (destroyed || integrationsStarted || integrationStartupScheduled) return;
        integrationStartupScheduled = true;
        try {
            Choreographer.getInstance().postFrameCallback(integrationStartupFrame);
        } catch (RuntimeException failure) {
            // Choreographer should always be available on the service main Looper. A broken OEM
            // implementation must not leave all connectors permanently stopped, however.
            Log.w(TAG, "Could not defer integrations to the first frame", failure);
            mainHandler.post(integrationStartup);
        }
    }

    /** Reconfigures each independent integration without letting one bad provider block the rest. */
    private void reconfigureIntegrationControllers() {
        runIntegrationStep("MQTT", () -> {
            if (mqttController != null) mqttController.reconfigure();
        });
        // Load the exact selected-address boundary before the phone transport can emit its
        // current state. A device change therefore clears the old Sprut switch first.
        runIntegrationStep("phone presence", () -> {
            if (phonePresenceExporter != null) phonePresenceExporter.reconfigure();
        });
        runIntegrationStep("phone", () -> {
            if (phoneController != null) phoneController.reconfigure();
        });
        runIntegrationStep("car telemetry", () -> {
            if (carTelemetryExporter != null) carTelemetryExporter.reconfigure();
        });
        runIntegrationStep("Sprut.hub", () -> {
            if (sprutController != null) sprutController.reconfigure();
        });
        runIntegrationStep("Home Assistant", () -> {
            if (haApiController != null) haApiController.reconfigure();
        });
        runIntegrationStep("visual scenarios", () -> {
            if (scenarioController != null) scenarioController.reconfigure();
        });
        runIntegrationStep("intent scenarios", () -> {
            if (intentScenarioController != null) intentScenarioController.reconfigure();
        });
    }

    private void runIntegrationStep(@NonNull String name, @NonNull Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not configure " + name, failure);
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
        if (prefs == null || !prefs.widgetEnabled.get()) return;
        ensurePopupOverlayManager();
        if (popupOverlay == null) return;
        try {
            popupOverlay.applyPreferences();
        } catch (RuntimeException failure) {
            Log.e(TAG, "Could not apply popup overlays", failure);
        }
    }

    private void ensurePopupOverlayManager() {
        if (popupOverlay != null || prefs == null || !prefs.widgetEnabled.get()
                || automationStates == null
                || actionDispatcher == null) return;
        popupOverlay = new PopupOverlayManager(this, prefs, automationStates,
                actionDispatcher, this::popupBuiltinValue);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (!destroyed && prefs != null
                && ((prefs.widgetEnabled.get() && binding == null
                && !overlayAttachRetryScheduled)
                || (!prefs.widgetEnabled.get()
                && (binding != null || popupOverlay != null)))) {
            applyPreferences(false);
        }
        if (!destroyed && intent != null
                && ScenarioTriggerReceiver.ACTION_EXECUTE_RULE.equals(intent.getAction())
                && intentScenarioController != null) {
            // Reload before lookup so a broadcast accepted from the latest device-protected
            // preferences cannot execute an older in-memory target after a settings edit.
            intentScenarioController.reconfigure();
            intentScenarioController.triggerRuleId(
                    intent.getStringExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_ID),
                    intent.getStringExtra(ScenarioTriggerReceiver.EXTRA_TRIGGER_TOKEN),
                    intent.getStringExtra(ScenarioTriggerReceiver.EXTRA_RULE_FINGERPRINT),
                    intent.getLongExtra(ScenarioTriggerReceiver.EXTRA_DEADLINE_ELAPSED, 0L));
        }
        // A sticky restart restores the long-lived widget/connectors but carries no old command.
        // Re-delivering a TOGGLE after process death would be unsafe, so null intents do nothing.
        return START_STICKY;
    }

    private void createOverlayView() {
        if (destroyed || binding != null || prefs == null || !prefs.widgetEnabled.get()
                || overlayAttachRetryScheduled
                || !Permissions.allPermissionsGranted(this)) return;
        // Create the overlay view
        LayoutInflater layoutInflater = LayoutInflater.from(this);
        binding = OverlayStatusWidgetBinding.inflate(layoutInflater);
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

        // Synchronous "size about to change" hook. Fires from {@code onMeasure} of the
        // BufferingLinearLayout — earlier than OnLayoutChangeListener and earlier than
        // LayoutTransition.startTransition, both of which run after ViewRootImpl has
        // already pushed the new wrap_content dimensions to WindowManager. Catching it
        // mid-measure lets our updateViewLayout(screenWidth) win the race so the window
        // never snaps below the children that are about to animate. The safety runnable
        // is a fallback in case no LayoutTransition actually plays.
        binding.overlayContainer.setSizeChangeHint((oldW, newW, oldH, newH) -> {
            if (params == null) return;
            if (prefs.widgetMode.get() == WIDGET_MODE_STATUS_BAR) return;
            if (newW >= oldW) return;   // grow path already works
            if (pendingBufferedTransitions > 0) return;   // some transition already buffering
            beginBufferedTransition(true);
            mainHandler.removeCallbacks(shrinkBufferSafetyClose);
            mainHandler.postDelayed(shrinkBufferSafetyClose,
                    BRICK_TRANSITION_DURATION_MS + 200);
        });

        // Universal "content size changed" animation: install a LayoutTransition with only the
        // CHANGING type enabled on the overlay container. Any child that changes its measured
        // size (clock minute rolls over, date string flips at midnight, media title scrolls to
        // a new track, status icon swaps drawable) will produce a smooth ChangeBounds-style
        // animation for itself and any siblings it pushes around. CHANGE_APPEARING / APPEARING
        // / DISAPPEARING are left disabled — those cases are handled by our explicit
        // {@link #beginVisibilityTransition} that knows about the window-buffer trick.
        // We hook startTransition / endTransition into the same buffered-transition counter so
        // the window doesn't snap mid-animation when CHANGING runs solo, and so concurrent
        // CHANGING + visibility transitions coexist correctly.
        contentLayoutTransition = new android.animation.LayoutTransition();
        android.animation.LayoutTransition lt = contentLayoutTransition;
        lt.disableTransitionType(android.animation.LayoutTransition.APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(android.animation.LayoutTransition.CHANGE_DISAPPEARING);
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING);
        lt.setDuration(android.animation.LayoutTransition.CHANGING, CONTENT_CHANGE_DURATION_MS);
        lt.setInterpolator(android.animation.LayoutTransition.CHANGING,
                new android.view.animation.AccelerateDecelerateInterpolator());
        lt.addTransitionListener(new android.animation.LayoutTransition.TransitionListener() {
            @Override
            public void startTransition(android.animation.LayoutTransition transition,
                                        android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                beginBufferedTransition(true);
            }

            @Override
            public void endTransition(android.animation.LayoutTransition transition,
                                      android.view.ViewGroup container, View view, int type) {
                if (type != android.animation.LayoutTransition.CHANGING) return;
                endBufferedTransition();
            }
        });
        binding.overlayContainer.setLayoutTransition(lt);

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
            windowManager.addView(binding.getRoot(), params);
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
            if (prefs.driverPanelEnabled.get() && !integrationsStarted) {
                runInitialIntegrationStartup();
            }
            return;
        }

        overlayAttached = true;
        mainHandler.removeCallbacks(overlayAttachRetry);
        overlayAttachRetryScheduled = false;
        overlayAttachAttempts = 0;
        // Reconnecting here used to duplicate the explicit startup reconfigure block in
        // onCreate(), including a full mapping pass over large Sprut.hub catalogs.
        applyPreferences(false);

        updateWifiStatus();
        updateGnssStatus();

        // Fade in the freshly-added view; addView itself is instant.
        binding.getRoot().animate()
                .alpha(1f)
                .setDuration(OVERLAY_FADE_DURATION_MS)
                .start();
        if (integrationsStarted) {
            // Dynamic headless -> status-row attach reuses the already-running connectors, but
            // popup windows still need to be recreated for the newly enabled status surface.
            applyPopupPreferencesSafely();
        } else {
            scheduleInitialIntegrationStartupAfterFrame();
        }
    }

    /**
     * Applies only values that can affect the overlay's first measurement.
     *
     * <p>This deliberately runs before {@link WindowManager#addView(View,
     * ViewGroup.LayoutParams)} and does not start listeners/integrations. The normal
     * {@link #applyPreferences(boolean)} pass still runs immediately after attach and remains the
     * single owner of those lifecycle side effects.</p>
     */
    private void prepareOverlayGeometryBeforeAttach() {
        // HA tiles contribute to the configured height floor too. Load their lightweight
        // persisted descriptors now so even an OEM WindowManager that measures synchronously
        // inside addView() sees the same tree as the normal post-attach preference pass.
        if (haConfigs != null) configuredMainBricks = haConfigs.loadMain();
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
        applyIndoorTempBrickSettings();
        applyOutdoorTempBrickSettings();
        renderHomeAssistantBricks(true);
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
        if (binding == null || windowManager == null) {
            overlayAttached = false;
            return;
        }
        View root = binding.getRoot();
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

        runCleanupStep("status car sensor subscriptions", () -> {
            CarIntegration car = CarIntegrations.get(this);
            car.unsubscribe(BrickType.INDOOR_TEMP);
            car.unsubscribe(BrickType.OUTDOOR_TEMP);
        });

        if (popupOverlay != null) {
            runCleanupStep("popup overlays", popupOverlay::destroy);
            popupOverlay = null;
        }

        if (binding != null) {
            binding.getRoot().animate().cancel();
            binding.overlayContainer.setLayoutTransition(null);
        }
        contentLayoutTransition = nul…17343 tokens truncated…ontSize.get());
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
        // Car bricks only contribute to the height floor when the vehicle actually renders them
        // (same isBrickSupported gate as applyBrickVisibility) — otherwise a preset from another
        // car would inflate the widget height for bricks that never appear.
        CarIntegration car = CarIntegrations.get(this);
        if (bricks.contains(BrickType.INDOOR_TEMP) && car.isBrickSupported(BrickType.INDOOR_TEMP)) {
            h = Math.max(h, textLineHeight(binding.indoorTempText, prefs.indoorTemp.fontSize.get()));
        }
        if (bricks.contains(BrickType.OUTDOOR_TEMP) && car.isBrickSupported(BrickType.OUTDOOR_TEMP)) {
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
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(activeSessionsChangedListener, component, mainHandler);
            rebindMediaControllers(mediaSessionManager.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not granted; media tracking disabled", e);
            mediaSessionManager = null;
        }
    }

    private void disableMediaTracking() {
        if (mediaSessionManager == null) return;
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

    private void updateMediaInfo() {
        if (binding == null) return;
        boolean mainMediaRequested = currentBrickSet().contains(BrickType.MEDIA)
                && isRemotelyVisible(BrickType.MEDIA);
        boolean mainMediaHidden = mainMediaRequested && isBrickHiddenByApp(BrickType.MEDIA);
        boolean mainMediaKeepsSpace = mainMediaHidden
                && prefs.hideKeepsSpaceFor(BrickType.MEDIA).get();
        boolean mainMediaVisible = mainMediaRequested && !mainMediaHidden;
        boolean popupMediaRequested = isPopupBuiltinRequested(BrickType.MEDIA);
        if (!mainMediaVisible && !mainMediaKeepsSpace && !popupMediaRequested) {
            binding.mediaContainer.setVisibility(View.GONE);
            stopMediaProgressTicker();
            lastMediaSubtitle = null;
            schedulePopupRefresh();
            return;
        }
        MediaController playing = pickActiveMediaController();
        if (playing == null) {
            binding.mediaContainer.setVisibility(View.GONE);
            stopMediaProgressTicker();
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
        PlaybackState playbackState = playing.getPlaybackState();
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
        }
        schedulePopupRefresh();
    }

    private void updateForegroundAppTracking() {
        if (binding == null) {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            return;
        }
        boolean needTracking = !hiddenInPackages.isEmpty() || anyBrickHasHideList();
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
                checkForegroundApp();
            }
        } else {
            mainHandler.removeCallbacks(foregroundAppCheckRunnable);
            usageStatsManager = null;
            lastForegroundPackage = null;
            applyOverlayVisibility(false);
        }
    }

    /**
     * Called by {@link WidgetAccessibilityService} when the per-display foreground map changes.
     * Recomputes visibility based on the package on <i>our</i> display.
     */
    public void onForegroundDisplayMapUpdated() {
        mainHandler.post(this::checkForegroundApp);
    }

    /**
     * Called by {@link WidgetAccessibilityService} when its connection state flips — connect
     * or disconnect. Re-evaluates which foreground-tracking pipeline to use (accessibility
     * push vs. UsageStats poll).
     */
    public void onForegroundTrackingPathChanged() {
        mainHandler.post(this::updateForegroundAppTracking);
    }

    private void checkForegroundApp() {
        if (hiddenInPackages.isEmpty() && !anyBrickHasHideList()) return;

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

        boolean changed = !latestPackage.equals(lastForegroundPackage);
        lastForegroundPackage = latestPackage;
        applyOverlayVisibility(hiddenInPackages.contains(latestPackage));
        if (changed && binding != null) {
            renderHomeAssistantBricks();
            applyBrickVisibility(currentBrickSet());
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
        if (wifiState == newState) {
            return;
        }
        wifiState = newState;
        updateWifiStatus();
    }

    private void updateWifiStatus() {
        if (binding != null) {
            updateIconStatus(ICON_TYPE_WIFI, binding.wifiStatusIcon, wifiState.ordinal());
        }
        schedulePopupRefresh();
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

        int outlineAlpha = iconPrefs.outlineAlpha.get();
        if (outlineAlpha > 0) {
            int haloColor = (ContextCompat.getColor(ctx, R.color.text_outline) & 0x00FFFFFF)
                    | (outlineAlpha << 24);
            icon.setOutlineColor(haloColor);
            icon.setOutlineWidth(iconPrefs.outlineWidth.get());
        } else {
            icon.setOutlineWidth(0);
        }

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

        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.notification_content)).setSmallIcon(R.drawable.ic_status_gps_good).setContentIntent(pendingIntent).setOngoing(true).build();
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
        integrationStartupScheduled = false;
        try {
            Choreographer.getInstance().removeFrameCallback(integrationStartupFrame);
        } catch (RuntimeException failure) {
            Log.w(TAG, "Could not remove deferred integration startup", failure);
        }

        mainHandler.removeCallbacksAndMessages(null);

        // Unregister derived listeners first. Connector shutdown emits synchronous stale events;
        // with the guards above and no scenario/popup listeners left, none can recreate a window.
        if (connectorValues != null) connectorValues.removeListener(crossSourceRuleListener);
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
        if (carTelemetryExporter != null) {
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
        runCleanupStep("car sensor subscriptions", () -> {
            CarIntegration car = CarIntegrations.get(this);
            car.setAvailabilityChangedListener(null);
            car.unsubscribe(BrickType.INDOOR_TEMP);
            car.unsubscribe(BrickType.OUTDOOR_TEMP);
        });
        super.onDestroy();
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

    private static Rect getBounds(View view) {
        return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
    }
}
