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
 * <p>Runtime “All applications” surfaces include every enabled
 * {@link Intent#CATEGORY_LAUNCHER} activity and, for packages without one, a safe exported OEM
 * screen discovered by {@link InstalledAppCatalog}. This is important on ECARX builds where
 * stock applications such as Phone have no conventional launcher category.</p>
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

    /** User-installed launcher activities retained for callers that explicitly need that subset. */
    @NonNull
    public static List<App> load(@NonNull Context context) {
        return load(context, false);
    }

    /**
     * Complete launcher-activity catalog used by runtime application menus and pinned apps.
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
        if (includeSystem) {
            // ECARX exposes some user-facing system screens without CATEGORY_LAUNCHER.
            // InstalledAppCatalog accepts only enabled exported activities without a protected
            // permission, so these screens can be shown without inventing an OEM package name.
            for (InstalledAppCatalog.App installed : InstalledAppCatalog.load(context)) {
                if (!installed.launchable() || installed.component == null) continue;
                if (!components.add(installed.component.flattenToString())) continue;
                apps.add(new App(installed.label, installed.packageName,
                        installed.component, installed.system));
            }
        }
        apps.sort(Comparator.comparing(value -> value.label.toLowerCase(Locale.ROOT)));
        return apps;
    }

    /**
     * Returns the shared user-visible catalog. Filtering is component based so two launcher
     * activities from one package may be controlled independently; system applications are not
     * silently discarded.
     */
    @NonNull
    public static List<App> loadVisible(@NonNull Context context,
                                        @NonNull Preferences preferences) {
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        if (hidden.isEmpty()) return loadIncludingSystem(context);
        List<App> visible = new ArrayList<>();
        for (App app : loadIncludingSystem(context)) {
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
