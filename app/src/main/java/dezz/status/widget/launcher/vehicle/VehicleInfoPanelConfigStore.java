/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.vehicle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.Preferences;

/** Versioned JSON persistence that tolerates interrupted writes and future telemetry IDs. */
public final class VehicleInfoPanelConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Preferences preferences;

    public VehicleInfoPanelConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public VehicleInfoPanelConfig load() {
        return decode(preferences.launcherVehicleInfoConfigJson.get());
    }

    public void save(@NonNull VehicleInfoPanelConfig source) {
        try {
            preferences.launcherVehicleInfoConfigJson.set(encode(source).toString());
        } catch (JSONException ignored) {
            // All written values are primitive and finite after normalize(); retained for safety.
        }
    }

    public void reset() {
        preferences.launcherVehicleInfoConfigJson.set("");
    }

    @NonNull
    static VehicleInfoPanelConfig decode(@Nullable String raw) {
        VehicleInfoPanelConfig value = new VehicleInfoPanelConfig();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("version", 0);
            if (version < 1 || version > SCHEMA_VERSION) return value;
            value.backgroundColor = root.optString("backgroundColor", value.backgroundColor);
            value.backgroundAlpha = root.optInt("backgroundAlpha", value.backgroundAlpha);
            value.cornerRadiusPx = root.optInt("cornerRadiusPx", value.cornerRadiusPx);
            value.contentPaddingPx = root.optInt("contentPaddingPx", value.contentPaddingPx);
            value.gapPx = root.optInt("gapPx", value.gapPx);
            value.columns = root.optInt("columns", value.columns);
            value.showLabels = root.optBoolean("showLabels", value.showLabels);
            value.hideUntilFirstSample = root.optBoolean("hideUntilFirstSample",
                    value.hideUntilFirstSample);

            JSONArray metrics = root.optJSONArray("metrics");
            List<String> order = new ArrayList<>();
            if (metrics != null) {
                for (int index = 0; index < metrics.length(); index++) {
                    JSONObject encoded = metrics.optJSONObject(index);
                    if (encoded == null) continue;
                    String id = encoded.optString("id", "").trim();
                    if (id.isEmpty()) continue;
                    VehicleInfoPanelConfig.Metric metric = value.ensureMetric(id,
                            encoded.optString("fallbackLabel", id),
                            encoded.optString("fallbackUnit", ""));
                    metric.fallbackLabel = encoded.optString("fallbackLabel",
                            metric.fallbackLabel);
                    metric.fallbackUnit = encoded.optString("fallbackUnit",
                            metric.fallbackUnit);
                    metric.enabled = encoded.optBoolean("enabled", metric.enabled);
                    metric.scalePercent = encoded.optInt("scalePercent", metric.scalePercent);
                    metric.labelOverride = encoded.optString("labelOverride",
                            metric.labelOverride);
                    metric.unitOverride = encoded.optString("unitOverride", metric.unitOverride);
                    metric.multiplier = finiteOr(encoded.optDouble("multiplier", 1d), 1d);
                    metric.offset = finiteOr(encoded.optDouble("offset", 0d), 0d);
                    metric.decimals = encoded.optInt("decimals", metric.decimals);
                    metric.valueColor = encoded.optString("valueColor", metric.valueColor);
                    metric.labelColor = encoded.optString("labelColor", metric.labelColor);
                    order.add(id);
                }
            }
            JSONArray explicitOrder = root.optJSONArray("order");
            if (explicitOrder != null) {
                order.clear();
                for (int index = 0; index < explicitOrder.length(); index++) {
                    String id = explicitOrder.optString(index, "").trim();
                    if (!id.isEmpty()) order.add(id);
                }
            }
            if (!order.isEmpty()) value.setOrder(order);
        } catch (JSONException ignored) {
            // Invalid imports fall back to a complete usable catalog instead of breaking HOME.
        }
        value.normalize();
        return value;
    }

    @NonNull
    static JSONObject encode(@NonNull VehicleInfoPanelConfig source) throws JSONException {
        VehicleInfoPanelConfig value = source.copy();
        value.normalize();
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("backgroundColor", value.backgroundColor);
        root.put("backgroundAlpha", value.backgroundAlpha);
        root.put("cornerRadiusPx", value.cornerRadiusPx);
        root.put("contentPaddingPx", value.contentPaddingPx);
        root.put("gapPx", value.gapPx);
        root.put("columns", value.columns);
        root.put("showLabels", value.showLabels);
        root.put("hideUntilFirstSample", value.hideUntilFirstSample);
        JSONArray metrics = new JSONArray();
        JSONArray order = new JSONArray();
        for (VehicleInfoPanelConfig.Metric metric : value.orderedMetrics()) {
            JSONObject encoded = new JSONObject();
            encoded.put("id", metric.id);
            encoded.put("fallbackLabel", metric.fallbackLabel);
            encoded.put("fallbackUnit", metric.fallbackUnit);
            encoded.put("enabled", metric.enabled);
            encoded.put("scalePercent", metric.scalePercent);
            encoded.put("labelOverride", metric.labelOverride);
            encoded.put("unitOverride", metric.unitOverride);
            encoded.put("multiplier", metric.multiplier);
            encoded.put("offset", metric.offset);
            encoded.put("decimals", metric.decimals);
            encoded.put("valueColor", metric.valueColor);
            encoded.put("labelColor", metric.labelColor);
            metrics.put(encoded);
            order.put(metric.id);
        }
        root.put("metrics", metrics);
        root.put("order", order);
        return root;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
