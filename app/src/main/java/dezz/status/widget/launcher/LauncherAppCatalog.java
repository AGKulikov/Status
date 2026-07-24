/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.R;

/**
 * Canonical application list used by HOME and every runtime "all applications" surface.
 *
 * <p>Only actual {@link Intent#CATEGORY_LAUNCHER} activities are included. Settings pickers use
 * {@link InstalledAppCatalog} instead because they intentionally need system, disabled and
 * non-launcher packages as action targets.</p>
 */
public final class LauncherAppCatalog {
    public static final class App {
        @NonNull public final String label;
        @NonNull public final String packageName;
        @NonNull public final ComponentName component;

        App(@NonNull String label, @NonNull String packageName,
            @NonNull ComponentName component) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
        }
    }

    private LauncherAppCatalog() {
    }

    /** Mirrors the original LauncherActivity filtering, component de-duplication and ordering. */
    @NonNull
    public static List<App> load(@NonNull Context context) {
        PackageManager manager = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(query, 0);
        Set<String> components = new LinkedHashSet<>();
        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName,
                    info.activityInfo.name);
            if (!components.add(component.flattenToString())) continue;
            apps.add(new App(String.valueOf(info.loadLabel(manager)),
                    info.activityInfo.packageName, component));
        }
        apps.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return apps;
    }

    @NonNull
    public static Drawable loadIcon(@NonNull Context context, @NonNull App app) {
        Drawable icon = HighResolutionAppIconLoader.load(context, app.component);
        if (icon != null) return icon;
        Drawable fallback = ContextCompat.getDrawable(context, R.drawable.ic_launcher_apps);
        if (fallback == null) throw new IllegalStateException("Missing launcher icon fallback");
        return fallback;
    }

    @NonNull
    public static Intent launchIntent(@NonNull App app) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }
}
