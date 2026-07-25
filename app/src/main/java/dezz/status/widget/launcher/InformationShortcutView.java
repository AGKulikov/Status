/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;
import dezz.status.widget.car.CarIntegrations;
import dezz.status.widget.integration.SourceBinding;
import dezz.status.widget.launcher.information.InformationPanelConfig;
import dezz.status.widget.launcher.information.InformationPanelConfigStore;
import dezz.status.widget.launcher.information.InformationPanelView;

/** One universal, read-only information shortcut reused by HOME, driver rail and Favorites. */
public final class InformationShortcutView extends FrameLayout {
    public static final String SYSTEM_PREFIX = "info:system:";
    public static final String VEHICLE_PREFIX = "info:vehicle:";
    public static final String CONNECTOR_TARGET = "info:connector";

    private final InformationPanelView content;

    public InformationShortcutView(@NonNull Context context,
                                   @NonNull Preferences preferences,
                                   @NonNull LauncherShortcutStore.Shortcut shortcut) {
        super(context);
        setClickable(false);
        setLongClickable(false);
        setFocusable(false);
        InformationPanelConfig config = config(shortcut);
        content = new InformationPanelView(context, CarIntegrations.get(context),
                new InformationPanelConfigStore(preferences));
        content.setConfig(config);
        content.setFixedCellBackgroundColor(shortcut.backgroundColor);
        content.setClickable(false);
        addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        content.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        content.stop();
        super.onDetachedFromWindow();
    }

    @NonNull
    public static String target(@NonNull InformationPanelConfig.Item item) {
        switch (item.sourceKind) {
            case SYSTEM:
                return SYSTEM_PREFIX + item.sourceId;
            case VEHICLE:
                return VEHICLE_PREFIX + item.sourceId;
            case CONNECTOR:
            default:
                return CONNECTOR_TARGET;
        }
    }

    @NonNull
    private static InformationPanelConfig config(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        InformationPanelConfig value = new InformationPanelConfig();
        value.columns = 1;
        value.rows = 1;
        value.gapPx = 0;
        value.contentPaddingPx = 0;
        value.cornerRadiusPx = 14;
        value.backgroundColor = "#000000";
        value.backgroundAlpha = 0;
        InformationPanelConfig.Item item = item(shortcut);
        item.labelOverride = shortcut.title;
        item.iconKey = shortcut.icon == null || shortcut.icon.trim().isEmpty()
                ? "auto" : shortcut.icon;
        item.iconColor = shortcut.iconColor;
        item.valueColor = shortcut.textColor;
        item.labelColor = shortcut.textColor;
        item.showIcon = !"none".equalsIgnoreCase(shortcut.icon);
        item.showLabel = shortcut.showTitle;
        item.labelTextSizeSp = shortcut.informationLabelTextSizeSp;
        item.valueTextSizeSp = shortcut.informationValueTextSizeSp;
        item.fontFamily = shortcut.informationFontFamily;
        item.textBold = shortcut.informationTextBold;
        item.textItalic = shortcut.informationTextItalic;
        item.horizontalAlignment = shortcut.informationHorizontalAlignment;
        item.verticalAlignment = shortcut.informationVerticalAlignment;
        item.paddingLeftPx = shortcut.informationPaddingLeftPx;
        item.paddingTopPx = shortcut.informationPaddingTopPx;
        item.paddingRightPx = shortcut.informationPaddingRightPx;
        item.paddingBottomPx = shortcut.informationPaddingBottomPx;
        value.add(item);
        value.normalize();
        return value;
    }

    @NonNull
    private static InformationPanelConfig.Item item(
            @NonNull LauncherShortcutStore.Shortcut shortcut) {
        String target = shortcut.target == null ? "" : shortcut.target;
        if (target.startsWith(SYSTEM_PREFIX)) {
            return InformationPanelConfig.Item.system(
                    target.substring(SYSTEM_PREFIX.length()), shortcut.title, "", "");
        }
        if (target.startsWith(VEHICLE_PREFIX)) {
            return InformationPanelConfig.Item.vehicle(
                    target.substring(VEHICLE_PREFIX.length()), shortcut.title, "", "");
        }
        SourceBinding binding = shortcut.stateBinding;
        if (binding != null && binding.isBound()) {
            return InformationPanelConfig.Item.connector(
                    binding, shortcut.title, binding.unitSuffix, "");
        }
        // Invalid imported connector items remain visibly unknown instead of becoming clickable.
        return InformationPanelConfig.Item.system(
                "system.unknown", shortcut.title, "", "");
    }

    @NonNull
    public static android.view.View divider(@NonNull Context context,
                                            @Nullable String rawColor,
                                            int thicknessPx) {
        android.view.View line = new android.view.View(context);
        GradientDrawable background = new GradientDrawable();
        int color;
        try {
            color = Color.parseColor(rawColor);
        } catch (RuntimeException ignored) {
            color = Color.argb(100, 255, 255, 255);
        }
        background.setColor(color);
        background.setCornerRadius(Math.max(1, thicknessPx));
        line.setBackground(background);
        line.setClickable(false);
        line.setFocusable(false);
        return line;
    }
}
