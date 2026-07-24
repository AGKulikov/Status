/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Broad application catalog for settings pickers.
 *
 * <p>Launcher-only queries hide many useful OEM activities. This catalog first indexes every
 * installed application (including system and disabled packages), then enriches it with
 * LAUNCHER/LEANBACK/HOME entry points and finally looks for a safe exported activity in packages
 * which have no conventional launcher icon. Non-launchable packages remain visible and clearly
 * labelled instead of silently disappearing.</p>
 */
public final class InstalledAppCatalog {
    public static final class App {
        @NonNull public final String label;
        @NonNull public final String packageName;
        @Nullable public final ComponentName component;
        public final boolean system;
        public final boolean enabled;

        App(@NonNull String label, @NonNull String packageName,
            @Nullable ComponentName component, boolean system, boolean enabled) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
            this.system = system;
            this.enabled = enabled;
        }

        public boolean launchable() {
            return component != null && enabled;
        }

        @NonNull
        public String secondaryLabel() {
            String type = system ? "Системное" : "Пользовательское";
            return launchable() ? type + " · " + packageName
                    : type + " · нет доступного экрана · " + packageName;
        }
    }

    private InstalledAppCatalog() {
    }

    @NonNull
    public static List<App> load(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        Map<String, ComponentName> entryPoints = queryEntryPoints(pm);
        List<ApplicationInfo> installed;
        try {
            installed = pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (RuntimeException error) {
            installed = Collections.emptyList();
        }
        List<App> result = new ArrayList<>(installed.size());
        for (ApplicationInfo info : installed) {
            String packageName = info.packageName;
            ComponentName component = entryPoints.get(packageName);
            if (component == null) {
                Intent launch = pm.getLaunchIntentForPackage(packageName);
                if (launch != null) component = launch.getComponent();
            }
            if (component == null) component = firstSafeExportedActivity(pm, packageName);
            CharSequence loadedLabel;
            try {
                loadedLabel = info.loadLabel(pm);
            } catch (RuntimeException ignored) {
                loadedLabel = packageName;
            }
            String label = TextUtils.isEmpty(loadedLabel) ? packageName : loadedLabel.toString();
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            result.add(new App(label, packageName, component, system, info.enabled));
        }
        result.sort(Comparator
                .comparing((App value) -> value.label.toLowerCase(Locale.getDefault()))
                .thenComparing(value -> value.packageName));
        return result;
    }

    @Nullable
    public static Drawable loadIcon(@NonNull Context context, @NonNull App app) {
        PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationIcon(app.packageName);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    @NonNull
    public static Intent launchIntent(@NonNull App app) {
        if (app.component == null) throw new IllegalArgumentException("No exported screen");
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    @NonNull
    private static Map<String, ComponentName> queryEntryPoints(@NonNull PackageManager pm) {
        Map<String, ComponentName> values = new LinkedHashMap<>();
        queryCategory(pm, Intent.CATEGORY_LAUNCHER, values);
        queryCategory(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, values);
        queryCategory(pm, Intent.CATEGORY_HOME, values);
        return values;
    }

    private static void queryCategory(@NonNull PackageManager pm, @NonNull String category,
                                      @NonNull Map<String, ComponentName> values) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> matches;
        try {
            matches = pm.queryIntentActivities(query,
                    PackageManager.MATCH_DISABLED_COMPONENTS);
        } catch (RuntimeException error) {
            return;
        }
        for (ResolveInfo match : matches) {
            ActivityInfo activity = match.activityInfo;
            if (activity == null || TextUtils.isEmpty(activity.packageName)
                    || TextUtils.isEmpty(activity.name)) continue;
            values.putIfAbsent(activity.packageName,
                    new ComponentName(activity.packageName, activity.name));
        }
    }

    @Nullable
    @SuppressWarnings("deprecation")
    private static ComponentName firstSafeExportedActivity(@NonNull PackageManager pm,
                                                           @NonNull String packageName) {
        try {
            PackageInfo info = pm.getPackageInfo(packageName,
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            if (info.activities == null) return null;
            for (ActivityInfo activity : info.activities) {
                if (!activity.exported || !activity.enabled || !TextUtils.isEmpty(activity.permission)
                        || TextUtils.isEmpty(activity.name)) continue;
                return new ComponentName(packageName, activity.name);
            }
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        return null;
    }
}
