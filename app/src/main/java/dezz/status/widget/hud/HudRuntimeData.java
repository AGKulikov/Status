/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

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

/**
 * Shared live-data controller used by both the main-display editor and the external Presentation.
 * It never owns layout state: saving a grid change can replace the configuration without tearing
 * down media, vehicle, navigation or smart-home sessions.
 */
public final class HudRuntimeData {
    public interface Listener { void onHudDataChanged(); }

    @NonNull private final Context context;
    @NonNull private final Listener listener;
    @NonNull private final Handler main = new Handler(Looper.getMainLooper());
    @Nullable private ExecutorService navigationWorker;
    @NonNull private final AtomicBoolean navigationReadQueued = new AtomicBoolean();
    @NonNull private final LauncherMediaController mediaController;
    @NonNull private final CarIntegration carIntegration;
    @NonNull private AutomationStateStore retainedAutomation;
    @NonNull private final NavigatorMapFrameProvider navigatorMap;
    @NonNull private final Map<String, CarIntegration.TelemetryValue> telemetry = new HashMap<>();
    @NonNull private final Map<String, ConnectorValue> connectorValues = new HashMap<>();
    @NonNull private HudPanelConfig config;
    @Nullable private NavigationDataRepository.Snapshot navigation;
    @Nullable private LauncherMediaController.Snapshot media;
    @Nullable private WidgetService attachedHost;
    private boolean started;
    private boolean navigationReceiverRegistered;
    @NonNull private final NavigatorMapFrameProvider.Listener navigatorMapListener =
            this::notifyChanged;

    private final CarIntegration.TelemetryListener telemetryListener = value -> {
        telemetry.put(value.id, value);
        notifyChanged();
    };
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
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!started) return;
            attachIntegrationHost();
            notifyChanged();
            main.postDelayed(this, 1_000L);
        }
    };

    public HudRuntimeData(@NonNull Context context, @NonNull HudPanelConfig config,
                          @NonNull Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.config = config;
        this.listener = listener;
        mediaController = new LauncherMediaController(this.context, state -> {
            media = state;
            notifyChanged();
        });
        carIntegration = CarIntegrations.get(this.context);
        retainedAutomation = new AutomationStateStore(this.context);
        navigatorMap = NavigatorMapFrameProvider.get(this.context);
    }

    public void start() {
        if (started) return;
        started = true;
        navigationWorker = newNavigationWorker();
        mediaController.start();
        try {
            ContextCompat.registerReceiver(context, navigationReceiver,
                    new IntentFilter(NavigationDataRepository.ACTION_UPDATED),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            navigationReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            navigationReceiverRegistered = false;
        }
        refreshNavigation();
        navigatorMap.attach(config, navigatorMapListener);
        reconfigureVehicleSubscription();
        WidgetServiceStarter.startIfNeeded(context);
        attachIntegrationHost();
        main.removeCallbacks(ticker);
        main.post(ticker);
    }

    public void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(ticker);
        navigatorMap.detach(navigatorMapListener);
        mediaController.stop();
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
        if (worker != null) worker.shutdownNow();
    }

    public void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        if (started) {
            navigatorMap.update(next, navigatorMapListener);
            reconfigureVehicleSubscription();
        }
        notifyChanged();
    }

    /** Reloads file-backed automation state after a command crosses into the isolated HUD process. */
    public void refreshCrossProcessState() {
        runOnMain(() -> {
            retainedAutomation = new AutomationStateStore(context);
            refreshNavigation();
            notifyChanged();
        });
    }

    @Nullable public NavigationDataRepository.Snapshot navigation() { return navigation; }
    @Nullable public LauncherMediaController.Snapshot media() { return media; }
    @Nullable public android.graphics.Bitmap navigatorMapFrame() { return navigatorMap.frame(); }
    @NonNull public String navigatorMapState() { return navigatorMap.state(); }

    @NonNull
    public AutomationState automation(@NonNull HudElementConfig item) {
        WidgetService host = attachedHost;
        if (host != null) return host.hudAutomationState(item.automationId);
        return retainedAutomation.get(AutomationContract.SCOPE_HUD, item.automationId);
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
                return join(firstNonEmpty(navigation.maneuverTitle, navigation.maneuverText, ""),
                        navigation.distance, "\n");
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
            case NAV_SPEED:
                return telemetryText(item);
            case NAV_JAM_PROGRESS:
                return navigation == null ? "—" : join(navigation.duration,
                        navigation.distance, " · ");
            case CUSTOM_TEXT:
                return item.options.optString("customText", item.title);
            case UPDATE_STATUS:
                return "Status Widget " + appVersion();
            case SMART_HOME_STATUS:
            case CONNECTOR_VALUE:
                return connectorText(item);
            case VEHICLE_TELEMETRY:
            default:
                return telemetryText(item);
        }
    }

    public double numericValue(@NonNull HudElementConfig item) {
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
            return text.replaceAll("[0-9]", "");
        }
        if (item.options.optBoolean("numberOnly", false)) {
            String number = text.replaceAll("\\D", "");
            return number.isEmpty() ? text : number;
        }
        return text;
    }

    @NonNull
    private static String applyFormat(String format, @Nullable Object raw, String unit) {
        if (raw == null) return "—";
        String value;
        if (raw instanceof Number) {
            double number = ((Number) raw).doubleValue();
            try {
                if (format != null && format.contains("%") && !"%s".equals(format)) {
                    value = String.format(Locale.getDefault(), format, number);
                } else {
                    value = new DecimalFormat("0.##").format(number);
                }
            } catch (RuntimeException ignored) {
                value = new DecimalFormat("0.##").format(number);
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

    private void attachIntegrationHost() {
        WidgetService current = WidgetService.getInstance();
        if (current == attachedHost) return;
        WidgetService previous = attachedHost;
        attachedHost = null;
        if (previous != null) previous.removeConnectorValueListener(connectorListener);
        connectorValues.clear();
        if (current != null) {
            List<ConnectorValue> snapshot = current.addConnectorValueListener(connectorListener);
            mergeConnectorValues(snapshot);
            attachedHost = current;
        }
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
        if (!started || !navigationReadQueued.compareAndSet(false, true)) return;
        ExecutorService worker = navigationWorker;
        if (worker == null) {
            navigationReadQueued.set(false);
            return;
        }
        try {
            worker.execute(() -> {
                NavigationDataRepository.Snapshot next = null;
                try { next = NavigationDataRepository.read(context); }
                catch (RuntimeException ignored) {}
                NavigationDataRepository.Snapshot result = next;
                main.post(() -> {
                    navigationReadQueued.set(false);
                    if (!started) return;
                    navigation = result;
                    navigatorMap.updateRoutePoints(
                            result == null ? "" : result.routePoints);
                    notifyChanged();
                });
            });
        } catch (RuntimeException stopped) {
            navigationReadQueued.set(false);
        }
    }

    private void notifyChanged() {
        if (!started) return;
        try { listener.onHudDataChanged(); }
        catch (RuntimeException ignored) {}
    }

    private void runOnMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @NonNull
    private String appVersion() {
        try {
            String version = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return version == null || version.trim().isEmpty() ? "—" : version.trim();
        } catch (android.content.pm.PackageManager.NameNotFoundException
                 | RuntimeException ignored) {
            return "—";
        }
    }

    @NonNull
    private static ExecutorService newNavigationWorker() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hud-navigation-reader");
            thread.setDaemon(true);
            return thread;
        });
    }
}
