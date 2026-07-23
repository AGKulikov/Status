/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.launcher.YandexWindowLauncher;

/** Opens a saved destination through the same ECARX window entry point as the HOME shortcut. */
public final class YandexRouteLauncher {
    private static final long ROUTE_AFTER_WINDOW_DELAY_MS = 650L;

    private YandexRouteLauncher() {}

    public static boolean launch(@NonNull Context context,
                                 @NonNull FavoriteRouteConfig source) {
        FavoriteRouteConfig route = source.copy();
        final Uri deepLink;
        final Uri alternateDeepLink;
        try {
            deepLink = deepLink(route.product, route.address, route.coordinates);
            alternateDeepLink = deepLink(opposite(route.product),
                    route.address, route.coordinates);
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(context, route.coordinates.trim().isEmpty()
                    ? "Укажите адрес или координаты маршрута"
                    : "Проверьте координаты маршрута", Toast.LENGTH_LONG).show();
            return false;
        }

        if (!route.floating) {
            return startDeepLink(context, route.product, deepLink, alternateDeepLink);
        }

        // Do not create a second approximation of the vendor floating window here. The HOME
        // "Navigator" shortcut already uses Yandex' ECARX-specific TransparentSplashActivity
        // (`ddnavwin`). Opening that exact entry point first also keeps route buttons compatible
        // with head units where ActivityOptions/windowingMode 5 opens on the wrong display.
        boolean opened = YandexWindowLauncher.launch(
                context, windowProduct(route.product), false);
        if (!opened) {
            return startDeepLink(context, route.product, deepLink, alternateDeepLink);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            // The Yandex task is now the same floating task created by the normal Navigator
            // button. ACTION_VIEW is delivered afterwards, so only its destination changes and
            // Android reuses that already-windowed task instead of constructing another window.
            if (!startDeepLink(context, route.product, deepLink, alternateDeepLink)) {
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

    private static boolean startDeepLink(@NonNull Context context,
                                         @NonNull FavoriteRouteConfig.Product product,
                                         @NonNull Uri deepLink,
                                         @NonNull Uri alternateDeepLink) {
        if (YandexWindowLauncher.launchDeepLink(
                context, windowProduct(product), deepLink)) return true;
        return YandexWindowLauncher.launchDeepLink(
                context, windowProduct(opposite(product)), alternateDeepLink);
    }

    @NonNull
    private static YandexWindowLauncher.Product windowProduct(
            @NonNull FavoriteRouteConfig.Product product) {
        return product == FavoriteRouteConfig.Product.MAPS
                ? YandexWindowLauncher.Product.MAPS
                : YandexWindowLauncher.Product.NAVIGATOR;
    }

    @NonNull
    private static FavoriteRouteConfig.Product opposite(
            @NonNull FavoriteRouteConfig.Product product) {
        return product == FavoriteRouteConfig.Product.MAPS
                ? FavoriteRouteConfig.Product.NAVIGATOR
                : FavoriteRouteConfig.Product.MAPS;
    }
}
