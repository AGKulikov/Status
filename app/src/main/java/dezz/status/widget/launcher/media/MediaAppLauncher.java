/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Opens Yandex Music across the package variants found on stock and modified head units. */
public final class MediaAppLauncher {
    private static final List<String> YANDEX_MUSIC_PACKAGES = Collections.unmodifiableList(
            Arrays.asList("ru.yandex.music", "com.yandex.music"));

    private MediaAppLauncher() {}

    @NonNull
    static List<String> yandexMusicPackages() {
        return YANDEX_MUSIC_PACKAGES;
    }

    public static boolean launchYandexMusic(@NonNull Context context) {
        PackageManager manager = context.getPackageManager();
        for (String packageName : YANDEX_MUSIC_PACKAGES) {
            Intent intent = launchIntent(manager, packageName);
            if (intent == null) continue;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                context.startActivity(intent);
                return true;
            } catch (RuntimeException ignored) {
                // A stale package-manager record must not prevent trying the second known build.
            }
        }
        return false;
    }

    @Nullable
    private static Intent launchIntent(@NonNull PackageManager manager,
                                       @NonNull String packageName) {
        Intent direct = manager.getLaunchIntentForPackage(packageName);
        if (direct != null) return direct;
        // A few Android Automotive/TV-flavoured Yandex Music builds expose only the Leanback
        // entry point, so getLaunchIntentForPackage() legitimately returns null even though the
        // application is installed. This API exists since 21 and is safe on our Android 9 unit.
        Intent leanback = manager.getLeanbackLaunchIntentForPackage(packageName);
        if (leanback != null) return leanback;

        Intent launcher = queryMainActivity(manager, packageName, Intent.CATEGORY_LAUNCHER);
        if (launcher != null) return launcher;
        return queryMainActivity(manager, packageName, Intent.CATEGORY_LEANBACK_LAUNCHER);
    }

    @Nullable
    private static Intent queryMainActivity(@NonNull PackageManager manager,
                                            @NonNull String packageName,
                                            @NonNull String category) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category).setPackage(packageName);
        try {
            List<ResolveInfo> candidates = manager.queryIntentActivities(query, 0);
            if (candidates == null) return null;
            for (ResolveInfo candidate : candidates) {
                if (candidate.activityInfo == null) continue;
                return new Intent(query).setComponent(new ComponentName(
                        candidate.activityInfo.packageName, candidate.activityInfo.name));
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
