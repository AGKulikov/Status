/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.popup;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import dezz.status.widget.Preferences;
import dezz.status.widget.automation.AutomationContract;

/** Geometry and visual settings of one independent floating overlay window. */
public final class PopupOverlayConfig {
    public static final int SCHEMA_VERSION = 1;

    public String id;
    public String name;
    public boolean enabled;
    /** Used only when neither a scenario nor a connector supplied overlay visibility. */
    public boolean defaultVisible;
    public int order;
    public int width;
    public int height;
    public int rows;
    public int columns;
    public int x;
    public int y;
    public int paddingLeft;
    public int paddingTop;
    public int paddingRight;
    public int paddingBottom;
    public int cellGap;
    public String backgroundColor;
    public int backgroundAlpha;
    public int cornerRadius;

    @NonNull
    public static PopupOverlayConfig create(@NonNull String id, @NonNull String name, int order) {
        PopupOverlayConfig config = new PopupOverlayConfig();
        config.id = AutomationContract.requireSafeId(id);
        config.name = normalizedName(name, config.id);
        config.enabled = true;
        config.defaultVisible = true;
        config.order = Math.max(0, order);
        config.width = 500;
        config.height = 500;
        config.rows = 2;
        config.columns = 2;
        config.x = 200 + order * 24;
        config.y = 300 + order * 24;
        config.paddingLeft = 12;
        config.paddingTop = 12;
        config.paddingRight = 12;
        config.paddingBottom = 12;
        config.cellGap = 8;
        config.backgroundColor = "#FF000000";
        config.backgroundAlpha = 0xCC;
        config.cornerRadius = 28;
        return config;
    }

    /** One-time projection of every setting used by the original single popup. */
    @NonNull
    public static PopupOverlayConfig fromLegacy(@NonNull Preferences prefs) {
        PopupOverlayConfig config = create(PopupItemConfig.DEFAULT_OVERLAY_ID,
                "Основной всплывающий оверлей", 0);
        config.enabled = prefs.popupEnabled.get();
        config.width = clamp(prefs.popupWidth.get(), 100, 4000);
        config.height = clamp(prefs.popupHeight.get(), 100, 4000);
        config.rows = clamp(prefs.popupRows.get(), 1, 50);
        config.columns = clamp(prefs.popupColumns.get(), 1, 50);
        config.x = prefs.popupX.get();
        config.y = prefs.popupY.get();
        config.paddingLeft = clamp(prefs.popupPaddingLeft.get(), 0, 1000);
        config.paddingTop = clamp(prefs.popupPaddingTop.get(), 0, 1000);
        config.paddingRight = clamp(prefs.popupPaddingRight.get(), 0, 1000);
        config.paddingBottom = clamp(prefs.popupPaddingBottom.get(), 0, 1000);
        config.cellGap = clamp(prefs.popupCellGap.get(), 0, 500);
        config.backgroundColor = prefs.popupBackgroundColor.get();
        config.backgroundAlpha = clamp(prefs.popupBackgroundAlpha.get(), 0, 255);
        config.cornerRadius = clamp(prefs.popupCornerRadius.get(), 0, 1000);
        return config;
    }

    @NonNull
    public static PopupOverlayConfig fromJson(@NonNull JSONObject object, int fallbackOrder) {
        PopupOverlayConfig config = create(object.optString("id", "overlay_" + fallbackOrder),
                object.optString("name", "Оверлей " + (fallbackOrder + 1)), fallbackOrder);
        int schema = object.optInt("schemaVersion", object.optInt("schema", SCHEMA_VERSION));
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported popup overlay schema: " + schema);
        }
        config.enabled = object.optBoolean("enabled", config.enabled);
        config.defaultVisible = object.optBoolean("defaultVisible", config.defaultVisible);
        config.order = Math.max(0, object.optInt("order", config.order));
        config.width = clamp(object.optInt("width", config.width), 100, 4000);
        config.height = clamp(object.optInt("height", config.height), 100, 4000);
        config.rows = clamp(object.optInt("rows", config.rows), 1, 50);
        config.columns = clamp(object.optInt("columns", config.columns), 1, 50);
        config.x = clamp(object.optInt("x", config.x), -10000, 10000);
        config.y = clamp(object.optInt("y", config.y), -10000, 10000);
        config.paddingLeft = clamp(object.optInt("paddingLeft", config.paddingLeft), 0, 1000);
        config.paddingTop = clamp(object.optInt("paddingTop", config.paddingTop), 0, 1000);
        config.paddingRight = clamp(object.optInt("paddingRight", config.paddingRight), 0, 1000);
        config.paddingBottom = clamp(object.optInt("paddingBottom", config.paddingBottom), 0, 1000);
        config.cellGap = clamp(object.optInt("cellGap", config.cellGap), 0, 500);
        config.backgroundColor = boundedColor(object.optString("backgroundColor",
                config.backgroundColor));
        config.backgroundAlpha = clamp(object.optInt("backgroundAlpha",
                config.backgroundAlpha), 0, 255);
        config.cornerRadius = clamp(object.optInt("cornerRadius", config.cornerRadius), 0, 1000);
        return config;
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        String safeId = AutomationContract.requireSafeId(id);
        String safeName = normalizedName(name, safeId);
        String safeBackground = boundedColor(backgroundColor);
        return new JSONObject()
                .put("schemaVersion", SCHEMA_VERSION)
                .put("id", safeId)
                .put("name", safeName)
                .put("enabled", enabled)
                .put("defaultVisible", defaultVisible)
                .put("order", order)
                .put("width", width)
                .put("height", height)
                .put("rows", rows)
                .put("columns", columns)
                .put("x", x)
                .put("y", y)
                .put("paddingLeft", paddingLeft)
                .put("paddingTop", paddingTop)
                .put("paddingRight", paddingRight)
                .put("paddingBottom", paddingBottom)
                .put("cellGap", cellGap)
                .put("backgroundColor", safeBackground)
                .put("backgroundAlpha", backgroundAlpha)
                .put("cornerRadius", cornerRadius);
    }

    public void setName(@NonNull String value) {
        name = normalizedName(value, AutomationContract.requireSafeId(id));
    }

    @NonNull
    public PopupOverlayConfig copy(@NonNull String nextId, @NonNull String nextName, int nextOrder) {
        try {
            JSONObject json = toJson();
            json.put("id", nextId).put("name", nextName).put("order", nextOrder)
                    .put("x", x + 24).put("y", y + 24);
            return fromJson(json, nextOrder);
        } catch (JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String normalizedName(String raw, String fallback) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = fallback;
        if (value.length() > 128 || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid popup overlay name");
        }
        return value;
    }

    private static String boundedColor(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) return "#FF000000";
        if (value.length() > 64 || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid popup overlay color");
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
