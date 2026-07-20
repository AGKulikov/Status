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
    public final long updatedAt;
    public final long expiresAt;

    private AutomationState(boolean present, @Nullable String text, @Nullable String color,
                            @Nullable String icon, @Nullable String backgroundColor,
                            boolean actionEnabled, boolean visible, long updatedAt, long expiresAt) {
        this.present = present;
        this.text = text;
        this.color = color;
        this.icon = icon;
        this.backgroundColor = backgroundColor;
        this.actionEnabled = actionEnabled;
        this.visible = visible;
        this.updatedAt = updatedAt;
        this.expiresAt = expiresAt;
    }

    @NonNull
    public static AutomationState missing() {
        return new AutomationState(false, null, null, null, null, true, true, 0, 0);
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
                json.optLong("updated_at", 0L), json.optLong("expires_at", 0L));
    }

    public boolean isStale(long nowMillis, long staleAfterMillis) {
        if (!present) return true;
        if (expiresAt > 0 && nowMillis >= expiresAt) return true;
        return staleAfterMillis > 0 && updatedAt > 0 && nowMillis - updatedAt >= staleAfterMillis;
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
                .put("updated_at", updatedAt).put("expires_at", expiresAt);
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
}
