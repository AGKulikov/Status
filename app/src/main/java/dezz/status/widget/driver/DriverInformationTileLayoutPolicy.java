/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.content.Context;
import android.text.TextPaint;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.BrickType;
import dezz.status.widget.Fonts;
import dezz.status.widget.StatusBrickSnapshot;
import dezz.status.widget.WidgetService;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.information.PhoneCellularDisplayPolicy;
import dezz.status.widget.launcher.information.StatusBarInformationCatalog;
import dezz.status.widget.phone.PhoneIndicatorVisualPolicy;

/** One geometry contract shared by the live driver rail and its settings preview. */
public final class DriverInformationTileLayoutPolicy {
    private static final float VALUE_BASELINE_EM = 4.2f;

    private DriverInformationTileLayoutPolicy() {
    }

    public static boolean isPhoneCellular(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        return StatusBarInformationCatalog.typeForTarget(shortcut.target)
                == BrickType.PHONE_CELLULAR;
    }

    public static boolean showsIcon(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        return isPhoneCellular(shortcut)
                ? shortcut.informationPhoneCellularShowSignal
                : !"none".equalsIgnoreCase(shortcut.icon);
    }

    public static boolean showsValue(@NonNull LauncherShortcutStore.Shortcut shortcut) {
        return isPhoneCellular(shortcut)
                ? shortcut.informationPhoneCellularShowOperator
                || shortcut.informationPhoneCellularShowNetworkType
                : shortcut.informationShowValue;
    }

    /**
     * Natural physical width before a complete row is proportionally fitted to the rail.
     * Text uses the exact configured typeface and size; there is deliberately no fixed 240 px
     * ceiling because that ceiling clipped operator/type text before row fitting even ran.
     */
    public static int naturalWidth(@NonNull Context context,
                                   @NonNull LauncherShortcutStore.Shortcut shortcut,
                                   float scale) {
        float safeScale = Math.max(.01f, scale);
        int width = Math.round((shortcut.informationPaddingLeftPx
                + shortcut.informationPaddingRightPx) * safeScale);
        if (showsIcon(shortcut)) {
            width += Math.round(shortcut.informationIconSizePx * safeScale);
            if (showsValue(shortcut) && isPhoneCellular(shortcut)) {
                width += PhoneIndicatorVisualPolicy.cellularIconTextGapPx(
                        Math.round(shortcut.informationIconSizePx * safeScale));
            }
        }

        int textWidth = 0;
        if (shortcut.showTitle) {
            textWidth = Math.max(textWidth, measuredTextWidth(context, shortcut,
                    shortcut.title, shortcut.informationLabelTextSizeSp, safeScale));
        }
        if (showsValue(shortcut)) {
            if (isPhoneCellular(shortcut)) {
                String actual = selectedCellularText(shortcut, liveSnapshot(shortcut));
                String fallback = cellularMeasurementFallback(shortcut);
                textWidth = Math.max(textWidth, measuredTextWidth(context, shortcut,
                        actual, shortcut.informationValueTextSizeSp, safeScale));
                textWidth = Math.max(textWidth, measuredTextWidth(context, shortcut,
                        fallback, shortcut.informationValueTextSizeSp, safeScale));
            } else {
                StatusBrickSnapshot status = liveSnapshot(shortcut);
                String actual = status == null ? "" : status.text;
                textWidth = Math.max(textWidth, measuredTextWidth(context, shortcut,
                        actual, shortcut.informationValueTextSizeSp, safeScale));
                textWidth = Math.max(textWidth, Math.round(shortcut.informationValueTextSizeSp
                        * context.getResources().getDisplayMetrics().scaledDensity
                        * VALUE_BASELINE_EM * safeScale));
            }
        }
        // Two physical pixels protect glyph overhang/rounding without creating a visible inset.
        return Math.max(1, width + textWidth + Math.max(1, Math.round(2f * safeScale)));
    }

    public static int naturalHeight(@NonNull Context context,
                                    @NonNull LauncherShortcutStore.Shortcut shortcut,
                                    float scale) {
        float safeScale = Math.max(.01f, scale);
        float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
        int text = Math.round((shortcut.informationValueTextSizeSp
                * (showsValue(shortcut) ? 1 : 0)
                + (shortcut.showTitle ? shortcut.informationLabelTextSizeSp : 0))
                * scaledDensity * 1.18f * safeScale);
        int padding = Math.round((shortcut.informationPaddingTopPx
                + shortcut.informationPaddingBottomPx) * safeScale);
        int icon = showsIcon(shortcut)
                ? Math.round(shortcut.informationIconSizePx * safeScale) + padding : padding;
        return Math.max(1, Math.max(icon, text + padding));
    }

    @NonNull
    public static String selectedCellularText(
            @NonNull LauncherShortcutStore.Shortcut shortcut,
            @Nullable StatusBrickSnapshot status) {
        PhoneCellularDisplayPolicy.Presentation selected =
                PhoneCellularDisplayPolicy.resolve(
                        status == null ? null : status.cellularSignalPercent,
                        status == null ? "" : status.cellularOperator,
                        status == null ? "" : status.cellularNetworkType,
                        shortcut.informationPhoneCellularShowSignal,
                        shortcut.informationPhoneCellularShowOperator,
                        shortcut.informationPhoneCellularShowNetworkType);
        return selected.text;
    }

    @NonNull
    private static String cellularMeasurementFallback(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        if (shortcut.informationPhoneCellularShowNetworkType
                && shortcut.informationPhoneCellularShowOperator) {
            return "LTE · оператор";
        }
        if (shortcut.informationPhoneCellularShowNetworkType) return "LTE";
        if (shortcut.informationPhoneCellularShowOperator) return "оператор";
        return "";
    }

    private static int measuredTextWidth(@NonNull Context context,
                                         @NonNull LauncherShortcutStore.Shortcut shortcut,
                                         @Nullable String value,
                                         int textSizeSp,
                                         float scale) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return 0;
        TextPaint paint = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Fonts.resolve(context, shortcut.informationFontFamily,
                shortcut.informationTextBold, shortcut.informationTextItalic));
        paint.setTextSize(textSizeSp
                * context.getResources().getDisplayMetrics().scaledDensity * scale);
        return (int) Math.ceil(paint.measureText(text));
    }

    @Nullable
    private static StatusBrickSnapshot liveSnapshot(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        BrickType type = StatusBarInformationCatalog.typeForTarget(shortcut.target);
        WidgetService service = WidgetService.getInstance();
        return type == null || service == null ? null : service.statusBrickSnapshot(type);
    }
}
