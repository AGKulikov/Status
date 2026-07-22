/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/** Starts the special floating-window entry points used by the head-unit Yandex builds. */
public final class YandexWindowLauncher {
    public enum Product { MAPS, NAVIGATOR }

    private YandexWindowLauncher() {}

    public static boolean launch(@NonNull Context context, @NonNull Product product,
                                 boolean forceFullScreen) {
        String packageName = product == Product.MAPS
                ? "ru.yandex.yandexmaps" : "ru.yandex.yandexnavi";
        String windowClass = product == Product.MAPS
                ? "ru.yandex.yandexmaps.TransparentSplashActivity"
                : "ru.yandex.yandexmaps.app.TransparentSplashActivity";
        String fullClass = product == Product.MAPS
                ? "ru.yandex.yandexmaps.app.MapActivity"
                : "ru.yandex.yandexmaps.core.NavigatorActivity";

        Intent intent = new Intent("navi_win/" + packageName)
                .setComponent(new ComponentName(packageName,
                        forceFullScreen ? fullClass : windowClass))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("ddnavwin", true);
        if (forceFullScreen) intent.putExtra("ddnavforcewinfull", true);
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException missingVendorEntryPoint) {
            Intent fallback = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (fallback == null) return false;
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                context.startActivity(fallback);
                return true;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
    }
}
