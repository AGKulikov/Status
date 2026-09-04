/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dezz.status.widget.AppProcessPolicy;
import dezz.status.widget.WidgetService;
import dezz.status.widget.WidgetServiceStarter;
import dezz.status.widget.automation.AutomationContract;
import dezz.status.widget.automation.AutomationState;
import dezz.status.widget.automation.AutomationStateStore;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.ConnectorValue;
import dezz.status.widget.integration.ConnectorValueRegistry;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.NavigationDataRepository;
import dezz.status.widget.navigation.NavigationBridgeStateStore;
import dezz.status.widget.navigation.NavigationRouteGeometryV2;
import dezz.status.widget.navigation.NavigationSnapshotV2;

/**
 * Shared live-data controller used by both the main-display editor and the external Presentation.
 * It never owns layout state: saving a grid change can replace the configuration without tearing
 * down media, vehicle, navigation or smart-home sessions.
 */
public final class HudRuntimeData {
    public interface Listener { void onHudDataChanged(); }

    private static final long HOST_ATTACH_RETRY_MS = 1_000L;
    private static final long HOST_LIVENESS_CHECK_MS = 30_000L;
    private static final long CLOCK_TICK_SLOP_MS = 25L;
    /** Direct snapshots arrive at 10 Hz; three seconds tolerates stalls but rejects dead routes. */
    private static final long DIRECT_NAVIGATION_FRESH_MS = 3_000L;

    @NonNull private final Context context;
    @NonNull private final Listener listener;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private ExecutorService navigationWorker;
    @NonNull private final AtomicBoolean navigationReadQueued = new AtomicBoolean();
    private boolean navigationReadPending;
    @NonNull private final AtomicBoolean directNavigationWakePosted = new AtomicBoolean();
    @NonNull private final LauncherMediaController mediaController;
    @NonNull private final CarIntegration carIntegration;
    @NonNull private AutomationStateStore retainedAutomation;
    @NonNull private final Map<String, AutomationState> retainedAutomationCache = new HashMap<>();
    @NonNull private final Map<String, CarIntegration.TelemetryValue> telemetry = new HashMap<>();
    @NonNull private final Map<String, ConnectorValue> connectorValues = new HashMap<>();
    /** Reused on the main thread; creating DecimalFormat for every speed redraw is expensive. */
    @NonNull private final DecimalFormat compactNumberFormat = new DecimalFormat("0.##");
    @NonNull private HudPanelConfig config;
    @Nullable private HudNavigationState navigation;
    @Nullable private LauncherMediaController.Snapshot media;
    private boolean mediaTimelineVisible;
    @Nullable private WidgetService attachedHost;
    @Nullable private String cachedAppVersion;
    private final boolean isolatedHudProcess;
    private volatile boolean navigationDataNeeded;
    private boolean started;
    private boolean navigationReceiverRegistered;
    private long navigationFreshUntilElapsedMs;
    private long navigationScheduledExpiryElapsedMs;
    private boolean navigationExpiryPosted;
    private final CarIntegration.TelemetryListener telemetryListener = value -> runOnMain(() -> {
        if (!started) return;
        telemetry.put(value.id, value);
        notifyChanged();
    });
    private final ConnectorValueRegistry.Listener connectorListener = changed -> {
        List<ConnectorValue> copy = new ArrayList<>(changed);
        main.post(() -> {
            if (!started) return;
            mergeConnectorValues(copy);
            notifyChanged();
        });
    };
    private final BroadcastReceiver navigationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (NavigationDataRepository.ACTION_UPDATED.equals(intent.getAction())) {
                refreshNavigation();
            }
        }
    };
    private final Runnable directNavigationWake = () -> {
        directNavigationWakePosted.set(false);
        if (started && navigationDataNeeded) refreshNavigation();
    };
    private final NavigationBridgeStateStore.Listener directNavigationListener = () -> {
        if (!navigationDataNeeded) return;
        if (!directNavigationWakePosted.compareAndSet(false, true)) return;
        if (!main.post(directNavigationWake)) directNavigationWakePosted.set(false);
    };
    private final Runnable navigationExpiry = new Runnable() {
        @Override public void run() {
            navigationExpiryPosted = false;
            navigationScheduledExpiryElapsedMs = 0L;
            if (!started || navigationFreshUntilElapsedMs <= 0L) return;
            long remaining = navigationFreshUntilElapsedMs - SystemClock.elapsedRealtime();
            if (remaining > 0L) {
                navigationExpiryPosted = main.postDelayed(this, remaining);
                navigationScheduledExpiryElapsedMs = navigationFreshUntilElapsedMs;
                return;
            }
            navigationFreshUntilElapsedMs = 0L;
            if (navigation != null && navigation.direct) {
                navigation = null;
                notifyChanged();
                refreshNavigation();
            }
        }
    };
    /** Main-process editor only: retry promptly until its already-started WidgetService appears. */
    private final Runnable hostProbe = new Runnable() {
        @Override public void run() {
            if (!started || isolatedHudProcess) return;
            boolean changed = attachIntegrationHost();
            if (changed) notifyChanged();
            main.postDelayed(this, attachedHost == null
                    ? HOST_ATTACH_RETRY_MS : HOST_LIVENESS_CHECK_MS);
        }
    };
    /** CLOCK has minute precision, so it only invalidates at the next visible minute boundary. */
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!started || !hasEnabledClock()) return;
            notifyChanged();
            scheduleClockTick();
        }
    };

    public HudRuntimeData(@NonNull Context context, @NonNull HudPanelConfig config,
                          @NonNull Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.config = config;
        this.listener = listener;
        isolatedHudProcess = AppProcessPolicy.isHudProcess();
        mediaController = new LauncherMediaController(this.context,
                state -> runOnMain(() -> acceptMediaSnapshot(state)));
        carIntegration = CarIntegrations.get(this.context);
        retainedAutomation = new AutomationStateStore(this.context);
    }

    public void start() {
        if (started) return;
        started = true;
        navigationDataNeeded = hasEnabledNavigationData();
        navigationWorker = newNavigationWorker();
        NavigationBridgeStateStore.addListener(directNavigationListener);
        reconcileMediaController();
        try {
            ContextCompat.registerReceiver(context, navigationReceiver,
                    new IntentFilter(NavigationDataRepository.ACTION_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            navigationReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            navigationReceiverRegistered = false;
        }
        if (navigationDataNeeded) refreshNavigation();
        reconfigureVehicleSubscription();
        WidgetServiceStarter.startIfNeeded(context);
        if (!isolatedHudProcess) {
            attachIntegrationHost();
            main.removeCallbacks(hostProbe);
            main.postDelayed(hostProbe, attachedHost == null
                    ? HOST_ATTACH_RETRY_MS : HOST_LIVENESS_CHECK_MS);
        }
        scheduleClockTick();
    }

    public void stop() {
        if (!started) return;
        started = false;
        navigationDataNeeded = false;
        main.removeCallbacks(hostProbe);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(navigationExpiry);
        navigationExpiryPosted = false;
        navigationFreshUntilElapsedMs = 0L;
        navigationScheduledExpiryElapsedMs = 0L;
        main.removeCallbacks(directNavigationWake);
        directNavigationWakePosted.set(false);
        mediaController.stop();
        NavigationBridgeStateStore.removeListener(directNavigationListener);
        carIntegration.unsubscribeTelemetry(telemetryListener);
        WidgetService host = attachedHost;
        attachedHost = null;
        if (host != null) host.removeConnectorValueListener(connectorListener);
        if (navigationReceiverRegistered) {
            try { context.unregisterReceiver(navigationReceiver); }
            catch (RuntimeException ignored) {}
            navigationReceiverRegistered = false;
        }
        ExecutorService worker = navigationWorker;
        navigationWorker = null;
        navigationReadQueued.set(false);
        navigationReadPending = false;
        if (worker != null) worker.shutdownNow();
        retainedAutomationCache.clear();
    }

    public void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        navigationDataNeeded = hasEnabledNavigationData();
        if (!navigationDataNeeded) {
            navigation = null;
            navigationFreshUntilElapsedMs = 0L;
            main.removeCallbacks(navigationExpiry);
            navigationExpiryPosted = false;
            navigationScheduledExpiryElapsedMs = 0L;
            navigationReadPending = false;
            main.removeCallbacks(directNavigationWake);
            directNavigationWakePosted.set(false);
        }
        if (started) {
            reconcileMediaController();
            if (navigationDataNeeded) refreshNavigation();
            reconfigureVehicleSubscription();
            scheduleClockTick();
        }
        notifyChanged();
    }

    /** Do not query the OEM MediaSession service when this HUD layout renders no media data. */
    private void reconcileMediaController() {
        if (!started) {
            mediaTimelineVisible = false;
            mediaController.stop();
            return;
        }
        boolean hasMedia = false;
        boolean hasTimeline = false;
        for (HudElementConfig item : config.elements) {
            if (item.enabled && isMediaElement(item.type)) {
                hasMedia = true;
                hasTimeline |= item.type == HudElementType.MEDIA_TIMER;
            }
        }
        mediaTimelineVisible = hasTimeline;
        if (hasMedia) {
            mediaController.start();
            return;
        }
        media = null;
        mediaController.stop();
    }

    private void acceptMediaSnapshot(@NonNull LauncherMediaController.Snapshot next) {
        if (!started) return;
        LauncherMediaController.Snapshot previous = media;
        media = next;
        if (previous == null || mediaSnapshotChanged(previous, next, mediaTimelineVisible)) {
            notifyChanged();
        }
    }

    /** Ignore the one-second playback-position tick when no timeline is drawn on this HUD. */
    private static boolean mediaSnapshotChanged(
            @NonNull LauncherMediaController.Snapshot before,
            @NonNull LauncherMediaController.Snapshot after,
            boolean includeTimeline) {
        return !before.title.equals(after.title)
                || !before.artist.equals(after.artist)
                || !before.album.equals(after.album)
                || !before.application.equals(after.application)
                || before.artwork != after.artwork
                || before.durationMs != after.durationMs
                || (includeTimeline && before.positionMs != after.positionMs)
                || before.playing != after.playing
                || before.available != after.available
                || before.likeAvailable != after.likeAvailable
                || (before.liked == null ? after.liked != null
                        : !before.liked.equals(after.liked))
                || before.volumePercent != after.volumePercent;
    }

    private static boolean isMediaElement(@NonNull HudElementType type) {
        switch (type) {
            case MEDIA_ARTWORK:
            case MEDIA_COMBINED:
            case MEDIA_TITLE:
            case MEDIA_ARTIST:
            case MEDIA_ALBUM:
            case MEDIA_APPLICATION:
            case MEDIA_TIMER:
            case MEDIA_VOLUME:
                return true;
            default:
                return false;
        }
    }

    /** Reloads file-backed automation state after a command crosses into the isolated HUD process. */
    public void refreshCrossProcessState() {
        runOnMain(() -> {
            retainedAutomation = new AutomationStateStore(context);
            retainedAutomationCache.clear();
            refreshNavigation();
            notifyChanged();
        });
    }

    @Nullable public HudNavigationState navigation() { return navigation; }
    @Nullable public LauncherMediaController.Snapshot media() { return media; }

    @NonNull
    public AutomationState automation(@NonNull HudElementConfig item) {
        WidgetService host = attachedHost;
        if (host != null) return host.hudAutomationState(item.automationId);
        // Main-process editor state may include volatile local scenario overrides, so only the
        // isolated HUD caches its explicitly invalidated, cross-process preference snapshot.
        if (!isolatedHudProcess) {
            return retainedAutomation.get(AutomationContract.SCOPE_HUD, item.automationId);
        }
        AutomationState cached = retainedAutomationCache.get(item.automationId);
        if (cached != null) return cached;
        AutomationState loaded = retainedAutomation.get(
                AutomationContract.SCOPE_HUD, item.automationId);
        retainedAutomationCache.put(item.automationId, loaded);
        return loaded;
    }

    @Nullable
    public CarIntegration.TelemetryValue telemetry(@NonNull String metricId) {
        return telemetry.get(metricId);
    }

    @Nullable
    public ConnectorValue connector(@Nullable SourceBinding binding) {
        if (binding == null || !binding.isBound()) return null;
        return connectorValues.get(connectorKey(binding.connectorType.jsonName(),
                binding.connectorId, binding.resourceId));
    }

    @NonNull
    public String textFor(@NonNull HudElementConfig item) {
        AutomationState automation = automation(item);
        if (automation.text != null) return automation.text;
        switch (item.type) {
            case CLOCK:
                return clock(item.options.optString("clockMode", "SYSTEM"));
            case MEDIA_COMBINED:
                if (media == null) return "Музыка не воспроизводится";
                return join(media.title, media.artist, " · ");
            case MEDIA_TITLE:
                return media == null ? "—" : media.title;
            case MEDIA_ARTIST:
                return media == null ? "—" : emptyDash(media.artist);
            case MEDIA_ALBUM:
                return media == null ? "—" : emptyDash(media.album);
            case MEDIA_APPLICATION:
                return media == null ? "—" : emptyDash(media.application);
            case MEDIA_TIMER:
                return media == null ? "0:00 / 0:00"
                        : duration(media.positionMs) + " / " + duration(media.durationMs);
            case MEDIA_VOLUME:
                return media == null ? "—" : media.volumePercent + "%";
            case NAV_MANEUVER_TITLE:
                return navigation == null ? "Маршрут не активен"
                        : firstNonEmpty(navigation.maneuverTitle, navigation.maneuverText, "Маршрут");
            case NAV_MANEUVER_SUBTEXT:
                return navigation == null ? "—" : emptyDash(navigation.maneuverSubtext);
            case NAV_STREET:
                return navigation == null ? "—" : emptyDash(navigation.street);
            case NAV_DESTINATION:
                return navigation == null ? "—" : emptyDash(navigation.destination);
            case NAV_TURN_DISTANCE:
                return navigation == null ? "—"
                        : firstNonEmpty(navigation.turnDistance, navigation.distance, "—");
            case NAV_DISTANCE_LEFT:
                return navigation == null ? "—" : emptyDash(navigation.distance);
            case NAV_TIME_LEFT:
                return navigation == null ? "—" : emptyDash(navigation.duration);
            case NAV_ARRIVAL_TIME:
                return navigation == null ? "—" : emptyDash(navigation.arrival);
            case NAV_LANE_DISTANCE:
                return navigation == null ? "—" : emptyDash(navigation.laneDistance);
            case NAV_COMBINED:
                if (navigation == null) return "Маршрут не активен";
                return join(navigation.turnDistance, navigation.maneuverNextRoad, "\n");
            case NAV_ROUTE_SUMMARY:
                if (navigation == null) return "—";
                return join(navigation.distance,
                        join(navigation.arrival, navigation.duration, " · "), " · ");
            case NAV_TRIP_PROGRESS:
                if (navigation == null) return "—";
                String mode = item.options.optString("progressMode", "COMBINED");
                if ("DISTANCE".equals(mode)) return emptyDash(navigation.distance);
                if ("TIME".equals(mode)) return emptyDash(navigation.duration);
                if ("ARRIVAL".equals(mode)) return emptyDash(navigation.arrival);
                return join(navigation.distance,
                        join(navigation.duration, navigation.arrival, " · "), " · ");
            case NAV_SPEED_LIMIT:
                return navigation == null ? "—" : emptyDash(navigation.speedLimit);
            case NAV_TRAFFIC_LIGHTS:
                if (navigation == null) return "—";
                return join(navigation.trafficColor, navigation.trafficCountdown, " ");
            case NAV_TRAFFIC_JAM:
                if (navigation == null || !navigation.trafficJamAvailable) return "";
                return "Пробка на " + navigation.trafficJamDuration
                        + " (" + navigation.trafficJamDistance + ")";
            case NAV_SPEED:
                if (navigation != null && Double.isFinite(navigation.speedKmh)) {
                    return applyFormat(item.textFormat, navigation.speedKmh,
                            item.unit.isEmpty() ? "км/ч" : item.unit);
                }
                return telemetryText(item);
            case NAV_JAM_PROGRESS:
                return navigation == null ? "—" : join(navigation.duration,
                        navigation.distance, " · ");
            case CUSTOM_TEXT:
                return item.options.optString("customText", item.title);
            case UPDATE_STATUS:
                return "Natro " + appVersion();
            case SMART_HOME_STATUS:
            case CONNECTOR_VALUE:
                return connectorText(item);
            case VEHICLE_TELEMETRY:
            default:
                return telemetryText(item);
        }
    }

    public double numericValue(@NonNull HudElementConfig item) {
        if ((item.type == HudElementType.NAV_TRIP_PROGRESS
                || item.type == HudElementType.NAV_ROUTE_SUMMARY) && navigation != null) {
            return navigation.tripProgress;
        }
        if (item.type == HudElementType.NAV_SPEED && navigation != null
                && Double.isFinite(navigation.speedKmh)) {
            return navigation.speedKmh;
        }
        if (item.type == HudElementType.MEDIA_TIMER && media != null && media.durationMs > 0) {
            return Math.max(0d, Math.min(1d, media.positionMs / (double) media.durationMs));
        }
        CarIntegration.TelemetryValue sample = telemetry.get(metricId(item));
        if (sample == null) return Double.NaN;
        if (item.type == HudElementType.NAV_SPEED_LIMIT) return sample.value * 3.72d;
        return normalizedVehicleValue(item, sample.value);
    }

    public boolean inPark() {
        CarIntegration.TelemetryValue gear = telemetry.get("ISensor.gear");
        return gear != null && Math.round(gear.value) == 2_097_712L;
    }

    public boolean active(@NonNull HudElementConfig item) {
        switch (item.type) {
            case HIGH_BEAM:
            case AUTO_HOLD:
            case TURN_SIGNAL_LEFT:
            case TURN_SIGNAL_RIGHT:
                double value = numericValue(item);
                return Double.isFinite(value) && value >= .5d;
            default:
                return true;
        }
    }

    /** Live surfaces never draw a navigation shell when its own semantic payload is absent. */
    public boolean navigationElementAvailable(@NonNull HudElementConfig item) {
        if (item.type == HudElementType.NAV_MAP) return true;
        if (item.type == HudElementType.NAV_SPEED) {
            HudNavigationState state = navigation;
            return (state != null && state.hasDataFor(item.type))
                    || Double.isFinite(numericValue(item));
        }
        HudNavigationState state = navigation;
        return state != null && state.hasDataFor(item.type);
    }

    @NonNull
    private String connectorText(@NonNull HudElementConfig item) {
        ConnectorValue value = connector(item.sourceBinding);
        if (value == null || !value.available || !value.readable) return "Недоступно";
        Object resolved = value.resolveValue(item.sourceBinding == null
                ? "" : item.sourceBinding.valuePath);
        String suffix = item.unit;
        if (suffix.isEmpty() && item.sourceBinding != null) {
            suffix = item.sourceBinding.unitSuffix;
        }
        if (suffix.isEmpty()) suffix = value.unit;
        return applyFormat(item.textFormat, resolved, suffix);
    }

    @NonNull
    private String telemetryText(@NonNull HudElementConfig item) {
        String id = metricId(item);
        CarIntegration.TelemetryValue value = telemetry.get(id);
        if (item.type == HudElementType.FUEL_REFILL) {
            CarIntegration.TelemetryValue fuel = telemetry.get("ISensor.fuel_level");
            CarIntegration.TelemetryValue capacity = telemetry.get("ICarInfo.fuel_capacity");
            if (fuel == null) return "—";
            double litres = fuel.value / 1_000d;
            double tank = capacity == null
                    ? item.options.optDouble("tankCapacityLitres", 64d)
                    : (capacity.value > 500d ? capacity.value / 1_000d : capacity.value);
            return applyFormat(item.textFormat, Math.max(0d, tank - litres),
                    item.unit.isEmpty() ? "л" : item.unit);
        }
        if (value == null) return "—";
        double number = normalizedVehicleValue(item, value.value);
        if (item.type == HudElementType.GEAR) return gear(number, item);
        if (item.type == HudElementType.HIGH_BEAM
                || item.type == HudElementType.AUTO_HOLD
                || item.type == HudElementType.TURN_SIGNAL_LEFT
                || item.type == HudElementType.TURN_SIGNAL_RIGHT) {
            return number >= .5d ? "Вкл" : "Выкл";
        }
        String unit = item.unit.isEmpty() ? defaultUnit(item, value.unit) : item.unit;
        return applyFormat(item.textFormat, number, unit);
    }

    private double normalizedVehicleValue(HudElementConfig item, double value) {
        switch (item.type) {
            case CAR_SPEED:
            case NAV_SPEED:
                return value * 3.72d;
            case FUEL_LEVEL:
                return value / 1_000d;
            case FUEL_CAPACITY:
                return value > 500d ? value / 1_000d : value;
            case RPM:
                return item.options.optBoolean("divideByThousand", false) ? value / 1_000d : value;
            default:
                return value;
        }
    }

    @NonNull
    private static String defaultUnit(HudElementConfig item, String sourceUnit) {
        switch (item.type) {
            case CAR_SPEED:
            case NAV_SPEED:
                return "км/ч";
            case FUEL_LEVEL:
            case FUEL_REFILL: return "л";
            case FUEL_RANGE: return sourceUnit.isEmpty() ? "км" : sourceUnit;
            case FUEL_RANGE_TOTAL: return sourceUnit.isEmpty() ? "км" : sourceUnit;
            case FUEL_CAPACITY: return sourceUnit.isEmpty() || "raw".equals(sourceUnit)
                    ? "л" : sourceUnit;
            case RPM: return item.options.optBoolean("divideByThousand", false) ? "×1000" : "об/мин";
            case ODOMETER: return sourceUnit.isEmpty() || "raw".equals(sourceUnit)
                    ? "км" : sourceUnit;
            default: return "raw".equals(sourceUnit) ? "" : sourceUnit;
        }
    }

    @NonNull
    private static String gear(double raw, HudElementConfig item) {
        int value = (int) Math.round(raw);
        String text;
        if (value == 2_097_680) text = "N";
        else if (value == 2_097_696) text = "D";
        else if (value == 2_097_712) text = "P";
        else if (value == 2_097_728) text = "R";
        else if (value >= 2_097_665 && value <= 2_097_674) {
            text = "D" + (value - 2_097_664);
        } else if (value <= -10_001 && value >= -10_010) {
            text = "M" + (-10_000 - value);
        } else {
            text = Integer.toString(value);
        }
        if (item.options.optBoolean("letterOnly", false)) {
            return withoutDigits(text);
        }
        if (item.options.optBoolean("numberOnly", false)) {
            String number = digitsOnly(text);
            return number.isEmpty() ? text : number;
        }
        return text;
    }

    @NonNull
    private String applyFormat(String format, @Nullable Object raw, String unit) {
        if (raw == null) return "—";
        String value;
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            try {
                if (format != null && format.contains("%") && !"%s".equals(format)) {
                    value = String.format(Locale.getDefault(), format, number);
                } else {
                    value = compactNumberFormat.format(number);
                }
            } catch (RuntimeException ignored) {
                value = compactNumberFormat.format(number);
            }
        } else {
            value = String.valueOf(raw);
            if (format != null && !format.isEmpty() && !"%s".equals(format)) {
                try { value = String.format(Locale.getDefault(), format, value); }
                catch (RuntimeException ignored) {}
            }
        }
        return unit == null || unit.trim().isEmpty() ? value : value + " " + unit.trim();
    }

    @NonNull
    private static String withoutDigits(@NonNull String value) {
        int firstDigit = -1;
        for (int index = 0; index < value.length(); index++) {
            if (Character.isDigit(value.charAt(index))) {
                firstDigit = index;
                break;
            }
        }
        if (firstDigit < 0) return value;
        StringBuilder result = new StringBuilder(value.length());
        result.append(value, 0, firstDigit);
        for (int index = firstDigit + 1; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isDigit(character)) result.append(character);
        }
        return result.toString();
    }

    @NonNull
    private static String digitsOnly(@NonNull String value) {
        boolean allDigits = !value.isEmpty();
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                allDigits = false;
                break;
            }
        }
        if (allDigits) return value;
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isDigit(character)) result.append(character);
        }
        return result.toString();
    }

    @NonNull
    private static String clock(String mode) {
        // The head-unit system locale uses 24-hour time in the supported vehicle profiles.
        // Explicit 12H/24H remains available per element, matching mHUD's three clock modes.
        String pattern = "12H".equalsIgnoreCase(mode) ? "h:mm" : "HH:mm";
        return new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
    }

    @NonNull
    private static String duration(long millis) {
        long seconds = Math.max(0L, millis / 1_000L);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    @NonNull
    private static String join(@Nullable String left, @Nullable String right, String separator) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        if (a.isEmpty()) return b.isEmpty() ? "—" : b;
        if (b.isEmpty()) return a;
        return a + separator + b;
    }

    @NonNull
    private static String firstNonEmpty(String first, String second, String fallback) {
        if (first != null && !first.trim().isEmpty()) return first.trim();
        if (second != null && !second.trim().isEmpty()) return second.trim();
        return fallback;
    }

    @NonNull
    private static String emptyDash(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? "—" : value.trim();
    }

    @NonNull
    private String metricId(@NonNull HudElementConfig item) {
        return item.telemetryMetricId.isEmpty() ? item.type.defaultMetricId
                : item.telemetryMetricId;
    }

    private void reconfigureVehicleSubscription() {
        HashSet<String> ids = new HashSet<>();
        for (HudElementConfig item : config.elements) {
            if (!item.enabled) continue;
            String id = metricId(item);
            if (!id.isEmpty()) ids.add(id);
            if (item.type == HudElementType.FUEL_REFILL) {
                ids.add("ISensor.fuel_level");
                ids.add("ICarInfo.fuel_capacity");
                if (item.options.optBoolean("onlyInPark", true)) ids.add("ISensor.gear");
            }
        }
        carIntegration.subscribeTelemetry(Collections.unmodifiableSet(ids), telemetryListener);
    }

    private boolean attachIntegrationHost() {
        if (isolatedHudProcess) return false;
        WidgetService current = WidgetService.getInstance();
        if (current == attachedHost) return false;
        WidgetService previous = attachedHost;
        attachedHost = null;
        if (previous != null) previous.removeConnectorValueListener(connectorListener);
        connectorValues.clear();
        if (current != null) {
            List<ConnectorValue> snapshot = current.addConnectorValueListener(connectorListener);
            mergeConnectorValues(snapshot);
            attachedHost = current;
        }
        return true;
    }

    private void mergeConnectorValues(@NonNull Collection<ConnectorValue> values) {
        for (ConnectorValue value : values) {
            connectorValues.put(connectorKey(value.connectorType.jsonName(),
                    value.connectorId, value.resourceId), value);
        }
    }

    @NonNull
    private static String connectorKey(String type, String connectorId, String resourceId) {
        return type + '\u0000' + connectorId + '\u0000' + resourceId;
    }

    private void refreshNavigation() {
        if (!started || !navigationDataNeeded) return;
        if (!navigationReadQueued.compareAndSet(false, true)) {
            navigationReadPending = true;
            return;
        }
        navigationReadPending = false;
        ExecutorService worker = navigationWorker;
        if (worker == null) {
            navigationReadQueued.set(false);
            return;
        }
        NavigationSnapshotV2 direct = NavigationBridgeStateStore.snapshot();
        boolean directSource = direct != null
                || !NavigationBridgeStateStore.sessionId().isEmpty();
        NavigationRouteGeometryV2 directRoute = direct == null
                ? null : NavigationBridgeStateStore.routeGeometry();
        Bitmap directManeuverArtwork = direct == null
                ? null : NavigationBridgeStateStore.maneuverArtworkFor(direct);
        HudNavigationState previous = navigation;
        try {
            worker.execute(() -> {
                HudNavigationState resolved = null;
                boolean directFresh = direct != null && direct.isFreshAt(
                        System.currentTimeMillis(), DIRECT_NAVIGATION_FRESH_MS);
                if (directFresh) {
                    resolved = HudNavigationState.fromBridge(
                            direct, directRoute, previous, directManeuverArtwork);
                } else if (!directSource) {
                    NavigationDataRepository.Snapshot legacy = null;
                    try { legacy = NavigationDataRepository.read(context); }
                    catch (RuntimeException ignored) {}
                    if (legacy != null) resolved = HudNavigationState.fromLegacy(legacy);
                }
                HudNavigationState result = resolved;
                long now = System.currentTimeMillis();
                long remaining = directFresh ? Math.min(DIRECT_NAVIGATION_FRESH_MS,
                        direct.sourceTimestampMs + DIRECT_NAVIGATION_FRESH_MS + 1L - now) : 0L;
                long freshUntil = directFresh ? SystemClock.elapsedRealtime()
                        + Math.max(1L, remaining) : 0L;
                main.post(() -> {
                    navigationReadQueued.set(false);
                    if (!started || !navigationDataNeeded) {
                        navigationReadPending = false;
                        return;
                    }
                    navigation = result;
                    scheduleNavigationExpiry(freshUntil);
                    notifyChanged();
                    if (navigationReadPending) refreshNavigation();
                });
            });
        } catch (RuntimeException stopped) {
            navigationReadQueued.set(false);
            navigationReadPending = false;
        }
    }

    private void scheduleNavigationExpiry(long freshUntilElapsedMs) {
        navigationFreshUntilElapsedMs = Math.max(0L, freshUntilElapsedMs);
        if (navigationFreshUntilElapsedMs <= 0L) {
            main.removeCallbacks(navigationExpiry);
            navigationExpiryPosted = false;
            navigationScheduledExpiryElapsedMs = 0L;
            return;
        }
        if (navigationExpiryPosted
                && navigationScheduledExpiryElapsedMs <= navigationFreshUntilElapsedMs) return;
        main.removeCallbacks(navigationExpiry);
        long delay = Math.max(1L,
                navigationFreshUntilElapsedMs - SystemClock.elapsedRealtime());
        navigationExpiryPosted = main.postDelayed(navigationExpiry, delay);
        navigationScheduledExpiryElapsedMs = navigationFreshUntilElapsedMs;
    }

    private void notifyChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(this::notifyChanged);
            return;
        }
        if (!started) return;
        try { listener.onHudDataChanged(); }
        catch (RuntimeException ignored) {}
    }

    private void runOnMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private boolean hasEnabledClock() {
        for (HudElementConfig item : config.elements) {
            if (item.enabled && item.type == HudElementType.CLOCK) return true;
        }
        return false;
    }

    /** The native NAV_MAP Surface does not consume the text/lanes/traffic Canvas model. */
    private boolean hasEnabledNavigationData() {
        for (HudElementConfig item : config.elements) {
            if (item.enabled && item.type != HudElementType.NAV_MAP
                    && item.type.name().startsWith("NAV_")) return true;
        }
        return false;
    }

    private void scheduleClockTick() {
        main.removeCallbacks(clockTick);
        if (!started || !hasEnabledClock()) return;
        long wallMillis = System.currentTimeMillis();
        long delay = 60_000L - Math.floorMod(wallMillis, 60_000L) + CLOCK_TICK_SLOP_MS;
        main.postDelayed(clockTick, delay);
    }

    @NonNull
    private String appVersion() {
        if (cachedAppVersion != null) return cachedAppVersion;
        String resolved;
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            resolved = version == null || version.trim().isEmpty() ? "—" : version.trim();
        } catch (android.content.pm.PackageManager.NameNotFoundException
                 | RuntimeException ignored) {
            resolved = "—";
        }
        cachedAppVersion = resolved;
        return resolved;
    }

    @NonNull
    private static ExecutorService newNavigationWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(() -> {
                try {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                } catch (RuntimeException ignored) {}
                runnable.run();
            }, "hud-navigation-reader");
            thread.setDaemon(true);
            return thread;
        });
    }
}
