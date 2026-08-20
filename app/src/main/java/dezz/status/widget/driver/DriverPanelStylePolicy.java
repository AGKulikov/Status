/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.automation.AutomationState;

/** Deterministic base/live/scenario precedence for driver-panel styling. */
public final class DriverPanelStylePolicy {
    public static final String PANEL_TARGET_ID = "driver_panel";

    public static final class PanelStyle {
        @NonNull public final String backgroundColor;
        @NonNull public final String borderColor;
        public final int borderWidthPx;

        PanelStyle(String backgroundColor, String borderColor, int borderWidthPx) {
            this.backgroundColor = backgroundColor;
            this.borderColor = borderColor;
            this.borderWidthPx = clampWidth(borderWidthPx);
        }
    }

    public static final class IconStyle {
        @NonNull public final String tint;
        @NonNull public final String backgroundColor;
        @NonNull public final String outlineColor;
        public final int outlineWidthPx;

        IconStyle(String tint, String backgroundColor, String outlineColor, int outlineWidthPx) {
            this.tint = tint;
            this.backgroundColor = backgroundColor;
            this.outlineColor = outlineColor;
            this.outlineWidthPx = clampWidth(outlineWidthPx);
        }
    }

    private DriverPanelStylePolicy() {}

    @NonNull
    public static PanelStyle panel(@NonNull String baseBackground,
                                   @NonNull String baseBorder,
                                   int baseWidth,
                                   @Nullable AutomationState automation) {
        return new PanelStyle(value(automation == null ? null : automation.backgroundColor,
                        baseBackground),
                value(automation == null ? null : automation.borderColor, baseBorder),
                automation != null && automation.borderWidthPx != null
                        ? automation.borderWidthPx : baseWidth);
    }

    /** Scenario style wins over a live active-state color; removing it restores live/base. */
    @NonNull
    public static IconStyle icon(@NonNull String liveTint,
                                 @NonNull String liveBackground,
                                 @Nullable AutomationState automation) {
        return new IconStyle(value(automation == null ? null : automation.iconTint, liveTint),
                value(automation == null ? null : automation.iconBackgroundColor,
                        liveBackground),
                value(automation == null ? null : automation.iconOutlineColor,
                        "#00000000"),
                automation != null && automation.iconOutlineWidthPx != null
                        ? automation.iconOutlineWidthPx : 0);
    }

    @NonNull private static String value(@Nullable String candidate, @NonNull String fallback) {
        return candidate == null || candidate.trim().isEmpty() ? fallback : candidate.trim();
    }

    private static int clampWidth(int value) { return Math.max(0, Math.min(64, value)); }
}
