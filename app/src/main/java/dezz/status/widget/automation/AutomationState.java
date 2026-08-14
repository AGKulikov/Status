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
    @Nullable public final String borderColor;
    @Nullable public final Integer borderWidthPx;
    @Nullable public final String iconTint;
    @Nullable public final String iconBackgroundColor;
    @Nullable public final String iconOutlineColor;
    @Nullable public final Integer iconOutlineWidthPx;
    public final boolean actionEnabled;
    public final boolean visible;
    /** True only after the owning connector confirmed this value in its current session. */
    public final boolean fresh;
    @Nullable public final String source;
    public final long updatedAt;
    public final long expiresAt;

    private AutomationState(boolean present, @Nullable String text, @Nullable String color,
                            @Nullable String icon, @Nullable String backgroundColor,
                            @Nullable String borderColor, @Nullable Integer borderWidthPx,
                            @Nullable String iconTint, @Nullable String iconBackgroundColor,
                            @Nullable String iconOutlineColor,
                            @Nullable Integer iconOutlineWidthPx,
                            boolean actionEnabled, boolean visible, boolean fresh,
                            @Nullable String source, long updatedAt, long expiresAt) {
        this.present = present;
        this.text = text;
        this.color = color;
        this.icon = icon;
        this.backgroundColor = backgroundColor;
        this.borderColor = borderColor;
        this.borderWidthPx = borderWidthPx;
        this.iconTint = iconTint;
        this.iconBackgroundColor = iconBackgroundColor;
        this.iconOutlineColor = iconOutlineColor;
        this.iconOutlineWidthPx = iconOutlineWidthPx;
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
                null, null, null, null, null, null,
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
        String border = optionalString(json, "border_color");
        Integer borderWidth = optionalInteger(json, "border_width");
        String iconTint = optionalString(json, "icon_tint");
        String iconBackground = optionalString(json, "icon_background_color");
        String iconOutline = optionalString(json, "icon_outline_color");
        Integer iconOutlineWidth = optionalInteger(json, "icon_outline_width");
        return new AutomationState(true, text, color, icon, background,
                border, borderWidth, iconTint, iconBackground, iconOutline, iconOutlineWidth,
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

    /** Session-local projection used before the persisted restart barrier finishes its worker. */
    @NonNull
    public AutomationState asStale() {
        if (!present || !fresh) return this;
        return new AutomationState(true, text, color, icon, backgroundColor,
                borderColor, borderWidthPx, iconTint, iconBackgroundColor,
                iconOutlineColor, iconOutlineWidthPx,
                actionEnabled, visible, false, source, updatedAt, expiresAt);
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
        String nextBorder = optionalOverride(overrides, "border_color", borderColor);
        Integer nextBorderWidth = optionalIntegerOverride(overrides, "border_width", borderWidthPx);
        String nextIconTint = optionalOverride(overrides, "icon_tint", iconTint);
        String nextIconBackground = optionalOverride(
                overrides, "icon_background_color", iconBackgroundColor);
        String nextIconOutline = optionalOverride(
                overrides, "icon_outline_color", iconOutlineColor);
        Integer nextIconOutlineWidth = optionalIntegerOverride(
                overrides, "icon_outline_width", iconOutlineWidthPx);
        boolean nextActionEnabled = overrides.has("action_enabled")
                ? overrides.optBoolean("action_enabled", actionEnabled) : actionEnabled;
        boolean nextVisible = overrides.has("visible")
                ? overrides.optBoolean("visible", visible) : visible;
        return new AutomationState(present, nextText, nextColor, nextIcon, nextBackground,
                nextBorder, nextBorderWidth, nextIconTint, nextIconBackground,
                nextIconOutline, nextIconOutlineWidth,
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
        if (borderColor != null) out.put("border_color", borderColor);
        if (borderWidthPx != null) out.put("border_width", borderWidthPx);
        if (iconTint != null) out.put("icon_tint", iconTint);
        if (iconBackgroundColor != null) out.put("icon_background_color", iconBackgroundColor);
        if (iconOutlineColor != null) out.put("icon_outline_color", iconOutlineColor);
        if (iconOutlineWidthPx != null) out.put("icon_outline_width", iconOutlineWidthPx);
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

    /**
     * Returns whether a configured presentation color is explicitly fully transparent.
     *
     * <p>The visual rule editor stores transparency either as the human-readable
     * {@code transparent} token or as an Android {@code #AARRGGBB}/{@code #ARGB} value. Invalid
     * and missing colors deliberately return {@code false}: they fall back to the brick's normal
     * color and must not make it disappear.</p>
     */
    public static boolean isFullyTransparentColor(@Nullable String value) {
        if (value == null) return false;
        String color = value.trim();
        if (color.isEmpty()) return false;
        if ("transparent".equalsIgnoreCase(color)) return true;
        if (color.charAt(0) != '#') return false;
        if (color.length() == 9) {
            return color.regionMatches(true, 1, "00", 0, 2);
        }
        return color.length() == 5 && color.charAt(1) == '0';
    }

    @Nullable
    private static String optionalOverride(@NonNull JSONObject object, String key,
                                           @Nullable String fallback) {
        if (!object.has(key)) return fallback;
        return object.isNull(key) ? null : object.optString(key, fallback);
    }

    @Nullable
    private static String optionalString(@NonNull JSONObject object, String key) {
        return object.has(key) && !object.isNull(key) ? object.optString(key, "") : null;
    }

    @Nullable
    private static Integer optionalInteger(@NonNull JSONObject object, String key) {
        return object.has(key) && !object.isNull(key) ? object.optInt(key) : null;
    }

    @Nullable
    private static Integer optionalIntegerOverride(@NonNull JSONObject object, String key,
                                                    @Nullable Integer fallback) {
        if (!object.has(key)) return fallback;
        return object.isNull(key) ? null : object.optInt(key);
    }
}
