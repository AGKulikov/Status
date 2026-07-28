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
import android.telecom.TelecomManager;

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
        List<App> catalog = loadIncludingSystem(context);
        ensureDefaultSystemVisibility(context, preferences, catalog);
        Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
        List<App> visible = new ArrayList<>();
        for (App app : catalog) {
            if (!hidden.contains(app.component.flattenToString())) visible.add(app);
        }
        return visible;
    }

    /**
     * Applies the requested first-run catalog policy without hard-coding one ECARX package name.
     * Every system app remains available in settings and can be enabled explicitly.
     */
    public static void ensureDefaultSystemVisibility(
            @NonNull Context context,
            @NonNull Preferences preferences,
            @NonNull List<App> catalog) {
        if (preferences.launcherSystemAppsDefaultApplied.get()) return;
        synchronized (LauncherAppCatalog.class) {
            if (preferences.launcherSystemAppsDefaultApplied.get()) return;
            Set<String> hidden = preferences.launcherAllAppsHiddenComponents.get();
            String defaultDialer = "";
            try {
                TelecomManager telecom =
                        (TelecomManager) context.getSystemService(Context.TELECOM_SERVICE);
                if (telecom != null && telecom.getDefaultDialerPackage() != null) {
                    defaultDialer = telecom.getDefaultDialerPackage();
                }
            } catch (RuntimeException ignored) {
            }
            if (defaultDialer.isEmpty()) {
                defaultDialer = fallbackPhonePackage(catalog);
            }
            for (App app : catalog) {
                if (app.systemApp && !isUserFacingPhone(app, defaultDialer)) {
                    hidden.add(app.component.flattenToString());
                }
            }
            preferences.launcherAllAppsHiddenComponents.set(hidden);
            preferences.launcherSystemAppsDefaultApplied.set(true);
        }
    }

    @NonNull
    private static String fallbackPhonePackage(@NonNull List<App> catalog) {
        App best = null;
        int bestScore = 0;
        for (App app : catalog) {
            if (!app.systemApp) continue;
            String packageName = app.packageName.toLowerCase(Locale.ROOT);
            String component = app.component.getClassName().toLowerCase(Locale.ROOT);
            String label = app.label.trim().toLowerCase(Locale.ROOT);
            int score = label.equals("phone") || label.equals("телефон") ? 100
                    : packageName.contains("dialer") ? 80
                    : component.contains("dialer") ? 70
                    : component.contains("phoneactivity") ? 60 : 0;
            if (score > bestScore) {
                best = app;
                bestScore = score;
            }
        }
        return best == null ? "" : best.packageName;
    }

    private static boolean isUserFacingPhone(@NonNull App app,
                                             @NonNull String defaultDialer) {
        return !defaultDialer.isEmpty() && defaultDialer.equals(app.packageName);
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
