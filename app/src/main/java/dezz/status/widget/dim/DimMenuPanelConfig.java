/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Versioned geometry and appearance of the steering-wheel DIM menu. */
public final class DimMenuPanelConfig {
    public static final int SCHEMA_VERSION = 1;
    public static final int STOCK_NAVIGATION_TAB = 2;

    public int displayId = 2;
    public int x = 740;
    public int y = 720;
    public int width = 414;
    public int height = 284;
    @NonNull public String title = "Natro";
    public int visibleRows = 4;
    public int rowHeightPx = 50;
    public int contentPaddingPx = 12;
    public int rowGapPx = 4;
    public int cornerRadiusPx = 20;
    public int borderWidthPx = 2;
    public int titleTextSizeSp = 21;
    public int rowTextSizeSp = 22;
    public int iconSizePx = 32;
    public int panelOpacityPercent = 92;
    @NonNull public String backgroundColor = "#FF11151B";
    @NonNull public String selectedColor = "#FF1478FF";
    @NonNull public String textColor = "#FFFFFFFF";
    @NonNull public String mutedTextColor = "#FFADB7C8";
    @NonNull public String borderColor = "#665E718A";
    public boolean showTitle = true;
    public boolean showIcons = true;
    public boolean showText = true;
    public boolean wrapSelection = true;
    public boolean invertScroll = false;
    public boolean closeAfterAction = false;
    public boolean navigationTabOnly = true;
    public boolean hideForMnav = true;
    public boolean hideForControlCenter = true;
    public boolean hideWhenDisplayOff = true;

    @NonNull
    public static DimMenuPanelConfig fromJson(String raw) {
        DimMenuPanelConfig value = new DimMenuPanelConfig();
        if (raw == null || raw.trim().isEmpty()) return value;
        try {
            JSONObject json = new JSONObject(raw);
            if (json.optInt("version", 0) > SCHEMA_VERSION) return value;
            value.displayId = json.optInt("displayId", value.displayId);
            value.x = json.optInt("x", value.x);
            value.y = json.optInt("y", value.y);
            value.width = json.optInt("width", value.width);
            value.height = json.optInt("height", value.height);
            value.title = json.optString("title", value.title);
            value.visibleRows = json.optInt("visibleRows", value.visibleRows);
            value.rowHeightPx = json.optInt("rowHeightPx", value.rowHeightPx);
            value.contentPaddingPx = json.optInt("contentPaddingPx", value.contentPaddingPx);
            value.rowGapPx = json.optInt("rowGapPx", value.rowGapPx);
            value.cornerRadiusPx = json.optInt("cornerRadiusPx", value.cornerRadiusPx);
            value.borderWidthPx = json.optInt("borderWidthPx", value.borderWidthPx);
            value.titleTextSizeSp = json.optInt("titleTextSizeSp", value.titleTextSizeSp);
            value.rowTextSizeSp = json.optInt("rowTextSizeSp", value.rowTextSizeSp);
            value.iconSizePx = json.optInt("iconSizePx", value.iconSizePx);
            value.panelOpacityPercent = json.optInt(
                    "panelOpacityPercent", value.panelOpacityPercent);
            value.backgroundColor = json.optString(
                    "backgroundColor", value.backgroundColor);
            value.selectedColor = json.optString("selectedColor", value.selectedColor);
            value.textColor = json.optString("textColor", value.textColor);
            value.mutedTextColor = json.optString(
                    "mutedTextColor", value.mutedTextColor);
            value.borderColor = json.optString("borderColor", value.borderColor);
            value.showTitle = json.optBoolean("showTitle", value.showTitle);
            value.showIcons = json.optBoolean("showIcons", value.showIcons);
            value.showText = json.optBoolean("showText", value.showText);
            value.wrapSelection = json.optBoolean("wrapSelection", value.wrapSelection);
            value.invertScroll = json.optBoolean("invertScroll", value.invertScroll);
            value.closeAfterAction = json.optBoolean(
                    "closeAfterAction", value.closeAfterAction);
            value.navigationTabOnly = json.optBoolean(
                    "navigationTabOnly", value.navigationTabOnly);
            value.hideForMnav = json.optBoolean("hideForMnav", value.hideForMnav);
            value.hideForControlCenter = json.optBoolean(
                    "hideForControlCenter", value.hideForControlCenter);
            value.hideWhenDisplayOff = json.optBoolean(
                    "hideWhenDisplayOff", value.hideWhenDisplayOff);
        } catch (JSONException ignored) {
            return new DimMenuPanelConfig();
        }
        value.normalize();
        return value;
    }

    @NonNull
    public String toJson() {
        normalize();
        try {
            return new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("displayId", displayId)
                    .put("x", x).put("y", y)
                    .put("width", width).put("height", height)
                    .put("title", title)
                    .put("visibleRows", visibleRows)
                    .put("rowHeightPx", rowHeightPx)
                    .put("contentPaddingPx", contentPaddingPx)
                    .put("rowGapPx", rowGapPx)
                    .put("cornerRadiusPx", cornerRadiusPx)
                    .put("borderWidthPx", borderWidthPx)
                    .put("titleTextSizeSp", titleTextSizeSp)
                    .put("rowTextSizeSp", rowTextSizeSp)
                    .put("iconSizePx", iconSizePx)
                    .put("panelOpacityPercent", panelOpacityPercent)
                    .put("backgroundColor", backgroundColor)
                    .put("selectedColor", selectedColor)
                    .put("textColor", textColor)
                    .put("mutedTextColor", mutedTextColor)
                    .put("borderColor", borderColor)
                    .put("showTitle", showTitle)
                    .put("showIcons", showIcons)
                    .put("showText", showText)
                    .put("wrapSelection", wrapSelection)
                    .put("invertScroll", invertScroll)
                    .put("closeAfterAction", closeAfterAction)
                    .put("navigationTabOnly", navigationTabOnly)
                    .put("hideForMnav", hideForMnav)
                    .put("hideForControlCenter", hideForControlCenter)
                    .put("hideWhenDisplayOff", hideWhenDisplayOff)
                    .toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    public void normalize() {
        displayId = clamp(displayId, 0, 32);
        x = clamp(x, -4000, 4000);
        y = clamp(y, -4000, 4000);
        width = clamp(width, 220, 1200);
        height = clamp(height, 120, 1200);
        title = bounded(title, "Natro", 80);
        visibleRows = clamp(visibleRows, 1, 12);
        rowHeightPx = clamp(rowHeightPx, 28, 140);
        contentPaddingPx = clamp(contentPaddingPx, 0, 80);
        rowGapPx = clamp(rowGapPx, 0, 40);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 100);
        borderWidthPx = clamp(borderWidthPx, 0, 16);
        titleTextSizeSp = clamp(titleTextSizeSp, 10, 48);
        rowTextSizeSp = clamp(rowTextSizeSp, 10, 52);
        iconSizePx = clamp(iconSizePx, 16, 96);
        panelOpacityPercent = clamp(panelOpacityPercent, 10, 100);
        backgroundColor = color(backgroundColor, "#FF11151B");
        selectedColor = color(selectedColor, "#FF1478FF");
        textColor = color(textColor, "#FFFFFFFF");
        mutedTextColor = color(mutedTextColor, "#FFADB7C8");
        borderColor = color(borderColor, "#665E718A");
    }

    @NonNull
    public DimMenuPanelConfig copy() {
        return fromJson(toJson());
    }

    @NonNull
    private static String bounded(String value, @NonNull String fallback, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) normalized = fallback;
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }

    @NonNull
    private static String color(String value, @NonNull String fallback) {
        if (value == null) return fallback;
        String normalized = value.trim();
        return normalized.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                ? normalized : fallback;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
