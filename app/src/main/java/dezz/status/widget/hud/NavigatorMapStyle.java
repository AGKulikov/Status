/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Builds the MapKit style passed to the independent Navigator surface.
 *
 * <p>An explicitly supplied style is never rewritten. The generated style is deliberately
 * conservative: an empty/default configuration returns {@code []}, which keeps the exact stock
 * Navigator rendering. Feature filters are added only after the user changes a visual option in
 * Status Widget.</p>
 */
final class NavigatorMapStyle {
    private static final int MAX_STYLE_CHARS = 131_072;

    private NavigatorMapStyle() {}

    @NonNull
    static String build(@NonNull JSONObject options) {
        String custom = bounded(options.optString("mapStyle", ""), MAX_STYLE_CHARS);
        if (!custom.isEmpty()) return custom;

        boolean roadOnly = options.optBoolean("roadOnly", false);
        boolean labels = !roadOnly && options.optBoolean("showLabels", true);
        boolean pois = !roadOnly && options.optBoolean("showPois", true);
        boolean buildings = !roadOnly && options.optBoolean("showBuildings", true);
        boolean parks = !roadOnly && options.optBoolean("showParks", true);
        boolean water = !roadOnly && options.optBoolean("showWater", true);

        JSONArray style = new JSONArray();
        try {
            if (!labels) style.put(rule("", "label", "visibility", "off"));
            if (!pois) style.put(rule("poi", "", "visibility", "off"));
            if (!buildings) style.put(rule("building", "", "visibility", "off"));
            if (!parks) style.put(rule("vegetation", "", "visibility", "off"));
            if (!water) style.put(rule("water", "", "visibility", "off"));

            addColor(style, "background", options.optString("backgroundColor", ""));
            addColor(style, "road", options.optString("roadsColor", ""));
            addColor(style, "vegetation", options.optString("parksColor", ""));
            addColor(style, "water", options.optString("waterColor", ""));

            if (roadOnly) {
                // Black non-road geometry is least distracting on the reflected 728x190 plane.
                if (options.optString("backgroundColor", "").trim().isEmpty()) {
                    style.put(rule("background", "", "color", "#FF000000"));
                }
                if (options.optString("parksColor", "").trim().isEmpty()) {
                    style.put(rule("vegetation", "", "color", "#FF000000"));
                }
                if (options.optString("waterColor", "").trim().isEmpty()) {
                    style.put(rule("water", "", "color", "#FF000000"));
                }
            }
        } catch (JSONException impossible) {
            return "[]";
        }
        String result = style.toString();
        return result.length() <= MAX_STYLE_CHARS ? result : "[]";
    }

    private static void addColor(JSONArray output, String type, String raw)
            throws JSONException {
        String color = color(raw);
        if (!color.isEmpty()) output.put(rule(type, "", "color", color));
    }

    private static JSONObject rule(String types, String elements, String key, String value)
            throws JSONException {
        JSONObject result = new JSONObject();
        if (!types.isEmpty()) result.put("types", types);
        if (!elements.isEmpty()) result.put("elements", elements);
        result.put("stylers", new JSONObject().put(key, value));
        return result;
    }

    @NonNull
    private static String color(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.matches("#[0-9a-fA-F]{6}") || value.matches("#[0-9a-fA-F]{8}")) {
            return value.toUpperCase(java.util.Locale.ROOT);
        }
        return "";
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= maximum && value.indexOf('\u0000') < 0 ? value : "";
    }
}
