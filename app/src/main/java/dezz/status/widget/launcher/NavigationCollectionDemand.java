/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;
import dezz.status.widget.launcher.vehicle.VehicleDerivedMetrics;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfig;
import dezz.status.widget.launcher.vehicle.VehicleInfoPanelConfigStore;

/**
 * Live, inexpensive answer to “does any configured HOME element consume navigation data?”.
 *
 * <p>The collectors are system services and can outlive HOME for days. Listening to the four
 * relevant preference keys lets them stop immediately when the user disables the last consumer,
 * without polling or coupling their lifecycle to {@code LauncherActivity}.</p>
 */
public final class NavigationCollectionDemand
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String KEY_NAVIGATION_VISIBLE = "launcherNavigationVisible";
    private static final String KEY_FAVORITES_VISIBLE = "launcherFavoriteRoutesVisible";
    private static final String KEY_VEHICLE_VISIBLE = "launcherVehicleInfoVisible";
    private static final String KEY_VEHICLE_CONFIG = "launcherVehicleInfoConfigJson";

    public interface Listener {
        void onNavigationCollectionDemandChanged(boolean needed);
    }

    @NonNull private final Preferences preferences;
    @NonNull private final SharedPreferences storage;
    @Nullable private Listener listener;
    private volatile boolean needed;

    public NavigationCollectionDemand(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        preferences = new Preferences(app);
        Context device = app.createDeviceProtectedStorageContext();
        storage = device.getSharedPreferences(app.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
        needed = compute();
    }

    public boolean isNeeded() {
        return needed;
    }

    public void start(@NonNull Listener listener) {
        this.listener = listener;
        storage.registerOnSharedPreferenceChangeListener(this);
        refresh();
    }

    public void stop() {
        storage.unregisterOnSharedPreferenceChangeListener(this);
        listener = null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (!isDemandKey(key)) return;
        refresh();
    }

    private void refresh() {
        boolean next = compute();
        boolean changed = next != needed;
        needed = next;
        Listener target = listener;
        if (changed && target != null) target.onNavigationCollectionDemandChanged(next);
    }

    private boolean compute() {
        boolean vehicleVisible = preferences.launcherVehicleInfoVisible.get();
        boolean speedWarningEnabled = false;
        // Avoid parsing the richer vehicle JSON in the overwhelmingly common paths. It is only a
        // navigation consumer when that panel is visible and its derived warning metric is on.
        if (vehicleVisible) {
            VehicleInfoPanelConfig config = new VehicleInfoPanelConfigStore(preferences).load();
            VehicleInfoPanelConfig.Metric metric = config.metric(
                    VehicleDerivedMetrics.SPEED_LIMIT_WARNING_ID);
            speedWarningEnabled = metric != null && metric.enabled;
        }
        return NavigationCollectionPolicy.shouldCollect(
                preferences.launcherNavigationVisible.get(),
                preferences.launcherFavoriteRoutesVisible.get(), vehicleVisible,
                speedWarningEnabled);
    }

    private static boolean isDemandKey(@Nullable String key) {
        return KEY_NAVIGATION_VISIBLE.equals(key) || KEY_FAVORITES_VISIBLE.equals(key)
                || KEY_VEHICLE_VISIBLE.equals(key) || KEY_VEHICLE_CONFIG.equals(key);
    }
}
