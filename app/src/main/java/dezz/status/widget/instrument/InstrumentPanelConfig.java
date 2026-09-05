/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Versioned 1920x720 dashboard document shared by the editor and the external activity. */
public final class InstrumentPanelConfig {
    public static final int SCHEMA_VERSION = 2;
    public static final int DESIGN_WIDTH = 1920;
    public static final int DESIGN_HEIGHT = 720;
    public static final int DEFAULT_COLUMNS = 48;
    public static final int DEFAULT_ROWS = 18;

    public int displayId = 2;
    public int columns = DEFAULT_COLUMNS;
    public int rows = DEFAULT_ROWS;
    public boolean transparentBackground;
    @NonNull public String presetId = InstrumentPanelPreset.SLATE_HORIZON.id;
    public int presetLayoutRevision = InstrumentPanelPreset.LAYOUT_REVISION;
    @NonNull public String backgroundBottomColor = "#FF16283D";
    /** Percentage of panel height which remains pure black before the bottom gradient begins. */
    public int blackZonePercent = 46;
    @NonNull public InstrumentStyleFamily defaultStyle = InstrumentStyleFamily.SLATE_HORIZON;
    @NonNull public final List<InstrumentElementConfig> elements = new ArrayList<>();

    @NonNull
    public static InstrumentPanelConfig defaults() {
        return InstrumentPanelPreset.SLATE_HORIZON.create();
    }

    @NonNull
    public InstrumentPanelConfig copy() {
        InstrumentPanelConfig value = new InstrumentPanelConfig();
        value.displayId = displayId;
        value.columns = columns;
        value.rows = rows;
        value.transparentBackground = transparentBackground;
        value.presetId = presetId;
        value.presetLayoutRevision = presetLayoutRevision;
        value.backgroundBottomColor = backgroundBottomColor;
        value.blackZonePercent = blackZonePercent;
        value.defaultStyle = defaultStyle;
        for (InstrumentElementConfig element : elements) value.elements.add(element.copy());
        value.normalize();
        return value;
    }

    public void normalize() {
        displayId = Math.max(0, displayId);
        columns = Math.max(12, Math.min(96, columns));
        rows = Math.max(6, Math.min(54, rows));
        presetId = InstrumentPanelPreset.fromId(presetId).id;
        backgroundBottomColor = color(backgroundBottomColor, "#FF16283D");
        blackZonePercent = Math.max(0, Math.min(95, blackZonePercent));
        for (InstrumentElementConfig element : elements) element.normalize(columns, rows);
        Collections.sort(elements, Comparator.comparingInt(value -> value.zIndex));
    }

    /** Exact vendor metrics required by the currently visible document. */
    @NonNull
    public Set<String> telemetryMetricIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (InstrumentElementConfig element : elements) {
            if (!element.enabled) continue;
            if (element.type == InstrumentElementType.INFO_BLOCK) {
                for (int row = 1; row <= 3; row++) {
                    InstrumentInfoMetric metric = InstrumentInfoMetric.fromName(
                            element.options.optString("row" + row),
                            row == 1 ? InstrumentInfoMetric.RANGE
                                    : row == 2 ? InstrumentInfoMetric.AVERAGE_CONSUMPTION
                                    : InstrumentInfoMetric.AMBIENT_TEMPERATURE);
                    if (!metric.metricId.isEmpty()) result.add(metric.metricId);
                    if (!metric.fallbackMetricId.isEmpty()) result.add(metric.fallbackMetricId);
                }
                continue;
            }
            if (element.type.metricId.isEmpty()) continue;
            result.add(element.type.metricId);
            // Total range is absent on some KX11 firmware; retain the fuel-range fallback only
            // when a range element is actually visible.
            if (element.type == InstrumentElementType.RANGE) {
                result.add("ISensor.range_fuel");
            }
        }
        return Collections.unmodifiableSet(result);
    }

    public boolean hasVisibleClock() {
        for (InstrumentElementConfig element : elements) {
            if (element.enabled && element.type.usesClock()) return true;
        }
        return false;
    }

    public boolean hasVisibleNavigationInfo() {
        for (InstrumentElementConfig element : elements) {
            if (element.enabled && element.type.usesNavigationState()) return true;
        }
        return false;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONArray items = new JSONArray();
        for (InstrumentElementConfig element : elements) items.put(element.toJson());
        return new JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("displayId", displayId)
                .put("columns", columns)
                .put("rows", rows)
                .put("transparentBackground", transparentBackground)
                .put("presetId", presetId)
                .put("presetLayoutRevision", presetLayoutRevision)
                .put("backgroundBottomColor", backgroundBottomColor)
                .put("blackZonePercent", blackZonePercent)
                .put("defaultStyle", defaultStyle.name())
                .put("elements", items);
    }

    @NonNull
    public static InstrumentPanelConfig fromJson(@NonNull JSONObject json) {
        int schema = json.optInt("schema", 1);
        if (schema != SCHEMA_VERSION) {
            // The ten experimental styles were intentionally retired. Preserve only launch and
            // background ownership while moving old layouts to the first approved modular preset.
            InstrumentPanelConfig migrated = defaults();
            migrated.displayId = Math.max(0, json.optInt("displayId", 2));
            migrated.transparentBackground = json.optBoolean(
                    "transparentBackground", false);
            return migrated;
        }
        InstrumentPanelConfig value = new InstrumentPanelConfig();
        value.displayId = json.optInt("displayId", 2);
        value.columns = json.optInt("columns", DEFAULT_COLUMNS);
        value.rows = json.optInt("rows", DEFAULT_ROWS);
        value.transparentBackground = json.optBoolean("transparentBackground", false);
        value.presetId = json.optString("presetId", InstrumentPanelPreset.SLATE_HORIZON.id);
        value.presetLayoutRevision = json.optInt("presetLayoutRevision", 1);
        value.backgroundBottomColor = json.optString(
                "backgroundBottomColor", "#FF16283D");
        value.blackZonePercent = json.optInt("blackZonePercent", 46);
        value.defaultStyle = InstrumentStyleFamily.fromName(json.optString("defaultStyle"));
        JSONArray items = json.optJSONArray("elements");
        if (items != null) {
            for (int index = 0; index < items.length(); index++) {
                JSONObject item = items.optJSONObject(index);
                if (item != null) {
                    value.elements.add(InstrumentElementConfig.fromJson(
                            item, value.columns, value.rows));
                }
            }
        }
        InstrumentPanelPreset.upgradeLegacyLayout(value);
        value.normalize();
        if (!value.elements.isEmpty()) return value;
        InstrumentPanelConfig fallback = InstrumentPanelPreset.fromId(value.presetId).create();
        fallback.displayId = value.displayId;
        fallback.transparentBackground = value.transparentBackground;
        fallback.backgroundBottomColor = value.backgroundBottomColor;
        fallback.blackZonePercent = value.blackZonePercent;
        fallback.normalize();
        return fallback;
    }

    @NonNull
    private static String color(@NonNull String raw, @NonNull String fallback) {
        String value = raw.trim();
        if (value.isEmpty() || value.charAt(0) != '#') return fallback;
        int digits = value.length() - 1;
        if (digits != 3 && digits != 4 && digits != 6 && digits != 8) return fallback;
        for (int index = 1; index < value.length(); index++) {
            char digit = value.charAt(index);
            boolean hexadecimal = digit >= '0' && digit <= '9'
                    || digit >= 'a' && digit <= 'f'
                    || digit >= 'A' && digit <= 'F';
            if (!hexadecimal) return fallback;
        }
        return value;
    }
}
