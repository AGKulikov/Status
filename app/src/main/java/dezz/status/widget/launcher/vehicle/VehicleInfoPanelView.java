/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarTelemetryDescriptor;

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

    private static final long STALE_TICK_MS = 1_000L;
    private static final long UNKNOWN_STREAM_STALE_MS = 15_000L;

    private final CarIntegration integration;
    private final VehicleInfoPanelConfigStore configStore;
    private final Map<String, CarTelemetryDescriptor> catalog = new LinkedHashMap<>();
    private final Map<String, CarIntegration.TelemetryValue> latest = new LinkedHashMap<>();
    private final Map<String, MetricViews> metricViews = new LinkedHashMap<>();
    private VehicleInfoPanelConfig config;
    private boolean started;
    private boolean catalogReady;
    private boolean previewMode;
    private boolean firstSessionSample;
    private int catalogGeneration;
    @Nullable private ContentVisibilityListener contentVisibilityListener;
    @Nullable private Boolean lastReportedContentVisibility;

    private final CarIntegration.TelemetryListener telemetryListener = sample -> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> acceptSample(sample));
        } else {
            acceptSample(sample);
        }
    };

    private final Runnable staleTick = new Runnable() {
        @Override public void run() {
            if (!started) return;
            updateAllValues();
            postDelayed(this, STALE_TICK_MS);
        }
    };

    public VehicleInfoPanelView(@NonNull Context context, @NonNull CarIntegration integration,
                                @NonNull VehicleInfoPanelConfigStore configStore) {
        super(context);
        this.integration = integration;
        this.configStore = configStore;
        config = configStore.load();
        setClipChildren(false);
        setClipToPadding(false);
        rebuild();
    }

    /** Begin catalog discovery and live updates. Safe to call repeatedly from onStart/onResume. */
    public void start() {
        if (started) return;
        started = true;
        // Never reuse values from the previous ignition/screen session. Every enabled metric is
        // seeded again by the connector, so HOME cannot briefly present an old gear, fuel level
        // or temperature merely because another sensor happened to reconnect first.
        latest.clear();
        firstSessionSample = false;
        // The listener may have been attached before LauncherActivity added its outer frame.
        // Re-emit now that start() guarantees the frame is present.
        lastReportedContentVisibility = null;
        updatePanelVisibility();
        subscribeEnabledMetrics();
        requestCatalog();
        removeCallbacks(staleTick);
        postDelayed(staleTick, STALE_TICK_MS);
    }

    /** Releases only this panel's listener; other bricks and exporters remain subscribed. */
    public void stop() {
        if (!started) return;
        started = false;
        catalogGeneration++;
        integration.unsubscribeTelemetry(telemetryListener);
        removeCallbacks(staleTick);
    }

    public void reloadConfig() {
        setConfig(configStore.load());
    }

    /** Applies editor sliders immediately; persistence remains the editor/store's responsibility. */
    public void setConfig(@NonNull VehicleInfoPanelConfig value) {
        Set<String> before = enabledMetricIds();
        config = value.copy();
        config.normalize();
        // LauncherActivity applies its coarse preference visibility before reloadConfig().
        // Re-report the content gate so a waiting-for-data panel cannot leave an empty frame.
        lastReportedContentVisibility = null;
        rebuild();
        if (started && !before.equals(enabledMetricIds())) subscribeEnabledMetrics();
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
        final int generation = ++catalogGeneration;
        integration.requestTelemetryCatalog(values -> {
            if (generation != catalogGeneration) return;
            catalogReady = true;
            catalog.clear();
            for (CarTelemetryDescriptor descriptor : values) {
                catalog.put(descriptor.id, descriptor);
            }
            boolean added = config.mergeCatalog(values);
            if (added) configStore.save(config);
            rebuild();
            if (started) subscribeEnabledMetrics();
        });
    }

    private void subscribeEnabledMetrics() {
        integration.unsubscribeTelemetry(telemetryListener);
        Set<String> ids = enabledMetricIds();
        if (started && !ids.isEmpty()) integration.subscribeTelemetry(ids, telemetryListener);
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
            updateMetric(sample.id);
            updatePanelVisibility();
        }
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
        metricViews.put(metric.id, new MetricViews(label, value));
        return tile;
    }

    private void updateAllValues() {
        for (String id : new ArrayList<>(metricViews.keySet())) updateMetric(id);
        updatePanelVisibility();
    }

    private void updateMetric(@NonNull String id) {
        MetricViews views = metricViews.get(id);
        VehicleInfoPanelConfig.Metric metric = config.metric(id);
        if (views == null || metric == null) return;
        views.label.setText(resolveLabel(metric));

        if (previewMode) {
            views.value.setText(formatValue(metric, demoValue(metric.id), resolvedUnit(metric)));
            views.value.setAlpha(1f);
            views.value.setContentDescription(resolveLabel(metric) + ": " + views.value.getText());
            return;
        }

        CarIntegration.TelemetryValue sample = latest.get(id);
        if (sample == null) {
            views.value.setText("…");
            views.value.setAlpha(.55f);
            views.value.setContentDescription(resolveLabel(metric) + ": нет данных");
            return;
        }
        boolean stale = isStale(id, sample);
        String formatted = formatValue(metric, sample.value, resolvedUnit(metric));
        views.value.setText(stale ? formatted + "  · устарело" : formatted);
        views.value.setAlpha(stale ? .48f : 1f);
        views.value.setContentDescription(resolveLabel(metric) + ": " + formatted
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
        String number = String.format(Locale.getDefault(), "%." + metric.decimals + "f", value);
        return unit.isEmpty() ? number : number + " " + unit;
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
                || id.equals("IBcm.turn_signal_right");
    }

    @Nullable
    private static String gearName(long raw) {
        if (raw == 2097680L) return "N";
        if (raw == 2097696L) return "D";
        if (raw == 2097712L) return "P";
        if (raw == 2097728L) return "R";
        if (raw >= 2097665L && raw <= 2097674L) return String.valueOf(raw - 2097664L);
        return null;
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
        if (id.contains("temperature") || id.contains("_temp")) return 24.6d;
        if (id.startsWith("TPMS.pressure.")) return 238d;
        if (id.contains("range")) return 436d;
        if (id.contains("consumption")) return 9.4d;
        if (id.contains("level")) return 67d;
        return 123.4d;
    }

    private void updatePanelVisibility() {
        boolean hide = config.hideUntilFirstSample && !previewMode && !firstSessionSample;
        setVisibility(hide ? View.GONE : View.VISIBLE);
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

    private static final class MetricViews {
        final TextView label;
        final TextView value;

        MetricViews(TextView label, TextView value) {
            this.label = label;
            this.value = value;
        }
    }
}
