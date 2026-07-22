/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Opens a saved destination in Yandex Maps/Navigator, including ECARX freeform display 2. */
public final class YandexRouteLauncher {
    private static final int ECARX_NAVIGATION_DISPLAY = 2;
    private static final long ROUTE_AFTER_WINDOW_DELAY_MS = 420L;

    private YandexRouteLauncher() {}

    public static boolean launch(@NonNull Context context,
                                 @NonNull FavoriteRouteConfig source) {
        FavoriteRouteConfig route = source.copy();
        final Uri deepLink;
        try {
            deepLink = deepLink(route.product, route.address, route.coordinates);
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(context, route.coordinates.trim().isEmpty()
                    ? "Укажите адрес или координаты маршрута"
                    : "Проверьте координаты маршрута", Toast.LENGTH_LONG).show();
            return false;
        }

        if (!route.floating) return startDeepLink(context, route.product, deepLink, null);

        Bundle freeform = freeformOptions();
        boolean opened = startFreeformWindow(context, route.product, freeform);
        if (!opened) {
            // Some Yandex builds accept ActivityOptions directly on ACTION_VIEW even when their
            // explicit MAIN entry point changed in an update.
            return startDeepLink(context, route.product, deepLink, freeform)
                    || startDeepLink(context, route.product, deepLink, null);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!startDeepLink(context, route.product, deepLink, freeform)
                    && !startDeepLink(context, route.product, deepLink, null)) {
                Toast.makeText(context, "Не удалось передать маршрут в Яндекс",
                        Toast.LENGTH_LONG).show();
            }
        }, ROUTE_AFTER_WINDOW_DELAY_MS);
        return true;
    }

    @NonNull
    static Uri deepLink(@NonNull FavoriteRouteConfig.Product product,
                        @Nullable String address, @Nullable String coordinates) {
        String coordinateValue = coordinates == null ? "" : coordinates.trim();
        String scheme = product == FavoriteRouteConfig.Product.MAPS
                ? "yandexmaps" : "yandexnavi";
        if (!coordinateValue.isEmpty()) {
            String route = RouteDestinationParser.coordinateRouteText(coordinateValue);
            return Uri.parse(scheme + "://maps.yandex.ru/?rtext="
                    + Uri.encode(route, "~,-.") + "&rtt=auto");
        }
        String addressValue = address == null ? "" : address.trim();
        if (addressValue.isEmpty()) throw new IllegalArgumentException("Destination is empty");
        // This is the address path used by mNavi. `едем` asks compatible Yandex car builds to
        // start navigation immediately instead of leaving a confirmation card open.
        return Uri.parse(scheme + "://ask_alice?text="
                + Uri.encode("Маршрут до " + addressValue + " едем"));
    }

    private static boolean startFreeformWindow(@NonNull Context context,
                                               @NonNull FavoriteRouteConfig.Product product,
                                               @Nullable Bundle options) {
        String packageName = packageName(product);
        String className = product == FavoriteRouteConfig.Product.MAPS
                ? "ru.yandex.yandexmaps.SplashScreen"
                : "ru.yandex.yandexnavi.core.NavigatorActivity";
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(new ComponentName(packageName, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return start(context, intent, options);
    }

    private static boolean startDeepLink(@NonNull Context context,
                                         @NonNull FavoriteRouteConfig.Product product,
                                         @NonNull Uri deepLink, @Nullable Bundle options) {
        Intent intent = new Intent(Intent.ACTION_VIEW, deepLink)
                .setPackage(packageName(product))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (start(context, intent, options)) return true;
        // Keep the proprietary URI as an implicit fallback for regional/Yandex builds whose
        // package differs but still register the same scheme.
        intent.setPackage(null);
        return start(context, intent, options);
    }

    private static boolean start(@NonNull Context context, @NonNull Intent intent,
                                 @Nullable Bundle options) {
        try {
            if (options == null) context.startActivity(intent);
            else context.startActivity(intent, options);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @NonNull private static String packageName(@NonNull FavoriteRouteConfig.Product product) {
        return product == FavoriteRouteConfig.Product.MAPS
                ? "ru.yandex.yandexmaps" : "ru.yandex.yandexnavi";
    }

    @Nullable
    private static Bundle freeformOptions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;
        try {
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(ECARX_NAVIGATION_DISPLAY);
            Bundle bundle = options.toBundle();
            bundle.putInt("android.activity.SplitScreenShownPosition", 0);
            bundle.putInt("android.activity.windowingMode", 5);
            return bundle;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
