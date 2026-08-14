/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.StatusBarSurfaceContext;

/** Starts the special floating-window entry points used by the head-unit Yandex builds. */
public final class YandexWindowLauncher {
    public enum Product { MAPS, NAVIGATOR }
    public static final String EXTRA_STAGED_PRODUCT =
            "dezz.status.widget.extra.YANDEX_STAGED_PRODUCT";
    public static final String EXTRA_STAGED_FULLSCREEN =
            "dezz.status.widget.extra.YANDEX_STAGED_FULLSCREEN";

    private static final String MAPS_PACKAGE = "ru.yandex.yandexmaps";
    private static final String NAVIGATOR_PACKAGE = "ru.yandex.yandexnavi";
    private static final String YANGO_PACKAGE = "com.yandex.yango";

    /** Known entry points differ between the standalone and unified Yandex car builds. */
    private static final Target[] MAPS_WINDOW_TARGETS = {
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.TransparentSplashActivity"),
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.app.TransparentSplashActivity")
    };
    private static final Target[] NAVIGATOR_WINDOW_TARGETS = {
            // Standalone Navigator used on Monjaro. This is the exact mSaver HOME entry point.
            new Target(NAVIGATOR_PACKAGE,
                    "ru.yandex.yandexmaps.app.TransparentSplashActivity"),
            new Target(NAVIGATOR_PACKAGE,
                    "ru.yandex.yandexnavi.TransparentSplashActivity"),
            new Target(NAVIGATOR_PACKAGE,
                    "ru.yandex.yandexnavi.app.TransparentSplashActivity"),
            // Recent releases fold navigation into the Maps package.
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.TransparentSplashActivity"),
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.app.TransparentSplashActivity")
    };
    private static final Target[] MAPS_FULL_TARGETS = {
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.app.MapActivity"),
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.MapActivity")
    };
    private static final Target[] NAVIGATOR_FULL_TARGETS = {
            new Target(NAVIGATOR_PACKAGE, "ru.yandex.yandexnavi.core.NavigatorActivity"),
            new Target(NAVIGATOR_PACKAGE, "ru.yandex.yandexmaps.core.NavigatorActivity"),
            new Target(MAPS_PACKAGE, "ru.yandex.yandexmaps.app.MapActivity")
    };

    private YandexWindowLauncher() {}

    /**
     * Makes Status Widget HOME the task directly under a floating Yandex window.
     *
     * <p>Driver-panel and automation launches run from a Service context. Staging them through
     * the launcher prevents the previously foreground application or stock launcher from being
     * exposed when the ECARX window closes.</p>
     */
    public static boolean launchOverLauncher(@NonNull Context context,
                                             @NonNull Product product,
                                             boolean forceFullScreen) {
        if (forceFullScreen || context instanceof dezz.status.widget.LauncherActivity) {
            return launch(context, product, forceFullScreen);
        }
        Intent stage = new Intent(context, dezz.status.widget.LauncherActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_STAGED_PRODUCT, product.name())
                .putExtra(EXTRA_STAGED_FULLSCREEN, false);
        return start(context, stage);
    }

    public static boolean launch(@NonNull Context context, @NonNull Product product,
                                 boolean forceFullScreen) {
        Target[] targets;
        if (product == Product.MAPS) {
            targets = forceFullScreen ? MAPS_FULL_TARGETS : MAPS_WINDOW_TARGETS;
        } else {
            targets = forceFullScreen ? NAVIGATOR_FULL_TARGETS : NAVIGATOR_WINDOW_TARGETS;
        }
        for (Target target : targets) {
            // The ECARX Yandex builds used by mSaver expose navi_win/<package>. Keep that proven
            // action first, then retry the same exported component without an action because a
            // few unified Maps versions reject the vendor action while accepting the component.
            if (startYandexSurface(context,
                    windowIntent(target, forceFullScreen, true), !forceFullScreen)) return true;
            if (startYandexSurface(context,
                    windowIntent(target, forceFullScreen, false), !forceFullScreen)) return true;
        }

        // Package launch intents are absent in a few head-unit builds, but trying every known
        // package still covers normal Play/RuStore installations and the unified Maps app.
        for (String packageName : packageCandidates(product)) {
            Intent fallback = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (fallback == null) continue;
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra("ddnavwin", !forceFullScreen);
            if (forceFullScreen) fallback.putExtra("ddnavforcewinfull", true);
            if (startYandexSurface(context, fallback, !forceFullScreen)) return true;
        }

        Uri home = Uri.parse(product == Product.MAPS ? "yandexmaps://" : "yandexnavi://");
        return launchDeepLink(context, product, home, !forceFullScreen);
    }

    /** Delivers a destination with an explicit full-screen/floating surface contract. */
    public static boolean launchDeepLink(@NonNull Context context,
                                         @NonNull Product product,
                                         @NonNull Uri deepLink,
                                         boolean windowed) {
        for (String packageName : packageCandidates(product)) {
            Intent intent = deepLinkIntent(deepLink, packageName, windowed);
            if (startYandexSurface(context, intent, windowed)) return true;
        }
        // Regional builds can register the proprietary scheme under another package name.
        return startYandexSurface(context,
                deepLinkIntent(deepLink, null, windowed), windowed);
    }

    @NonNull
    private static Intent deepLinkIntent(@NonNull Uri deepLink,
                                         @Nullable String packageName,
                                         boolean windowed) {
        Intent intent = new Intent(Intent.ACTION_VIEW, deepLink)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("ddnavwin", windowed);
        if (!windowed) intent.putExtra("ddnavforcewinfull", true);
        if (packageName != null) intent.setPackage(packageName);
        return intent;
    }

    @NonNull
    private static String[] packageCandidates(@NonNull Product product) {
        return product == Product.MAPS
                ? new String[]{MAPS_PACKAGE, NAVIGATOR_PACKAGE, YANGO_PACKAGE}
                : new String[]{NAVIGATOR_PACKAGE, MAPS_PACKAGE, YANGO_PACKAGE};
    }

    private static boolean start(@NonNull Context context, @NonNull Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean startYandexSurface(@NonNull Context context,
                                              @NonNull Intent intent,
                                              boolean windowed) {
        if (!start(context, intent)) return false;
        // startActivity only confirms handoff; the accessibility event later confirms the exact
        // class. Publishing now prevents one visible frame with stale per-element rules.
        StatusBarSurfaceContext.setNavigatorWindowForeground(windowed);
        if (windowed) StatusBarSurfaceContext.markNavigatorWindowOptimistic();
        return true;
    }

    @NonNull
    private static Intent windowIntent(@NonNull Target target, boolean forceFullScreen,
                                       boolean vendorAction) {
        Intent intent = vendorAction
                ? new Intent("navi_win/" + target.packageName) : new Intent();
        intent.setComponent(new ComponentName(target.packageName, target.className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("ddnavwin", true);
        if (forceFullScreen) intent.putExtra("ddnavforcewinfull", true);
        return intent;
    }

    private static final class Target {
        @NonNull final String packageName;
        @NonNull final String className;

        Target(@NonNull String packageName, @NonNull String className) {
            this.packageName = packageName;
            this.className = className;
        }
    }
}
