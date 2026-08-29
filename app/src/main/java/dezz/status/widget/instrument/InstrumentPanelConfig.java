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
    public static final int SCHEMA_VERSION = 1;
    public static final int DESIGN_WIDTH = 1920;
    public static final int DESIGN_HEIGHT = 720;
    public static final int DEFAULT_COLUMNS = 48;
    public static final int DEFAULT_ROWS = 18;

    public int displayId = 2;
    public int columns = DEFAULT_COLUMNS;
    public int rows = DEFAULT_ROWS;
    public boolean transparentBackground;
    @NonNull public InstrumentStyleFamily defaultStyle = InstrumentStyleFamily.GRAND_TOURER;
    @NonNull public final List<InstrumentElementConfig> elements = new ArrayList<>();

    @NonNull
    public static InstrumentPanelConfig defaults() {
        InstrumentPanelConfig config = new InstrumentPanelConfig();
        InstrumentElementConfig speed = new InstrumentElementConfig(
                "cluster_speed", InstrumentElementType.ANALOG_SPEEDOMETER,
                InstrumentStyleFamily.GRAND_TOURER);
        speed.x = 2; speed.y = 3; speed.width = 12; speed.height = 12;
        InstrumentElementConfig map = new InstrumentElementConfig(
                "cluster_map", InstrumentElementType.NAV_MAP,
                InstrumentStyleFamily.NAVIGATION_FIRST);
        map.x = 14; map.y = 1; map.width = 20; map.height = 16;
        InstrumentElementConfig rpm = new InstrumentElementConfig(
                "cluster_rpm", InstrumentElementType.ANALOG_TACHOMETER,
                InstrumentStyleFamily.GRAND_TOURER);
        rpm.x = 34; rpm.y = 3; rpm.width = 12; rpm.height = 12;
        InstrumentElementConfig gear = new InstrumentElementConfig(
                "cluster_gear", InstrumentElementType.GEAR,
                InstrumentStyleFamily.GRAND_TOURER);
        gear.x = 21; gear.y = 12; gear.width = 6; gear.height = 4; gear.zIndex = 10;
        config.elements.add(speed);
        config.elements.add(map);
        config.elements.add(rpm);
        config.elements.add(gear);
        config.normalize();
        return config;
    }

    @NonNull
    public InstrumentPanelConfig copy() {
        InstrumentPanelConfig value = new InstrumentPanelConfig();
        value.displayId = displayId;
        value.columns = columns;
        value.rows = rows;
        value.transparentBackground = transparentBackground;
        value.defaultStyle = defaultStyle;
        for (InstrumentElementConfig element : elements) value.elements.add(element.copy());
        value.normalize();
        return value;
    }

    public void normalize() {
        displayId = Math.max(0, displayId);
        columns = Math.max(12, Math.min(96, columns));
        rows = Math.max(6, Math.min(54, rows));
        for (InstrumentElementConfig element : elements) element.normalize(columns, rows);
        Collections.sort(elements, Comparator.comparingInt(value -> value.zIndex));
    }

    /** Exact vendor metrics required by the currently visible document. */
    @NonNull
    public Set<String> telemetryMetricIds() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (InstrumentElementConfig element : elements) {
            if (!element.enabled || element.type.metricId.isEmpty()) continue;
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
                .put("defaultStyle", defaultStyle.name())
                .put("elements", items);
    }

    @NonNull
    public static InstrumentPanelConfig fromJson(@NonNull JSONObject json) {
        InstrumentPanelConfig value = new InstrumentPanelConfig();
        value.displayId = json.optInt("displayId", 2);
        value.columns = json.optInt("columns", DEFAULT_COLUMNS);
        value.rows = json.optInt("rows", DEFAULT_ROWS);
        value.transparentBackground = json.optBoolean("transparentBackground", false);
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
        value.normalize();
        return value.elements.isEmpty() ? defaults() : value;
    }
}
