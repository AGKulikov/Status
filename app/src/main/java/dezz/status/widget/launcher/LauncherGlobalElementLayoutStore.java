/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.Preferences;

/**
 * Persists independent screen-space rectangles for real HOME elements.
 *
 * <p>Missing entries are intentional: their first rectangle is migrated from the current live
 * panel layout after Android has measured it, preserving existing user arrangements.</p>
 */
public final class LauncherGlobalElementLayoutStore {
    public static final int SCHEMA_VERSION = 2;
    private static final int MIN_WIDTH = 36;
    private static final int MIN_HEIGHT = 28;

    public static final class Geometry {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        public Geometry(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }
    }

    public enum ScaleMode {
        /** Preserve source proportions and show the complete element. */
        FIT,
        /** Preserve proportions and fill the widget, cropping only the overflow. */
        CROP,
        /** Explicit legacy mode; may distort the source. */
        STRETCH
    }

    public enum TapAction {
        /** Forward the gesture to the original live element. */
        INHERIT,
        /** Keep the widget informational. */
        NONE,
        /** Open the selected Android activity. */
        APP
    }

    /** Deep per-widget rendering and interaction settings, independent of its rectangle. */
    public static final class Appearance {
        @NonNull public ScaleMode scaleMode = ScaleMode.FIT;
        public boolean preserveAspectRatio = true;
        public int paddingLeftPx;
        public int paddingTopPx;
        public int paddingRightPx;
        public int paddingBottomPx;
        /** Zero and empty strings mean “inherit from the live source”. */
        public int textSizeSp;
        @NonNull public String textColor = "";
        @NonNull public String iconColor = "";
        @NonNull public String fontFamily = "";
        public boolean textBold;
        public boolean textItalic;
        /** -1 inherits, 0=start/top, 1=center, 2=end/bottom. */
        public int horizontalAlignment = -1;
        public int verticalAlignment = -1;
        @NonNull public String backgroundColor = "#00000000";
        public int cornerRadiusPx;
        @NonNull public TapAction tapAction = TapAction.INHERIT;
        @NonNull public String appComponent = "";

        @NonNull
        public Appearance copy() {
            Appearance value = new Appearance();
            value.scaleMode = scaleMode;
            value.preserveAspectRatio = preserveAspectRatio;
            value.paddingLeftPx = paddingLeftPx;
            value.paddingTopPx = paddingTopPx;
            value.paddingRightPx = paddingRightPx;
            value.paddingBottomPx = paddingBottomPx;
            value.textSizeSp = textSizeSp;
            value.textColor = textColor;
            value.iconColor = iconColor;
            value.fontFamily = fontFamily;
            value.textBold = textBold;
            value.textItalic = textItalic;
            value.horizontalAlignment = horizontalAlignment;
            value.verticalAlignment = verticalAlignment;
            value.backgroundColor = backgroundColor;
            value.cornerRadiusPx = cornerRadiusPx;
            value.tapAction = tapAction;
            value.appComponent = appComponent;
            return value;
        }

        void normalize() {
            if (scaleMode == null) scaleMode = ScaleMode.FIT;
            paddingLeftPx = clampStyle(paddingLeftPx, 0, 240);
            paddingTopPx = clampStyle(paddingTopPx, 0, 240);
            paddingRightPx = clampStyle(paddingRightPx, 0, 240);
            paddingBottomPx = clampStyle(paddingBottomPx, 0, 240);
            textSizeSp = clampStyle(textSizeSp, 0, 180);
            textColor = clean(textColor);
            iconColor = clean(iconColor);
            fontFamily = clean(fontFamily);
            horizontalAlignment = clampStyle(horizontalAlignment, -1, 2);
            verticalAlignment = clampStyle(verticalAlignment, -1, 2);
            backgroundColor = colorOrTransparent(backgroundColor);
            cornerRadiusPx = clampStyle(cornerRadiusPx, 0, 240);
            if (tapAction == null) tapAction = TapAction.INHERIT;
            appComponent = clean(appComponent);
            if (tapAction == TapAction.APP && appComponent.isEmpty()) {
                tapAction = TapAction.INHERIT;
            }
        }
    }

    private final Preferences preferences;
    private final Map<String, Geometry> geometry = new LinkedHashMap<>();
    private final Map<String, Appearance> appearances = new LinkedHashMap<>();
    private int screenWidth;
    private int screenHeight;

    public LauncherGlobalElementLayoutStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public void load(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        geometry.clear();
        appearances.clear();
        String raw = preferences.launcherGlobalElementsJson.get();
        if (raw == null || raw.trim().isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            int version = root.optInt("version", 0);
            if (version != 1 && version != SCHEMA_VERSION) return;
            JSONObject elements = root.optJSONObject("elements");
            if (elements == null) return;
            java.util.Iterator<String> keys = elements.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject value = elements.optJSONObject(id);
                if (value == null) continue;
                Geometry parsed = new Geometry(
                        value.optInt("x", 0),
                        value.optInt("y", 0),
                        value.optInt("width", MIN_WIDTH),
                        value.optInt("height", MIN_HEIGHT));
                geometry.put(id, clamp(parsed, screenWidth, screenHeight));
                JSONObject appearance = value.optJSONObject("appearance");
                if (appearance != null) {
                    appearances.put(id, decodeAppearance(appearance));
                }
            }
        } catch (JSONException ignored) {
            geometry.clear();
            appearances.clear();
        }
    }

    @Nullable
    public Geometry get(@NonNull String id) {
        return geometry.get(id);
    }

    public void put(@NonNull String id, @NonNull Geometry value) {
        geometry.put(id, clamp(value, screenWidth, screenHeight));
        save();
    }

    @NonNull
    public Appearance getAppearance(@NonNull String id) {
        Appearance value = appearances.get(id);
        return value == null ? new Appearance() : value.copy();
    }

    public void putAppearance(@NonNull String id, @NonNull Appearance source) {
        Appearance value = source.copy();
        value.normalize();
        appearances.put(id, value);
        save();
    }

    public void reset() {
        geometry.clear();
        appearances.clear();
        preferences.launcherGlobalElementsJson.set("");
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", SCHEMA_VERSION);
            JSONObject elements = new JSONObject();
            for (Map.Entry<String, Geometry> entry : geometry.entrySet()) {
                Geometry value = entry.getValue();
                JSONObject encoded = new JSONObject();
                encoded.put("x", value.x);
                encoded.put("y", value.y);
                encoded.put("width", value.width);
                encoded.put("height", value.height);
                Appearance appearance = appearances.get(entry.getKey());
                if (appearance != null) {
                    encoded.put("appearance", encodeAppearance(appearance));
                }
                elements.put(entry.getKey(), encoded);
            }
            root.put("elements", elements);
            preferences.launcherGlobalElementsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    static Geometry clamp(@NonNull Geometry source, int rawWidth, int rawHeight) {
        int screenWidth = Math.max(1, rawWidth);
        int screenHeight = Math.max(1, rawHeight);
        int minWidth = Math.min(MIN_WIDTH, screenWidth);
        int minHeight = Math.min(MIN_HEIGHT, screenHeight);
        int width = Math.max(minWidth, Math.min(source.width, screenWidth));
        int height = Math.max(minHeight, Math.min(source.height, screenHeight));
        int x = Math.max(0, Math.min(source.x, Math.max(0, screenWidth - width)));
        int y = Math.max(0, Math.min(source.y, Math.max(0, screenHeight - height)));
        return new Geometry(x, y, width, height);
    }

    @NonNull
    private static Appearance decodeAppearance(@NonNull JSONObject encoded) {
        Appearance value = new Appearance();
        try {
            value.scaleMode = ScaleMode.valueOf(
                    encoded.optString("scaleMode", ScaleMode.FIT.name()));
        } catch (IllegalArgumentException ignored) {
            value.scaleMode = ScaleMode.FIT;
        }
        value.preserveAspectRatio =
                encoded.optBoolean("preserveAspectRatio", true);
        value.paddingLeftPx = encoded.optInt("paddingLeftPx", 0);
        value.paddingTopPx = encoded.optInt("paddingTopPx", 0);
        value.paddingRightPx = encoded.optInt("paddingRightPx", 0);
        value.paddingBottomPx = encoded.optInt("paddingBottomPx", 0);
        value.textSizeSp = encoded.optInt("textSizeSp", 0);
        value.textColor = encoded.optString("textColor", "");
        value.iconColor = encoded.optString("iconColor", "");
        value.fontFamily = encoded.optString("fontFamily", "");
        value.textBold = encoded.optBoolean("textBold", false);
        value.textItalic = encoded.optBoolean("textItalic", false);
        value.horizontalAlignment = encoded.optInt("horizontalAlignment", -1);
        value.verticalAlignment = encoded.optInt("verticalAlignment", -1);
        value.backgroundColor = encoded.optString("backgroundColor", "#00000000");
        value.cornerRadiusPx = encoded.optInt("cornerRadiusPx", 0);
        try {
            value.tapAction = TapAction.valueOf(
                    encoded.optString("tapAction", TapAction.INHERIT.name()));
        } catch (IllegalArgumentException ignored) {
            value.tapAction = TapAction.INHERIT;
        }
        value.appComponent = encoded.optString("appComponent", "");
        value.normalize();
        return value;
    }

    @NonNull
    private static JSONObject encodeAppearance(@NonNull Appearance source)
            throws JSONException {
        Appearance value = source.copy();
        value.normalize();
        return new JSONObject()
                .put("scaleMode", value.scaleMode.name())
                .put("preserveAspectRatio", value.preserveAspectRatio)
                .put("paddingLeftPx", value.paddingLeftPx)
                .put("paddingTopPx", value.paddingTopPx)
                .put("paddingRightPx", value.paddingRightPx)
                .put("paddingBottomPx", value.paddingBottomPx)
                .put("textSizeSp", value.textSizeSp)
                .put("textColor", value.textColor)
                .put("iconColor", value.iconColor)
                .put("fontFamily", value.fontFamily)
                .put("textBold", value.textBold)
                .put("textItalic", value.textItalic)
                .put("horizontalAlignment", value.horizontalAlignment)
                .put("verticalAlignment", value.verticalAlignment)
                .put("backgroundColor", value.backgroundColor)
                .put("cornerRadiusPx", value.cornerRadiusPx)
                .put("tapAction", value.tapAction.name())
                .put("appComponent", value.appComponent);
    }

    private static int clampStyle(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String colorOrTransparent(@Nullable String value) {
        String clean = clean(value);
        return clean.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                ? clean : "#00000000";
    }
}
