/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Stable bridge between Status HOME and the bundled Monjaro Navigator mod.
 *
 * <p>The merged APK keeps the Java names of Navigator classes, but its Android package and UID
 * are those of Status Widget. That gives both halves access to the same {@code overlay_prefs}
 * file without reflection, root, accessibility gestures, or a second installed application.</p>
 */
public final class EmbeddedNavigatorContract {
    public static final String MAP_ACTIVITY =
            "ru.yandex.yandexmaps.app.MapActivity";
    public static final String SPLASH_ACTIVITY =
            "ru.yandex.yandexmaps.app.TransparentSplashActivity";
    public static final String ORIGINAL_PACKAGE = "ru.yandex.yandexnavi";
    public static final String EXTRA_WINDOW = "ddnavwin";
    public static final String EXTRA_FORCE_FULL_SCREEN = "ddnavforcewinfull";

    static final String OVERLAY_PREFERENCES = "overlay_prefs";
    static final String OVERLAY_X = "overlay_x";
    static final String OVERLAY_Y = "overlay_y";
    static final String OVERLAY_WIDTH = "overlay_width";
    static final String OVERLAY_HEIGHT = "overlay_height";

    private EmbeddedNavigatorContract() {}

    /** False in the ordinary Status APK and true only after the Navigator payload is merged. */
    public static boolean isBundled(@NonNull Context context) {
        try {
            context.getPackageManager().getActivityInfo(
                    new ComponentName(context.getPackageName(), MAP_ACTIVITY),
                    PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return false;
        }
    }

    /** Writes exactly the values read by setupoverlayInteresnoPravda() in the supplied mod. */
    public static void savePanelBounds(@NonNull Context context, @NonNull Rect bounds) {
        int width = Math.max(200, bounds.width());
        int height = Math.max(200, bounds.height());
        SharedPreferences preferences = context.getSharedPreferences(
                OVERLAY_PREFERENCES, Context.MODE_PRIVATE);
        preferences.edit()
                .putInt(OVERLAY_X, Math.max(0, bounds.left))
                .putInt(OVERLAY_Y, Math.max(0, bounds.top))
                .putInt(OVERLAY_WIDTH, width)
                .putInt(OVERLAY_HEIGHT, height)
                .apply();
    }

    @NonNull
    public static Intent windowIntent(@NonNull Context context, boolean fullScreen) {
        return windowIntent(context, fullScreen, null);
    }

    /**
     * Opens the bundled Navigator through its forwarding activity. Keeping the optional data URI
     * on the same explicit intent lets saved routes reuse the already docked Navigator task.
     */
    @NonNull
    public static Intent windowIntent(@NonNull Context context, boolean fullScreen,
                                      @Nullable Uri deepLink) {
        Intent intent = new Intent("navi_win/" + ORIGINAL_PACKAGE)
                .setComponent(new ComponentName(context.getPackageName(), SPLASH_ACTIVITY))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_WINDOW, true);
        if (deepLink != null) intent.setData(deepLink);
        if (fullScreen) intent.putExtra(EXTRA_FORCE_FULL_SCREEN, true);
        return intent;
    }
}
