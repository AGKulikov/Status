/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;

import dezz.status.widget.BrickType;
import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.LauncherShortcutStore;

/** Copies the matching status-bar appearance into a newly created information shortcut. */
public final class InformationShortcutDefaults {
    private InformationShortcutDefaults() {
    }

    public static void apply(@NonNull Preferences preferences,
                             @NonNull InformationPanelConfig.Item item,
                             @NonNull LauncherShortcutStore.Shortcut shortcut) {
        BrickType type = StatusBarInformationCatalog.type(item);
        if (type == null) return;
        shortcut.icon = StatusBarInformationCatalog.fallbackIcon(type);
        shortcut.informationUseStatusIconStyle = true;
        shortcut.backgroundColor = "#00000000";

        Preferences.IconBrickPrefs icon = preferences.iconBrickPrefs(type);
        if (icon != null) {
            shortcut.showTitle = false;
            shortcut.informationShowValue = false;
            shortcut.informationIconSizePx = icon.size.get();
            shortcut.informationIconAlpha = icon.contentAlpha.get();
            shortcut.informationIconOutlineAlpha = icon.outlineAlpha.get();
            shortcut.informationIconOutlineWidth = icon.outlineWidth.get();
            if (type == BrickType.PHONE_CELLULAR) {
                shortcut.informationPhoneCellularShowSignal = true;
                shortcut.informationPhoneCellularShowOperator = true;
                shortcut.informationPhoneCellularShowNetworkType =
                        preferences.phoneCellular.showNetworkType.get();
                shortcut.informationShowValue = true;
                shortcut.informationHorizontalAlignment = 1;
                shortcut.informationVerticalAlignment = 1;
            }
            return;
        }

        Preferences.TextBrickPrefs text = preferences.textBrickPrefs(type);
        if (text != null) {
            shortcut.showTitle = false;
            shortcut.informationShowValue = true;
            shortcut.informationValueTextSizeSp = text.fontSize.get();
            shortcut.informationFontFamily = text.fontFamily.get();
            shortcut.informationTextBold = text.fontBold.get();
            shortcut.informationTextItalic = text.fontItalic.get();
        }
        shortcut.icon = type == BrickType.MEDIA ? "media" : "none";
        shortcut.informationIconSizePx = Math.max(20,
                Math.min(48, shortcut.informationValueTextSizeSp + 4));
        shortcut.informationIconAlpha = text == null ? 255 : text.contentAlpha.get();
        shortcut.informationIconOutlineAlpha = text == null ? 0 : text.outlineAlpha.get();
        shortcut.informationIconOutlineWidth = text == null ? 0 : text.outlineWidth.get();
    }
}
