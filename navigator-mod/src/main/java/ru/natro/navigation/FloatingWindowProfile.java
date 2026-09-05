/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import org.json.JSONException;
import org.json.JSONObject;

/** Defensive parser for the mainFloatingWindow block owned by Natro. */
final class FloatingWindowProfile {
    boolean enabled = true;
    int leftPercent = 4;
    int topPercent = 6;
    int widthPercent = 75;
    int heightPercent = 82;
    boolean movementLocked;
    boolean resizeLocked;
    int cornerRadiusDp = 24;
    int opacityPercent = 100;
    int borderWidthDp;
    String borderColor = "#00000000";
    int shadowRadiusDp = 20;
    String shadowColor = "#66000000";
    String backgroundColor = "#00000000";
    boolean aspectRatioLocked;
    boolean rememberGeometry = true;
    boolean dragHandleVisible = true;
    boolean resizeHandleVisible = true;
    boolean modeButtonVisible = true;
    String modeButtonPosition = "TOP_LEFT";
    int modeButtonSizeDp = 50;
    int modeButtonOpacityPercent = 85;
    boolean closeButtonVisible = true;

    /** True when applying the next full Natro document cannot change the floating window. */
    boolean sameWindowContract(FloatingWindowProfile other) {
        return other != null
                && enabled == other.enabled
                && leftPercent == other.leftPercent
                && topPercent == other.topPercent
                && widthPercent == other.widthPercent
                && heightPercent == other.heightPercent
                && movementLocked == other.movementLocked
                && resizeLocked == other.resizeLocked
                && cornerRadiusDp == other.cornerRadiusDp
                && opacityPercent == other.opacityPercent
                && borderWidthDp == other.borderWidthDp
                && borderColor.equals(other.borderColor)
                && shadowRadiusDp == other.shadowRadiusDp
                && shadowColor.equals(other.shadowColor)
                && backgroundColor.equals(other.backgroundColor)
                && aspectRatioLocked == other.aspectRatioLocked
                && rememberGeometry == other.rememberGeometry
                && dragHandleVisible == other.dragHandleVisible
                && resizeHandleVisible == other.resizeHandleVisible
                && modeButtonVisible == other.modeButtonVisible
                && closeButtonVisible == other.closeButtonVisible;
    }

    static FloatingWindowProfile fromConfiguration(String raw) {
        FloatingWindowProfile result = new FloatingWindowProfile();
        if (raw == null || raw.length() > 384 * 1024 || raw.indexOf('\u0000') >= 0) {
            return result;
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject source = root.optJSONObject("mainFloatingWindow");
            if (source == null) return result;
            result.enabled = source.optBoolean("enabled", result.enabled);
            result.leftPercent = source.optInt("leftPercent", result.leftPercent);
            result.topPercent = source.optInt("topPercent", result.topPercent);
            result.widthPercent = source.optInt("widthPercent", result.widthPercent);
            result.heightPercent = source.optInt("heightPercent", result.heightPercent);
            result.movementLocked = source.optBoolean(
                    "movementLocked", result.movementLocked);
            result.resizeLocked = source.optBoolean("resizeLocked", result.resizeLocked);
            result.cornerRadiusDp = source.optInt("cornerRadiusDp", result.cornerRadiusDp);
            result.opacityPercent = source.optInt("opacityPercent", result.opacityPercent);
            result.borderWidthDp = source.optInt("borderWidthDp", result.borderWidthDp);
            result.borderColor = source.optString("borderColor", result.borderColor);
            result.shadowRadiusDp = source.optInt("shadowRadiusDp", result.shadowRadiusDp);
            result.shadowColor = source.optString("shadowColor", result.shadowColor);
            result.backgroundColor = source.optString(
                    "backgroundColor", result.backgroundColor);
            result.aspectRatioLocked = source.optBoolean(
                    "aspectRatioLocked", result.aspectRatioLocked);
            result.rememberGeometry = source.optBoolean(
                    "rememberGeometry", result.rememberGeometry);
            result.dragHandleVisible = source.optBoolean(
                    "dragHandleVisible", result.dragHandleVisible);
            result.resizeHandleVisible = source.optBoolean(
                    "resizeHandleVisible", result.resizeHandleVisible);
            result.modeButtonVisible = source.optBoolean(
                    "modeButtonVisible", result.modeButtonVisible);
            result.modeButtonPosition = source.optString(
                    "modeButtonPosition", result.modeButtonPosition);
            result.modeButtonSizeDp = source.optInt(
                    "modeButtonSizeDp", result.modeButtonSizeDp);
            result.modeButtonOpacityPercent = source.optInt(
                    "modeButtonOpacityPercent", result.modeButtonOpacityPercent);
            result.closeButtonVisible = source.optBoolean(
                    "closeButtonVisible", result.closeButtonVisible);
        } catch (JSONException | RuntimeException ignored) {}
        result.normalize();
        return result;
    }

    private void normalize() {
        widthPercent = clamp(widthPercent, 20, 100);
        heightPercent = clamp(heightPercent, 20, 100);
        leftPercent = clamp(leftPercent, 0, 100 - widthPercent);
        topPercent = clamp(topPercent, 0, 100 - heightPercent);
        cornerRadiusDp = clamp(cornerRadiusDp, 0, 160);
        opacityPercent = clamp(opacityPercent, 20, 100);
        borderWidthDp = clamp(borderWidthDp, 0, 24);
        shadowRadiusDp = clamp(shadowRadiusDp, 0, 96);
        modeButtonSizeDp = clamp(modeButtonSizeDp, 28, 96);
        modeButtonOpacityPercent = clamp(modeButtonOpacityPercent, 20, 100);
        borderColor = color(borderColor, "#00000000");
        shadowColor = color(shadowColor, "#66000000");
        // Kept in the wire schema for compatibility; KX11 requires the outer window plane to be
        // transparent so launcher/status controls remain visible around the resized map.
        backgroundColor = "#00000000";
        // Wire compatibility only. Current builds insert the toggle into Navigator's stock left
        // rail, whose shell owns position, size, opacity and appearance. Do not revive legacy
        // TOP_RIGHT/BOTTOM_* coordinates or apply the old overlay geometry fields.
        modeButtonPosition = "TOP_LEFT";
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String color(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (value.matches("#[0-9A-F]{6}")) return "#FF" + value.substring(1);
        return value.matches("#[0-9A-F]{8}") ? value : fallback;
    }
}
