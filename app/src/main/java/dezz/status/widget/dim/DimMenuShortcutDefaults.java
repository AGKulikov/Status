/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.LauncherShortcutStore;
import dezz.status.widget.launcher.routes.FavoriteRouteConfig;
import dezz.status.widget.launcher.routes.FavoriteRoutesConfigStore;

/** Useful first-run menu: saved destinations followed by one media control. */
public final class DimMenuShortcutDefaults {
    private static final int MAX_DEFAULT_ROUTES = 5;

    private DimMenuShortcutDefaults() {}

    @NonNull
    public static List<LauncherShortcutStore.Shortcut> create(
            @NonNull Preferences preferences) {
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        for (FavoriteRouteConfig route :
                new FavoriteRoutesConfigStore(preferences).load()) {
            if (!route.enabled) continue;
            LauncherShortcutStore.Shortcut shortcut = builtin(
                    LauncherShortcutStore.favoriteRouteTarget(route.id),
                    route.title, route.icon);
            shortcut.iconColor = route.iconColor;
            shortcut.textColor = route.textColor;
            result.add(shortcut);
            if (result.size() >= MAX_DEFAULT_ROUTES) break;
        }
        if (result.isEmpty()) {
            result.add(builtin(LauncherShortcutStore.Builtin.NAVIGATOR_WINDOW.key,
                    "Навигатор", "navigation"));
        }
        result.add(builtin(LauncherShortcutStore.Builtin.MEDIA_PLAY_PAUSE.key,
                "Пауза / Играть", "media"));
        return result;
    }

    @NonNull
    private static LauncherShortcutStore.Shortcut builtin(
            @NonNull String target, @NonNull String title, @NonNull String icon) {
        LauncherShortcutStore.Shortcut value = new LauncherShortcutStore.Shortcut();
        value.kind = LauncherShortcutStore.Kind.BUILTIN;
        value.target = target;
        value.title = title;
        value.icon = icon;
        value.iconCustomized = false;
        value.backgroundColor = "#00000000";
        value.iconColor = "#FFFFFFFF";
        value.textColor = "#FFFFFFFF";
        value.showTitle = true;
        return value;
    }
}
