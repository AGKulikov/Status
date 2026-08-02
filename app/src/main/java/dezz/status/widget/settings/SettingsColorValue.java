/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.settings;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * Lossless value model used by visual settings color controls.
 *
 * <p>The application historically stores colors as {@code #RRGGBB}, {@code #AARRGGBB},
 * {@code transparent}, {@code none}, or a nullable "inherit" value. Keeping those states in
 * one model prevents a visual editor from silently turning a semantic value into an ordinary
 * transparent color.</p>
 */
public final class SettingsColorValue {
    public enum Kind { COLOR, TRANSPARENT, NONE, INHERIT }

    @NonNull private final Kind kind;
    @ColorInt private final int argb;
    private final boolean explicitAlpha;

    private SettingsColorValue(@NonNull Kind kind, @ColorInt int argb, boolean explicitAlpha) {
        this.kind = kind;
        this.argb = argb;
        this.explicitAlpha = explicitAlpha;
    }

    @NonNull
    public static SettingsColorValue color(@ColorInt int argb, boolean explicitAlpha) {
        return new SettingsColorValue(Kind.COLOR, argb,
                explicitAlpha || ((argb >>> 24) & 0xFF) != 0xFF);
    }

    @NonNull
    public static SettingsColorValue transparent() {
        return new SettingsColorValue(Kind.TRANSPARENT, 0x00000000, true);
    }

    @NonNull
    public static SettingsColorValue none() {
        return new SettingsColorValue(Kind.NONE, 0x00000000, false);
    }

    @NonNull
    public static SettingsColorValue inherit() {
        return new SettingsColorValue(Kind.INHERIT, 0x00000000, false);
    }

    /**
     * Parses all persisted color forms. Invalid values return {@code fallback}, never a guessed
     * color, so a settings screen cannot damage an existing configuration simply by opening it.
     */
    @NonNull
    public static SettingsColorValue parseOr(@Nullable String raw,
                                             @NonNull SettingsColorValue fallback) {
        SettingsColorValue parsed = tryParse(raw);
        return parsed == null ? fallback : parsed;
    }

    /**
     * @return parsed value, or {@code null} only when a non-null invalid string was supplied.
     * A Java {@code null} is the valid semantic INHERIT value.
     */
    @Nullable
    public static SettingsColorValue tryParse(@Nullable String raw) {
        if (raw == null) return inherit();
        String value = raw.trim();
        if (value.equalsIgnoreCase("transparent")) return transparent();
        if (value.equalsIgnoreCase("none")) return none();
        if (!value.matches("(?i)#[0-9a-f]{6}([0-9a-f]{2})?")) return null;
        try {
            if (value.length() == 7) {
                int rgb = (int) Long.parseLong(value.substring(1), 16);
                return color(0xFF000000 | rgb, false);
            }
            int argb = (int) Long.parseLong(value.substring(1), 16);
            return color(argb, true);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @NonNull
    public Kind kind() {
        return kind;
    }

    @ColorInt
    public int argb() {
        return argb;
    }

    public boolean hasExplicitAlpha() {
        return explicitAlpha;
    }

    @Nullable
    public String serialize() {
        switch (kind) {
            case INHERIT:
                return null;
            case NONE:
                return "none";
            case TRANSPARENT:
                return "transparent";
            case COLOR:
            default:
                return serializeColor(argb, explicitAlpha);
        }
    }

    @NonNull
    public static String serializeColor(@ColorInt int argb, boolean explicitAlpha) {
        int alpha = (argb >>> 24) & 0xFF;
        if (explicitAlpha || alpha != 0xFF) {
            return String.format(Locale.ROOT, "#%08X", argb);
        }
        return String.format(Locale.ROOT, "#%06X", argb & 0x00FFFFFF);
    }

    @NonNull
    public static String displayValue(@Nullable String raw) {
        SettingsColorValue value = tryParse(raw);
        if (value == null) return raw == null ? "Наследовать" : raw;
        switch (value.kind) {
            case INHERIT: return "Не менять";
            case NONE: return "Без окрашивания";
            case TRANSPARENT: return "Прозрачный";
            case COLOR:
            default:
                String serialized = value.serialize();
                return serialized == null ? "Не менять" : serialized.toUpperCase(Locale.ROOT);
        }
    }
}
