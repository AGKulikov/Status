/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarTelemetryDescriptor;
import dezz.status.widget.car.EcarxSignalDecoder;
import dezz.status.widget.launcher.LauncherGlobalElementTag;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.NavigationDataRepository;

/**
 * Configurable HOME panel showing live connector-neutral vehicle telemetry.
 *
 * <p>The ECARX integration owns vendor threads and guarantees main-thread callbacks. This view
 * only keeps the latest immutable samples, explicitly starts/stops subscriptions with HOME, and
 * asks the connector to seed every subscription with current values.</p>
 */
public final class VehicleInfoPanelView extends FrameLayout {
    public interface ContentVisibilityListener {
        void onContentVisibilityChanged(boolean visible);
    }

    private static final long BLINK_INTERVAL_MS = 1_000L;
    private static final long ROUTE_STATUS_CACHE_MS = 1_000L;
    private static final long ROUTE_STATUS_IDLE_REFRESH_MS = 5_000L;
    private static final long STATE_TICK_SLOP_MS = 25L;
    private static final long UNKNOWN_STREAM_STALE_MS = 15_000L;
    private static final String FUEL_ID = "ISensor.fuel_level";
    private static final String FUEL_CAPACITY_ID = "ICarInfo.fuel_capacity";
    private static final String GEAR_ID = "ISensor.gear";
    private static final String SPEED_ID = "ISensor.speed";
    private static final String TURN_LEFT_ID = "IBcm.turn_signal_left";
    private static final String TURN_RIGHT_ID = "IBcm.turn_signal_right";

    private final Supplier<CarIntegration> integrationSupplier;
    @Nullable private CarIntegration integration;
    private final VehicleInfoPanelConfigStore configStore;
    private final Map<String, CarTelemetryDescriptor> catalog = new LinkedHashMap<>();
    private final Map<String, CarIntegration.TelemetryValue> latest = new LinkedHashMap<>();
    private final Map<String, MetricViews> metricViews = new LinkedHashMap<>();
    private final Set<String> pendingMetricRefresh = new LinkedHashSet<>();
    private final DecimalFormat[] numberFormats = new DecimalFormat[5];
    @NonNull private Set<String> currentSubscriptionIds = Collections.emptySet();
    @Nullable private Locale numberFormatLocale;
    @Nullable private NavigationDataRepository.RouteStatus cachedRouteStatus;
    private VehicleInfoPanelConfig config;
    private boolean started;
    private boolean catalogReady;
    private boolean previewMode;
    private boolean firstSessionSample;
    private boolean navigationReceiverRegistered;
    private boolean valueRefreshPosted;
    private boolean speedLimitBlinking;
    private long routeStatusReadElapsed;
    private int catalogGeneration;
    @Nullable private ContentVisibilityListener contentVisibilityListener;
    @Nullable private Boolean lastReportedContentVisibility;

    private final BroadcastReceiver navigationReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (NavigationDataRepository.ACTION_UPDATED.equals(intent.getAction())) {
                routeStatus(true);
                speedLimitBlinking = false;
                updateMetric(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
                updatePanelVisibility();
                scheduleStateRefresh();
            }
        }
    };

    private final CarIntegration.TelemetryListener telemetryListener = sample -> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> acceptSample(sample));
        } else {
            acceptSample(sample);
        }
    };

    /** Coalesces a burst of independent vendor metrics into one HOME traversal. */
    private final Runnable valueRefresh = () -> {
        valueRefreshPosted = false;
        if (!started) {
            pendingMetricRefresh.clear();
            return;
        }
        boolean refreshesSpeedWarning = pendingMetricRefresh.contains(
                VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
        if (refreshesSpeedWarning) speedLimitBlinking = false;
        for (String id : pendingMetricRefresh) updateMetric(id);
        pendingMetricRefresh.clear();
        updatePanelVisibility();
        scheduleStateRefresh();
    };

    /** Wakes only at the next stale/blink boundary; there is no permanent one-second loop. */
    private final Runnable stateRefresh = () -> {
        if (!started) return;
        if (valueRefreshPosted) return; // That frame will also schedule the next exact deadline.
        updateAllValues();
        scheduleStateRefresh();
    };

    public VehicleInfoPanelView(@NonNull Context context, @NonNull CarIntegration integration,
                                @NonNull VehicleInfoPanelConfigStore configStore) {
        this(context, () -> integration, configStore);
        this.integration = integration;
    }

    /** Keeps vendor binding outside constructor-time HOME inflation. */
    public VehicleInfoPanelView(@NonNull Context context,
                                @NonNull Supplier<CarIntegration> integrationSupplier,
                                @NonNull VehicleInfoPanelConfigStore configStore) {
        super(context);
        this.integrationSupplier = integrationSupplier;
        this.configStore = configStore;
        config = configStore.load();
        setClipChildren(false);
        setClipToPadding(false);
        rebuild();
    }

    /** Begin catalog discovery and live updates. Safe to call repeatedly from onStart/onResume. */
    public void start() {
        if (started) return;
        requireIntegration();
        started = true;
        // Never reuse values from the previous ignition/screen session. Every enabled metric is
        // seeded again by the connector, so HOME cannot briefly present an old gear, fuel level
        // or temperature merely because another sensor happened to reconnect first.
        latest.clear();
        firstSessionSample = false;
        cachedRouteStatus = null;
        routeStatusReadElapsed = 0L;
        speedLimitBlinking = false;
        // The listener may have been attached before LauncherActivity added its outer frame.
        // Re-emit now that start() guarantees the frame is present.
        lastReportedContentVisibility = null;
        updatePanelVisibility();
        registerNavigationReceiver();
        subscribeEnabledMetrics();
        requestCatalog();
        scheduleStateRefresh();
    }

    /** Releases only this panel's listener; other bricks and exporters remain subscribed. */
    public void stop() {
        if (!started) return;
        started = false;
        catalogGeneration++;
        if (integration != null) integration.unsubscribeTelemetry(telemetryListener);
        unregisterNavigationReceiver();
        removeCallbacks(valueRefresh);
        removeCallbacks(stateRefresh);
        valueRefreshPosted = false;
        pendingMetricRefresh.clear();
        currentSubscriptionIds = Collections.emptySet();
        cachedRouteStatus = null;
    }

    public void reloadConfig() {
        setConfig(configStore.load());
    }

    /** Applies editor sliders immediately; persistence remains the editor/store's responsibility. */
    public void setConfig(@NonNull VehicleInfoPanelConfig value) {
        Set<String> before = subscriptionMetricIds();
        config = value.copy();
        config.normalize();
        // LauncherActivity applies its coarse preference visibility before reloadConfig().
        // Re-report the content gate so a waiting-for-data panel cannot leave an empty frame.
        lastReportedContentVisibility = null;
        rebuild();
        if (started && !before.equals(subscriptionMetricIds())) subscribeEnabledMetrics();
    }

    @NonNull
    public VehicleInfoPanelConfig currentConfig() {
        return config.copy();
    }

    /** Shows realistic local samples in settings without connecting to an actual vehicle. */
    public void setPreviewDemoMode(boolean enabled) {
        if (previewMode == enabled) return;
        previewMode = enabled;
        lastReportedContentVisibility = null;
        rebuild();
    }

    public void setPreviewMode(boolean enabled) {
        setPreviewDemoMode(enabled);
    }

    /** Compatibility name matching the climate/media live editor. */
    public void setEditorPreviewMode(boolean enabled) {
        setPreviewDemoMode(enabled);
    }

    /** Lets LauncherActivity hide the outer draggable frame, not just this inner content view. */
    public void setContentVisibilityListener(@Nullable ContentVisibilityListener listener) {
        contentVisibilityListener = listener;
        lastReportedContentVisibility = null;
        updatePanelVisibility();
    }

    public boolean hasDisplayableSample() {
        return previewMode || !config.hideUntilFirstSample || firstSessionSample;
    }

    /** Allows a visual editor to refresh the connector list on demand. */
    public void refreshCatalog() {
        requestCatalog();
    }

    private void requestCatalog() {
        CarIntegration current = requireIntegration();
        final int generation = ++catalogGeneration;
        current.requestTelemetryCatalog(values -> {
            if (generation != catalogGeneration) return;
            ArrayList<CarTelemetryDescriptor> complete = new ArrayList<>(values);
            complete.addAll(derivedCatalog());
            catalogReady = true;
            catalog.clear();
            for (CarTelemetryDescriptor descriptor : complete) {
                catalog.put(descriptor.id, descriptor);
            }
            boolean added = config.mergeCatalog(complete);
            if (added) configStore.save(config);
            rebuild();
            if (started) subscribeEnabledMetrics();
        });
    }

    private void subscribeEnabledMetrics() {
        CarIntegration current = requireIntegration();
        current.unsubscribeTelemetry(telemetryListener);
        Set<String> ids = subscriptionMetricIds();
        currentSubscriptionIds = Collections.unmodifiableSet(new LinkedHashSet<>(ids));
        if (started && !ids.isEmpty()) current.subscribeTelemetry(ids, telemetryListener);
        scheduleStateRefresh();
    }

    @NonNull
    private CarIntegration requireIntegration() {
        CarIntegration current = integration;
        if (current != null) return current;
        current = integrationSupplier.get();
        if (current == null) {
            throw new IllegalStateException("Car integration supplier returned null");
        }
        integration = current;
        return current;
    }

    @NonNull
    private Set<String> subscriptionMetricIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (VehicleInfoPanelConfig.Metric metric : config.orderedMetrics()) {
            if (!metric.enabled) continue;
            if (VehicleDerivedMetrics.REFILL_FUEL_ID.equals(metric.id)) {
                result.add(FUEL_ID);
                if (metric.refillAutomaticCapacity) result.add(FUEL_CAPACITY_ID);
                if (metric.refillOnlyInPark) result.add(GEAR_ID);
            } else if (VehicleDerivedMetrics.TURN_SIGNALS_ID.equals(metric.id)) {
                result.add(TURN_LEFT_ID);
                result.add(TURN_RIGHT_ID);
            } else if (VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID.equals(metric.id)) {
                result.add(SPEED_ID);
            } else {
                result.add(metric.id);
            }
        }
        return result;
    }

    @NonNull
    private Set<String> enabledMetricIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (VehicleInfoPanelConfig.Metric metric : config.orderedMetrics()) {
            if (metric.enabled) result.add(metric.id);
        }
        return result;
    }

    private void acceptSample(@NonNull CarIntegration.TelemetryValue sample) {
        if (!started) return;
        latest.put(sample.id, sample);
        firstSessionSample = true;
        if (!catalog.containsKey(sample.id)) {
            CarTelemetryDescriptor descriptor = new CarTelemetryDescriptor(sample.id, sample.label,
                    sample.unit, true, UNKNOWN_STREAM_STALE_MS);
            catalog.put(sample.id, descriptor);
            config.mergeCatalog(Collections.singletonList(descriptor));
            configStore.save(config);
            rebuild();
        } else {
            requestMetricRefresh(sample.id);
        }
    }

    private void requestMetricRefresh(@NonNull String sampleId) {
        pendingMetricRefresh.add(sampleId);
        if (FUEL_ID.equals(sampleId) || FUEL_CAPACITY_ID.equals(sampleId)
                || GEAR_ID.equals(sampleId)) {
            pendingMetricRefresh.add(VehicleDerivedMetrics.REFILL_FUEL_ID);
        }
        if (TURN_LEFT_ID.equals(sampleId) || TURN_RIGHT_ID.equals(sampleId)) {
            pendingMetricRefresh.add(VehicleDerivedMetrics.TURN_SIGNALS_ID);
        }
        if (SPEED_ID.equals(sampleId)) {
            pendingMetricRefresh.add(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
        }
        if (valueRefreshPosted) return;
        valueRefreshPosted = true;
        postOnAnimation(valueRefresh);
    }

    @NonNull
    private static List<CarTelemetryDescriptor> derivedCatalog() {
        ArrayList<CarTelemetryDescriptor> result = new ArrayList<>();
        result.add(new CarTelemetryDescriptor(VehicleDerivedMetrics.REFILL_FUEL_ID,
                "Долить топлива", "л", true, 120_000L));
        result.add(new CarTelemetryDescriptor(VehicleDerivedMetrics.TURN_SIGNALS_ID,
                "Поворотники / аварийка", "", true, 1_500L));
        result.add(new CarTelemetryDescriptor(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID,
                "Превышение скорости", "км/ч", true, 30_000L));
        return result;
    }

    private void registerNavigationReceiver() {
        if (navigationReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(NavigationDataRepository.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            getContext().registerReceiver(navigationReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(navigationReceiver, filter);
        }
        navigationReceiverRegistered = true;
    }

    private void unregisterNavigationReceiver() {
        if (!navigationReceiverRegistered) return;
        try { getContext().unregisterReceiver(navigationReceiver); }
        catch (IllegalArgumentException ignored) {}
        navigationReceiverRegistered = false;
    }

    private void rebuild() {
        removeAllViews();
        metricViews.clear();
        applySurface();

        List<VehicleInfoPanelConfig.Metric> visible = visibleMetrics();
        if (visible.isEmpty()) {
            TextView empty = text("Данные автомобиля недоступны", 15f, Color.WHITE, true);
            empty.setGravity(Gravity.CENTER);
            empty.setAlpha(.72f);
            addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            updatePanelVisibility();
            scheduleStateRefresh();
            return;
        }

        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(config.columns);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);
        grid.setPadding(config.contentPaddingPx, config.contentPaddingPx,
                config.contentPaddingPx, config.contentPaddingPx);

        for (int index = 0; index < visible.size(); index++) {
            VehicleInfoPanelConfig.Metric metric = visible.get(index);
            View tile = buildMetricTile(metric);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                    GridLayout.spec(index / config.columns, 1),
                    GridLayout.spec(index % config.columns, 1, 1f));
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            int halfGap = config.gapPx / 2;
            lp.setMargins(halfGap, halfGap, config.gapPx - halfGap,
                    config.gapPx - halfGap);
            grid.addView(tile, lp);
        }
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateAllValues();
        updatePanelVisibility();
        scheduleStateRefresh();
    }

    @NonNull
    private List<VehicleInfoPanelConfig.Metric> visibleMetrics() {
        ArrayList<VehicleInfoPanelConfig.Metric> result = new ArrayList<>();
        for (VehicleInfoPanelConfig.Metric metric : config.orderedMetrics()) {
            if (!metric.enabled) continue;
            // Before discovery, placeholders make the panel/editor deterministic. Afterwards an
            // empty/partial connector catalog does not waste HOME space on unavailable sensors.
            if (previewMode || !catalogReady || catalog.containsKey(metric.id)) result.add(metric);
        }
        return result;
    }

    @NonNull
    private View buildMetricTile(@NonNull VehicleInfoPanelConfig.Metric metric) {
        LinearLayout tile = new LinearLayout(getContext());
        LauncherGlobalElementTag.attach(tile, LauncherLayoutStore.VEHICLE_INFO,
                metric.id, resolveLabel(metric));
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        int vertical = scaledDp(11, metric.scalePercent);
        tile.setPadding(scaledDp(10, metric.scalePercent), vertical,
                scaledDp(10, metric.scalePercent), vertical);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(66, 255, 255, 255));
        background.setCornerRadius(Math.max(4f, config.cornerRadiusPx * .48f));
        tile.setBackground(background);
        tile.setMinimumHeight(scaledDp(config.showLabels ? 72 : 52, metric.scalePercent));

        TextView label = text(resolveLabel(metric), scaledSp(12f, metric.scalePercent),
                color(metric.labelColor, Color.LTGRAY), false);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setVisibility(config.showLabels ? View.VISIBLE : View.GONE);
        TextView value = text("…", scaledSp(21f, metric.scalePercent),
                color(metric.valueColor, Color.WHITE), true);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setGravity(Gravity.CENTER_VERTICAL);
        if (config.showLabels) tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tile.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        metricViews.put(metric.id, new MetricViews(tile, label, value, background));
        return tile;
    }

    private void updateAllValues() {
        speedLimitBlinking = false;
        for (String id : metricViews.keySet()) updateMetric(id);
        updatePanelVisibility();
    }

    private void updateMetric(@NonNull String id) {
        MetricViews views = metricViews.get(id);
        VehicleInfoPanelConfig.Metric metric = config.metric(id);
        if (views == null || metric == null) return;
        setTextIfChanged(views.label, resolveLabel(metric));
        resetMetricAppearance(views, metric);

        if (previewMode) {
            if (VehicleDerivedMetrics.TURN_SIGNALS_ID.equals(id)) {
                renderValue(views, metric,
                        VehicleDerivedMetrics.turnText(VehicleDerivedMetrics.TURN_HAZARD), false);
            } else if (VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID.equals(id)) {
                renderSpeedLimitWarning(views, metric, 19.4d, "60", true);
            } else {
                renderValue(views, metric,
                        formatValue(metric, demoValue(metric.id), resolvedUnit(metric)), false);
            }
            return;
        }

        if (VehicleDerivedMetrics.REFILL_FUEL_ID.equals(id)) {
            updateRefillMetric(views, metric);
            return;
        }
        if (VehicleDerivedMetrics.TURN_SIGNALS_ID.equals(id)) {
            updateTurnMetric(views, metric);
            return;
        }
        if (VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID.equals(id)) {
            updateSpeedLimitWarning(views, metric);
            return;
        }

        CarIntegration.TelemetryValue sample = latest.get(id);
        if (sample == null) {
            renderMissing(views, metric);
            return;
        }
        boolean stale = isStale(id, sample);
        String formatted = formatValue(metric, sample.value, resolvedUnit(metric));
        renderValue(views, metric, formatted, stale);
    }

    private void updateRefillMetric(@NonNull MetricViews views,
                                    @NonNull VehicleInfoPanelConfig.Metric metric) {
        if (metric.refillOnlyInPark) {
            CarIntegration.TelemetryValue gear = latest.get(GEAR_ID);
            if (gear == null || !VehicleDerivedMetrics.isPark(gear.value)) {
                setVisibilityIfChanged(views.tile, View.GONE);
                setDescriptionIfChanged(views.value, resolveLabel(metric)
                        + ": доступно только на передаче P");
                return;
            }
        }
        CarIntegration.TelemetryValue fuel = latest.get(FUEL_ID);
        CarIntegration.TelemetryValue capacity = metric.refillAutomaticCapacity
                ? latest.get(FUEL_CAPACITY_ID) : null;
        if (fuel == null) {
            renderMissing(views, metric);
            return;
        }
        double capacityLitres = metric.refillAutomaticCapacity
                ? VehicleDerivedMetrics.capacityLitresOrDefault(
                        capacity == null ? null : capacity.value,
                        VehicleDerivedMetrics.DEFAULT_FUEL_CAPACITY_LITRES)
                : metric.refillManualCapacityLitres;
        double refill = VehicleDerivedMetrics.refillLitres(fuel.value, capacityLitres);
        boolean stale = isStale(FUEL_ID, fuel)
                || (capacity != null && isStale(FUEL_CAPACITY_ID, capacity));
        renderValue(views, metric,
                formatValue(metric, refill, resolvedUnit(metric)), stale);
    }

    private void updateTurnMetric(@NonNull MetricViews views,
                                  @NonNull VehicleInfoPanelConfig.Metric metric) {
        CarIntegration.TelemetryValue left = latest.get(TURN_LEFT_ID);
        CarIntegration.TelemetryValue right = latest.get(TURN_RIGHT_ID);
        if (left == null || right == null) {
            renderMissing(views, metric);
            return;
        }
        int state = VehicleDerivedMetrics.turnState(left.value, right.value);
        renderValue(views, metric, VehicleDerivedMetrics.turnText(state),
                isStale(TURN_LEFT_ID, left) || isStale(TURN_RIGHT_ID, right));
    }

    private void updateSpeedLimitWarning(@NonNull MetricViews views,
                                         @NonNull VehicleInfoPanelConfig.Metric metric) {
        CarIntegration.TelemetryValue speed = latest.get(SPEED_ID);
        if (speed == null) {
            renderMissing(views, metric);
            return;
        }
        NavigationDataRepository.RouteStatus navigation = routeStatus(false);
        renderSpeedLimitWarning(views, metric, speed.value, navigation.speedLimit,
                navigation.routeActive);
        if (isStale(SPEED_ID, speed)) setAlphaIfChanged(views.value, .48f);
    }

    private void renderSpeedLimitWarning(@NonNull MetricViews views,
                                         @NonNull VehicleInfoPanelConfig.Metric metric,
                                         double rawSpeed, @NonNull String limitText,
                                         boolean activeRoute) {
        speedLimitBlinking = false;
        if (metric.speedLimitOnlyActiveRoute && !activeRoute) {
            renderValue(views, metric, "Нет маршрута", false);
            return;
        }
        double limit = VehicleDerivedMetrics.parseSpeedLimit(limitText);
        if (!Double.isFinite(limit)) {
            renderValue(views, metric, "Нет лимита", false);
            return;
        }
        double current = VehicleDerivedMetrics.speedKmh(rawSpeed);
        double excess = VehicleDerivedMetrics.speedExcess(rawSpeed, limit,
                metric.speedLimitThresholdKmh);
        boolean exceeded = Double.isFinite(excess) && excess > 0d;
        String value = formatNumber(current, 0) + " / " + formatNumber(limit, 0) + " км/ч";
        renderValue(views, metric, value, false);
        if (!exceeded) return;
        setTextColorIfChanged(views.value, color(metric.warningColor, Color.RED));
        if (metric.speedLimitWhiteBackground) {
            setBackgroundColorIfChanged(views, Color.argb(238, 255, 255, 255));
            setTextColorIfChanged(views.label, Color.rgb(35, 35, 35));
        }
        speedLimitBlinking = metric.speedLimitBlink;
        if (speedLimitBlinking
                && ((System.currentTimeMillis() / BLINK_INTERVAL_MS) & 1L) != 0L) {
            setAlphaIfChanged(views.value, .22f);
        }
        setDescriptionIfChanged(views.value,
                resolveLabel(metric) + ": превышение, " + value);
    }

    private void resetMetricAppearance(@NonNull MetricViews views,
                                       @NonNull VehicleInfoPanelConfig.Metric metric) {
        setVisibilityIfChanged(views.tile, View.VISIBLE);
        setTextColorIfChanged(views.value, color(metric.valueColor, Color.WHITE));
        setTextColorIfChanged(views.label, color(metric.labelColor, Color.LTGRAY));
        setAlphaIfChanged(views.value, 1f);
        setBackgroundColorIfChanged(views, Color.argb(66, 255, 255, 255));
    }

    private void renderMissing(@NonNull MetricViews views,
                               @NonNull VehicleInfoPanelConfig.Metric metric) {
        setTextIfChanged(views.value, "…");
        setAlphaIfChanged(views.value, .55f);
        setDescriptionIfChanged(views.value, resolveLabel(metric) + ": нет данных");
    }

    private void renderValue(@NonNull MetricViews views,
                             @NonNull VehicleInfoPanelConfig.Metric metric,
                             @NonNull String formatted, boolean stale) {
        setTextIfChanged(views.value, stale ? formatted + "  · устарело" : formatted);
        setAlphaIfChanged(views.value, stale ? .48f : 1f);
        setDescriptionIfChanged(views.value, resolveLabel(metric) + ": " + formatted
                + (stale ? ", данные устарели" : ""));
    }

    private boolean isStale(@NonNull String id,
                            @NonNull CarIntegration.TelemetryValue sample) {
        CarTelemetryDescriptor descriptor = catalog.get(id);
        long staleAfter = descriptor == null
                ? UNKNOWN_STREAM_STALE_MS : descriptor.staleAfterMillis;
        if (staleAfter <= 0L) return false;
        long age = System.currentTimeMillis() - sample.observedAtMillis;
        return age > staleAfter;
    }

    @NonNull
    private NavigationDataRepository.RouteStatus routeStatus(boolean force) {
        long now = SystemClock.elapsedRealtime();
        NavigationDataRepository.RouteStatus current = cachedRouteStatus;
        if (force || current == null
                || now - routeStatusReadElapsed >= ROUTE_STATUS_CACHE_MS) {
            current = NavigationDataRepository.readRouteStatus(getContext());
            cachedRouteStatus = current;
            routeStatusReadElapsed = now;
        }
        return current;
    }

    private void scheduleStateRefresh() {
        removeCallbacks(stateRefresh);
        if (!started) return;
        long wallNow = System.currentTimeMillis();
        long delay = Long.MAX_VALUE;
        for (String id : currentSubscriptionIds) {
            CarIntegration.TelemetryValue sample = latest.get(id);
            if (sample == null) continue;
            CarTelemetryDescriptor descriptor = catalog.get(id);
            long staleAfter = descriptor == null
                    ? UNKNOWN_STREAM_STALE_MS : descriptor.staleAfterMillis;
            if (staleAfter <= 0L) continue;
            long remaining = sample.observedAtMillis + staleAfter - wallNow;
            if (remaining > 0L) delay = Math.min(delay, remaining + STATE_TICK_SLOP_MS);
        }
        if (metricViews.containsKey(VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID)
                && latest.containsKey(SPEED_ID)) {
            delay = Math.min(delay, ROUTE_STATUS_IDLE_REFRESH_MS);
        }
        if (speedLimitBlinking) {
            long nextBlink = BLINK_INTERVAL_MS
                    - Math.floorMod(wallNow, BLINK_INTERVAL_MS) + STATE_TICK_SLOP_MS;
            delay = Math.min(delay, nextBlink);
        }
        if (delay != Long.MAX_VALUE) postDelayed(stateRefresh, Math.max(1L, delay));
    }

    @NonNull
    private String resolveLabel(@NonNull VehicleInfoPanelConfig.Metric metric) {
        if (!metric.labelOverride.isEmpty()) return metric.labelOverride;
        return metric.fallbackLabel;
    }

    @NonNull
    private String resolvedUnit(@NonNull VehicleInfoPanelConfig.Metric metric) {
        if (!metric.unitOverride.isEmpty()) return metric.unitOverride;
        if (!metric.fallbackUnit.isEmpty() && !metric.fallbackUnit.equals("raw")) {
            return metric.fallbackUnit;
        }
        CarTelemetryDescriptor descriptor = catalog.get(metric.id);
        if (descriptor != null && !descriptor.unit.equals("raw")) return descriptor.unit;
        return metric.fallbackUnit;
    }

    @NonNull
    private String formatValue(@NonNull VehicleInfoPanelConfig.Metric metric, double raw,
                               @NonNull String unit) {
        if (metric.id.equals("ISensor.gear")
                && metric.multiplier == 1d && metric.offset == 0d) {
            String gear = gearName(Math.round(raw));
            if (gear != null) return gear;
        }
        if (metric.id.equals("ISensor.ignition_state")
                && metric.multiplier == 1d && metric.offset == 0d) {
            String ignition = ignitionName(Math.round(raw));
            if (ignition != null) return ignition;
        }
        if (isBooleanIndicator(metric.id)
                && metric.multiplier == 1d && metric.offset == 0d) {
            return raw >= .5d ? "Вкл" : "Выкл";
        }
        double normalized = normalizeRaw(metric.id, raw);
        double value = normalized * metric.multiplier + metric.offset;
        if (!Double.isFinite(value)) return "—";
        String number = formatNumber(value, metric.decimals);
        return unit.isEmpty() ? number : number + " " + unit;
    }

    @NonNull
    private String formatNumber(double value, int decimals) {
        if (!Double.isFinite(value)) return "—";
        int bounded = Math.max(0, Math.min(numberFormats.length - 1, decimals));
        Locale locale = Locale.getDefault();
        if (!locale.equals(numberFormatLocale)) {
            numberFormatLocale = locale;
            java.util.Arrays.fill(numberFormats, null);
        }
        DecimalFormat format = numberFormats[bounded];
        if (format == null) {
            StringBuilder pattern = new StringBuilder("0");
            if (bounded > 0) {
                pattern.append('.');
                for (int index = 0; index < bounded; index++) pattern.append('0');
            }
            format = new DecimalFormat(pattern.toString(),
                    DecimalFormatSymbols.getInstance(locale));
            format.setGroupingUsed(false);
            numberFormats[bounded] = format;
        }
        return format.format(value);
    }

    private static double normalizeRaw(@NonNull String id, double value) {
        if (id.equals("ISensor.fuel_level")) return value / 1_000d;
        if (id.equals("ISensor.speed")) return value * 3.72d;
        if (id.startsWith("TPMS.pressure.")) {
            return Math.abs(value) >= 40d ? value / 100d : value;
        }
        return value;
    }

    private static boolean isBooleanIndicator(@NonNull String id) {
        return id.equals("IBcm.high_beam") || id.equals("IBcm.turn_signal_left")
                || id.equals("IBcm.turn_signal_right")
                || id.equals("ECarx.gear_manual_mode")
                || id.equals(VehicleDerivedMetrics.AUTO_HOLD_ID);
    }

    @Nullable
    private static String gearName(long raw) {
        return EcarxSignalDecoder.gearDisplayName(raw);
    }

    @Nullable
    private static String ignitionName(long raw) {
        if (raw == 2_097_409L) return "Не определено";
        if (raw == 2_097_410L) return "Блокировка";
        if (raw == 2_097_411L) return "Выкл";
        if (raw == 2_097_412L) return "ACC";
        if (raw == 2_097_413L) return "Вкл";
        if (raw == 2_097_414L) return "Запуск";
        if (raw == 2_097_415L) return "Движение";
        return null;
    }

    private double demoValue(@NonNull String id) {
        if (id.equals("ISensor.fuel_level")) return 43_500d;
        if (id.equals("ISensor.speed")) return 16.13d;
        if (id.equals("ISensor.rpm")) return 1_820d;
        if (id.equals("ISensor.gear")) return 2_097_696d;
        if (VehicleDerivedMetrics.REFILL_FUEL_ID.equals(id)) return 20.5d;
        if (VehicleDerivedMetrics.AUTO_HOLD_ID.equals(id)) return 1d;
        if (id.contains("temperature") || id.contains("_temp")) return 24.6d;
        if (id.startsWith("TPMS.pressure.")) return 238d;
        if (id.contains("range")) return 436d;
        if (id.contains("consumption")) return 9.4d;
        if (id.contains("level")) return 67d;
        return 123.4d;
    }

    private void updatePanelVisibility() {
        boolean allTilesHidden = !previewMode && !metricViews.isEmpty();
        if (allTilesHidden) {
            for (MetricViews views : metricViews.values()) {
                if (views.tile.getVisibility() == View.VISIBLE) {
                    allTilesHidden = false;
                    break;
                }
            }
        }
        boolean hide = (config.hideUntilFirstSample && !previewMode && !firstSessionSample)
                || allTilesHidden;
        setVisibilityIfChanged(this, hide ? View.GONE : View.VISIBLE);
        boolean visible = !hide;
        if (contentVisibilityListener != null
                && (lastReportedContentVisibility == null
                || lastReportedContentVisibility != visible)) {
            lastReportedContentVisibility = visible;
            contentVisibilityListener.onContentVisibilityChanged(visible);
        }
    }

    private void applySurface() {
        int base = color(config.backgroundColor, Color.rgb(17, 24, 34));
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.argb(config.backgroundAlpha, Color.red(base), Color.green(base),
                Color.blue(base)));
        surface.setCornerRadius(config.cornerRadiusPx);
        setBackground(surface);
    }

    @NonNull
    private TextView text(@NonNull String content, float sizeSp, int textColor, boolean bold) {
        TextView value = new TextView(getContext());
        value.setText(content);
        value.setTextSize(sizeSp);
        value.setTextColor(textColor);
        value.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    private int scaledDp(int dp, int percent) {
        return Math.max(1, Math.round(dp * getResources().getDisplayMetrics().density
                * percent / 100f));
    }

    private static float scaledSp(float sp, int percent) {
        return Math.max(8f, sp * percent / 100f);
    }

    private static int color(@Nullable String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static void setTextIfChanged(@NonNull TextView view, @NonNull String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private static void setTextColorIfChanged(@NonNull TextView view, int color) {
        if (view.getCurrentTextColor() != color) view.setTextColor(color);
    }

    private static void setAlphaIfChanged(@NonNull View view, float alpha) {
        if (view.getAlpha() != alpha) view.setAlpha(alpha);
    }

    private static void setVisibilityIfChanged(@NonNull View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private static void setDescriptionIfChanged(@NonNull View view,
                                                @NonNull String description) {
        if (!TextUtils.equals(view.getContentDescription(), description)) {
            view.setContentDescription(description);
        }
    }

    private static void setBackgroundColorIfChanged(@NonNull MetricViews views, int color) {
        if (views.backgroundColor == color) return;
        views.backgroundColor = color;
        views.background.setColor(color);
    }

    private static final class MetricViews {
        final View tile;
        final TextView label;
        final TextView value;
        final GradientDrawable background;
        int backgroundColor = Integer.MIN_VALUE;

        MetricViews(View tile, TextView label, TextView value, GradientDrawable background) {
            this.tile = tile;
            this.label = label;
            this.value = value;
            this.background = background;
        }
    }
}
