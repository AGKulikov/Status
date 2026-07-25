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
import android.content.pm.ApplicationInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import dezz.status.widget.Preferences;
import dezz.status.widget.R;

/**
 * Canonical application list used by HOME and every runtime "all applications" surface.
 *
 * <p>Only user-installed {@link Intent#CATEGORY_LAUNCHER} activities are included. Settings
 * pickers use {@link InstalledAppCatalog} instead because they intentionally need system,
 * disabled and non-launcher packages as action targets.</p>
 */
public final class LauncherAppCatalog {
    public static final class App {
        @NonNull public final String label;
        @NonNull public final String packageName;
        @NonNull public final ComponentName component;
        public final boolean systemApp;

        App(@NonNull String label, @NonNull String packageName,
            @NonNull ComponentName component, boolean systemApp) {
            this.label = label;
            this.packageName = packageName;
            this.component = component;
            this.systemApp = systemApp;
        }
    }

    private LauncherAppCatalog() {
    }

    /** User-installed launcher activities shown by runtime "All applications" surfaces. */
    @NonNull
    public static List<App> load(@NonNull Context context) {
        return load(context, false);
    }

    /**
     * Complete launcher-activity catalog used only to resolve explicitly pinned applications.
     * System applications stay out of every runtime "All applications" list, but a target that
     * the user deliberately selected in settings must not disappear from HOME or the driver rail.
     */
    @NonNull
    public static List<App> loadIncludingSystem(@NonNull Context context) {
        return load(context, true);
    }

    @NonNull
    private static List<App> load(@NonNull Context context, boolean includeSystem) {
        PackageManager manager = context.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = manager.queryIntentActivities(query, 0);
        Set<String> components = new LinkedHashSet<>();
        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info.activityInfo == null) continue;
            ApplicationInfo application = info.activityInfo.applicationInfo;
            boolean systemApp = application != null
                    && (application.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            if (systemApp && !includeSystem) continue;
            ComponentName component = new ComponentName(info.activityInfo.packageName,
                    info.activityInfo.name);
            if (!components.add(component.flattenToString())) continue;
            apps.add(new App(String.valueOf(info.loadLabel(manager)),
                    info.activityInfo.packageName, component, systemApp));
        }
        apps.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return apps;
    }

    /**
     * Returns the shared user-visible catalog. Filtering is component based so two launcher
     * activities from one package may be controlled independently.
     */
    @NonNull
    public static List<App> loadVisible(@NonNull Context context,
                                        @NonNull Preferences preferences) {
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        if (hidden.isEmpty()) return load(context);
        List<App> visible = new ArrayList<>();
        for (App app : load(context)) {
            if (!hidden.contains(app.component.flattenToString())) visible.add(app);
        }
        return visible;
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
