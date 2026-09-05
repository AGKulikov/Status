/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.car.CarTelemetryDescriptor;

/**
 * Human-editable content and appearance of the HOME vehicle-information panel.
 *
 * <p>The built-in list covers both the original Status Widget sensors and the values discovered
 * while studying mHUD. It is intentionally not a closed enum: a future car connector can append
 * descriptors to its telemetry catalog and the visual editor will expose them without an app
 * update.</p>
 */
public final class VehicleInfoPanelConfig {
    @NonNull public String backgroundColor = "#111822";
    public int backgroundAlpha = 220;
    public int cornerRadiusPx = 28;
    public int contentPaddingPx = 14;
    public int gapPx = 8;
    public int columns = 3;
    public boolean showLabels = true;
    public boolean hideUntilFirstSample = true;

    /** Settings for one stable telemetry ID. Fields are public for the live visual editor. */
    public static final class Metric {
        @NonNull public final String id;
        @NonNull public String fallbackLabel;
        @NonNull public String fallbackUnit;
        public boolean enabled;
        public int scalePercent;
        /** Empty means use the connector/catalog label. */
        @NonNull public String labelOverride;
        /** Empty means use the connector/catalog unit. */
        @NonNull public String unitOverride;
        /** Applied after the connector-neutral built-in normalization (fuel/speed/TPMS). */
        public double multiplier;
        public double offset;
        public int decimals;
        @NonNull public String valueColor;
        @NonNull public String labelColor;
        /** Derived refill option: suppress the value until the confirmed gear is P. */
        public boolean refillOnlyInPark;
        /** Prefer the static AdaptAPI tank capacity; otherwise use the editable value below. */
        public boolean refillAutomaticCapacity;
        public double refillManualCapacityLitres;
        /** Allowed speed above the navigation limit before the warning becomes active. */
        public int speedLimitThresholdKmh;
        public boolean speedLimitBlink;
        public boolean speedLimitWhiteBackground;
        public boolean speedLimitOnlyActiveRoute;
        @NonNull public String warningColor;

        private Metric(@NonNull String id, @NonNull String fallbackLabel,
                       @NonNull String fallbackUnit, boolean enabled, int decimals) {
            this.id = id;
            this.fallbackLabel = fallbackLabel;
            this.fallbackUnit = fallbackUnit;
            this.enabled = enabled;
            this.scalePercent = 100;
            this.labelOverride = "";
            this.unitOverride = "";
            this.multiplier = 1d;
            this.offset = 0d;
            this.decimals = decimals;
            this.valueColor = "#FFFFFF";
            this.labelColor = "#AEB9C8";
            this.refillOnlyInPark = false;
            this.refillAutomaticCapacity = true;
            this.refillManualCapacityLitres = 64d;
            this.speedLimitThresholdKmh = 0;
            this.speedLimitBlink = true;
            this.speedLimitWhiteBackground = false;
            this.speedLimitOnlyActiveRoute = true;
            this.warningColor = "#FF3B30";
        }

        @NonNull
        public Metric copy() {
            Metric value = new Metric(id, fallbackLabel, fallbackUnit, enabled, decimals);
            value.scalePercent = scalePercent;
            value.labelOverride = labelOverride;
            value.unitOverride = unitOverride;
            value.multiplier = multiplier;
            value.offset = offset;
            value.valueColor = valueColor;
            value.labelColor = labelColor;
            value.refillOnlyInPark = refillOnlyInPark;
            value.refillAutomaticCapacity = refillAutomaticCapacity;
            value.refillManualCapacityLitres = refillManualCapacityLitres;
            value.speedLimitThresholdKmh = speedLimitThresholdKmh;
            value.speedLimitBlink = speedLimitBlink;
            value.speedLimitWhiteBackground = speedLimitWhiteBackground;
            value.speedLimitOnlyActiveRoute = speedLimitOnlyActiveRoute;
            value.warningColor = warningColor;
            return value;
        }

        private void normalize() {
            fallbackLabel = textOr(fallbackLabel, id);
            fallbackUnit = fallbackUnit == null ? "" : fallbackUnit.trim();
            labelOverride = labelOverride == null ? "" : labelOverride.trim();
            unitOverride = unitOverride == null ? "" : unitOverride.trim();
            scalePercent = clamp(scalePercent, 55, 220);
            decimals = clamp(decimals, 0, 4);
            if (!Double.isFinite(multiplier)) multiplier = 1d;
            if (!Double.isFinite(offset)) offset = 0d;
            if (!isColor(valueColor)) valueColor = "#FFFFFF";
            if (!isColor(labelColor)) labelColor = "#AEB9C8";
            if (!Double.isFinite(refillManualCapacityLitres)
                    || refillManualCapacityLitres < 1d
                    || refillManualCapacityLitres > 250d) {
                refillManualCapacityLitres = 64d;
            }
            speedLimitThresholdKmh = clamp(speedLimitThresholdKmh, 0, 20);
            if (!isColor(warningColor)) warningColor = "#FF3B30";
        }
    }

    /** A built-in metric plus its useful default unit/precision. All can be disabled by the user. */
    private static final class BuiltIn {
        final String id;
        final String label;
        final String unit;
        final int decimals;

        BuiltIn(String id, String label, String unit, int decimals) {
            this.id = id;
            this.label = label;
            this.unit = unit;
            this.decimals = decimals;
        }
    }

    private static final BuiltIn[] BUILT_INS = {
            new BuiltIn("ISensor.fuel_level", "Остаток топлива", "л", 1),
            new BuiltIn("ISensor.range_fuel", "Запас хода на топливе", "км", 0),
            new BuiltIn("ISensor.range_total", "Общий запас хода", "км", 0),
            new BuiltIn("ISensor.odometer", "Пробег", "км", 0),
            new BuiltIn("ISensor.speed", "Скорость", "км/ч", 0),
            new BuiltIn("ISensor.rpm", "Обороты двигателя", "об/мин", 0),
            new BuiltIn("ISensor.coolant_temp", "Температура ОЖ", "°C", 1),
            new BuiltIn("ISensor.coolant_level", "Уровень ОЖ", "raw", 0),
            new BuiltIn("ISensor.engine_oil_level", "Уровень масла", "raw", 0),
            new BuiltIn("ISensor.indoor_temp", "Температура в салоне", "°C", 1),
            new BuiltIn("ISensor.ambient_temp", "Температура снаружи", "°C", 1),
            new BuiltIn("ISensor.vehicle_weight", "Масса автомобиля", "raw", 0),
            new BuiltIn("ISensor.ev_battery_level", "Тяговая батарея", "%", 0),
            new BuiltIn("ICarInfo.fuel_capacity", "Объём топливного бака", "raw", 1),

            // Additional live values used by mHUD on the same ECARX/AdaptAPI platform.
            new BuiltIn("ISensor.avg_fuel_consumption", "Средний расход", "л/100 км", 1),
            new BuiltIn("ISensor.instant_fuel_consumption", "Мгновенный расход", "л/100 км", 1),
            new BuiltIn("ISensor.avg_fuel_consumption_ignition",
                    "Средний расход за поездку", "л/100 км", 1),
            new BuiltIn("ISensor.gear", "Передача", "", 0),
            new BuiltIn("ECarx.gear_actual", "Фактическая передача", "", 0),
            new BuiltIn("ECarx.gear_manual_mode", "Ручной режим коробки", "", 0),
            new BuiltIn("ISensor.ignition_state", "Зажигание", "", 0),
            new BuiltIn("IBcm.high_beam", "Дальний свет", "", 0),
            new BuiltIn("IBcm.turn_signal_left", "Левый указатель поворота", "", 0),
            new BuiltIn("IBcm.turn_signal_right", "Правый указатель поворота", "", 0),
            new BuiltIn("External.auto_hold", "Auto Hold", "", 0),
            new BuiltIn("Derived.turn_signals", "Поворотники / аварийка", "", 0),
            new BuiltIn("Derived.refill_fuel", "Долить топлива", "л", 1),
            new BuiltIn("Derived.speed_limit_warning", "Превышение скорости", "км/ч", 0),
            new BuiltIn("TPMS.pressure.front_left", "Давление: переднее левое", "бар", 1),
            new BuiltIn("TPMS.pressure.front_right", "Давление: переднее правое", "бар", 1),
            new BuiltIn("TPMS.pressure.rear_left", "Давление: заднее левое", "бар", 1),
            new BuiltIn("TPMS.pressure.rear_right", "Давление: заднее правое", "бар", 1),
            new BuiltIn("TPMS.temperature.front_left", "Температура: переднее левое", "°C", 1),
            new BuiltIn("TPMS.temperature.front_right", "Температура: переднее правое", "°C", 1),
            new BuiltIn("TPMS.temperature.rear_left", "Температура: заднее левое", "°C", 1),
            new BuiltIn("TPMS.temperature.rear_right", "Температура: заднее правое", "°C", 1)
    };

    private final LinkedHashMap<String, Metric> metrics = new LinkedHashMap<>();
    private final ArrayList<String> order = new ArrayList<>();

    public VehicleInfoPanelConfig() {
        for (BuiltIn builtIn : BUILT_INS) {
            Metric metric = new Metric(builtIn.id, builtIn.label, builtIn.unit,
                    isDefaultEnabled(builtIn.id), builtIn.decimals);
            metrics.put(metric.id, metric);
            order.add(metric.id);
        }
    }

    @Nullable
    public Metric metric(@NonNull String id) {
        return metrics.get(id);
    }

    /** Ordered objects are live members of this config, making slider changes immediately cheap. */
    @NonNull
    public List<Metric> orderedMetrics() {
        ArrayList<Metric> result = new ArrayList<>();
        for (String id : order) {
            Metric metric = metrics.get(id);
            if (metric != null) result.add(metric);
        }
        return Collections.unmodifiableList(result);
    }

    /** Short editor-friendly alias; returned entries remain live and ordered. */
    @NonNull
    public List<Metric> items() {
        return orderedMetrics();
    }

    public void setMetricEnabled(@NonNull String id, boolean enabled) {
        Metric metric = metrics.get(id);
        if (metric != null) metric.enabled = enabled;
    }

    @NonNull
    public Map<String, Metric> metricsById() {
        return Collections.unmodifiableMap(metrics);
    }

    /** Adds values introduced by a connector update while retaining every user override. */
    public boolean mergeCatalog(@NonNull List<CarTelemetryDescriptor> catalog) {
        boolean changed = false;
        for (CarTelemetryDescriptor descriptor : catalog) {
            Metric existing = metrics.get(descriptor.id);
            if (existing == null) {
                Metric value = new Metric(descriptor.id, descriptor.label, descriptor.unit,
                        false, suggestedDecimals(descriptor.unit));
                metrics.put(value.id, value);
                order.add(value.id);
                changed = true;
            } else {
                // Preserve deliberately human-friendly built-in names (several SDK descriptors
                // contain a diagnostic "— raw" suffix). A previously unknown ID initially uses
                // its ID as a placeholder and may be upgraded when a real catalog arrives.
                if (existing.fallbackLabel.equals(existing.id)
                        && !descriptor.label.equals(existing.id)) {
                    existing.fallbackLabel = descriptor.label;
                    changed = true;
                }
            }
        }
        normalize();
        return changed;
    }

    /** Restores an imported/dynamically discovered metric absent from this app's built-ins. */
    @NonNull
    Metric ensureMetric(@NonNull String id, @Nullable String label, @Nullable String unit) {
        Metric value = metrics.get(id);
        if (value != null) return value;
        value = new Metric(id, textOr(label, id), unit == null ? "" : unit,
                false, suggestedDecimals(unit));
        metrics.put(id, value);
        order.add(id);
        return value;
    }

    public boolean moveMetric(@NonNull String id, int direction) {
        int from = order.indexOf(id);
        if (from < 0 || direction == 0) return false;
        int to = clamp(from + direction, 0, order.size() - 1);
        if (from == to) return false;
        order.remove(from);
        order.add(to, id);
        return true;
    }

    public void setOrder(@NonNull List<String> ids) {
        order.clear();
        order.addAll(ids);
        normalizeOrder();
    }

    public boolean hasEnabledMetrics() {
        for (Metric metric : metrics.values()) if (metric.enabled) return true;
        return false;
    }

    @NonNull
    public VehicleInfoPanelConfig copy() {
        VehicleInfoPanelConfig value = new VehicleInfoPanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.contentPaddingPx = contentPaddingPx;
        value.gapPx = gapPx;
        value.columns = columns;
        value.showLabels = showLabels;
        value.hideUntilFirstSample = hideUntilFirstSample;
        value.metrics.clear();
        for (Map.Entry<String, Metric> entry : metrics.entrySet()) {
            value.metrics.put(entry.getKey(), entry.getValue().copy());
        }
        value.order.clear();
        value.order.addAll(order);
        value.normalize();
        return value;
    }

    public void normalize() {
        if (!isColor(backgroundColor)) backgroundColor = "#111822";
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 120);
        contentPaddingPx = clamp(contentPaddingPx, 0, 80);
        gapPx = clamp(gapPx, 0, 48);
        columns = clamp(columns, 1, 6);
        for (Metric metric : metrics.values()) metric.normalize();
        normalizeOrder();
    }

    private void normalizeOrder() {
        Set<String> seen = new HashSet<>();
        ArrayList<String> valid = new ArrayList<>();
        for (String id : order) if (metrics.containsKey(id) && seen.add(id)) valid.add(id);
        for (String id : metrics.keySet()) if (seen.add(id)) valid.add(id);
        order.clear();
        order.addAll(valid);
    }

    private static int suggestedDecimals(@Nullable String unit) {
        String normalized = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        return normalized.contains("°") || normalized.contains("bar")
                || normalized.contains("бар") ? 1 : 0;
    }

    private static boolean isDefaultEnabled(@NonNull String id) {
        return id.equals("ISensor.fuel_level")
                || id.equals("ISensor.range_fuel")
                || id.equals("ISensor.speed")
                || id.equals("ISensor.ambient_temp")
                || id.equals("ISensor.indoor_temp")
                || id.equals("ISensor.gear");
    }

    private static boolean isColor(@Nullable String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    @NonNull
    private static String textOr(@Nullable String value, @NonNull String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
