/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.automation;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.graphics.Color;

import org.json.JSONObject;
import org.json.JSONException;

import java.util.Locale;

/** Immutable effective state read by both overlay renderers. */
public final class AutomationState {
    public final boolean present;
    @Nullable public final String text;
    @Nullable public final String color;
    @Nullable public final String icon;
    @Nullable public final String backgroundColor;
    public final boolean actionEnabled;
    public final boolean visible;
    /** True only after the owning connector confirmed this value in its current session. */
    public final boolean fresh;
    @Nullable public final String source;
    public final long updatedAt;
    public final long expiresAt;

    private AutomationState(boolean present, @Nullable String text, @Nullable String color,
                            @Nullable String icon, @Nullable String backgroundColor,
                            boolean actionEnabled, boolean visible, boolean fresh,
                            @Nullable String source, long updatedAt, long expiresAt) {
        this.present = present;
        this.text = text;
        this.color = color;
        this.icon = icon;
        this.backgroundColor = backgroundColor;
        this.actionEnabled = actionEnabled;
        this.visible = visible;
        this.fresh = fresh;
        this.source = source;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
    }

    @NonNull
    public static AutomationState missing() {
        return new AutomationState(false, null, null, null, null,
                false, true, false, null, 0, 0);
    }

    @NonNull
    public static AutomationState fromJson(@Nullable JSONObject json) {
        if (json == null) return missing();
        String text = json.has("text") && !json.isNull("text") ? json.optString("text", "") : null;
        String color = json.has("color") && !json.isNull("color") ? json.optString("color", "") : null;
        String icon = json.has("icon") && !json.isNull("icon") ? json.optString("icon", "") : null;
        String background = json.has("background_color") && !json.isNull("background_color")
                ? json.optString("background_color", "") : null;
        return new AutomationState(true, text, color, icon, background,
                json.has("action_enabled") ? json.optBoolean("action_enabled", true)
                        : json.optBoolean("enabled", true),
                json.optBoolean("visible", true),
                json.optBoolean("fresh", true),
                json.has("source") && !json.isNull("source")
                        ? json.optString("source", null) : null,
                json.optLong("updated_at", 0L), json.optLong("expires_at", 0L));
    }

    public boolean isStale(long nowMillis, long staleAfterMillis) {
        if (!present) return true;
        if (!fresh) return true;
        if (expiresAt > 0 && nowMillis >= expiresAt) return true;
        return staleAfterMillis > 0 && updatedAt > 0 && nowMillis - updatedAt >= staleAfterMillis;
    }

    /**
     * Applies an in-memory local-scenario presentation layer without changing connector state.
     * Presence, freshness, timestamps and source remain owned by the connector, so hiding a
     * pending brick does not accidentally turn its cached value into a current one.
     */
    @NonNull
    public AutomationState withLocalOverrides(@Nullable JSONObject overrides) {
        if (overrides == null || overrides.length() == 0) return this;
        String nextText = optionalOverride(overrides, "text", text);
        String nextColor = optionalOverride(overrides, "color", color);
        String nextIcon = optionalOverride(overrides, "icon", icon);
        String nextBackground = optionalOverride(overrides, "background_color", backgroundColor);
        boolean nextActionEnabled = overrides.has("action_enabled")
                ? overrides.optBoolean("action_enabled", actionEnabled) : actionEnabled;
        boolean nextVisible = overrides.has("visible")
                ? overrides.optBoolean("visible", visible) : visible;
        return new AutomationState(present, nextText, nextColor, nextIcon, nextBackground,
                nextActionEnabled, nextVisible, fresh, source, updatedAt, expiresAt);
    }

    /** A bounded state snapshot included in an outgoing command for HA-side race checks. */
    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        if (text != null) out.put("text", text);
        if (color != null) out.put("color", color);
        if (icon != null) out.put("icon", icon);
        if (backgroundColor != null) out.put("background_color", backgroundColor);
        out.put("present", present).put("visible", visible)
                .put("action_enabled", actionEnabled)
                .put("fresh", fresh)
                .put("updated_at", updatedAt).put("expires_at", expiresAt);
        if (source != null) out.put("source", source);
        return out;
    }

    @ColorInt
    public static int parseColor(@Nullable String value, @ColorInt int fallback) {
        if (value == null) return fallback;
        String color = value.trim();
        if (color.isEmpty()) return fallback;
        switch (color.toLowerCase(Locale.ROOT)) {
            case "transparent": return Color.TRANSPARENT;
            case "white": return Color.WHITE;
            case "black": return Color.BLACK;
            case "red": return Color.RED;
            case "green": return Color.GREEN;
            case "blue": return Color.BLUE;
            case "yellow": return Color.YELLOW;
            case "orange": return 0xFFFF9800;
            default:
                try {
                    return Color.parseColor(color);
                } catch (IllegalArgumentException ignored) {
                    return fallback;
                }
        }
    }

    @Nullable
    private static String optionalOverride(@NonNull JSONObject object, String key,
                                           @Nullable String fallback) {
        if (!object.has(key)) return fallback;
        return object.isNull(key) ? null : object.optString(key, fallback);
    }
}
