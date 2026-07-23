/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * Same-process Activity registry used only by the merged mod.
 *
 * <p>Tracking through lifecycle callbacks lets HOME close the bundled floating MapActivity before
 * layout editing and lets the fullscreen X return without force-stopping the whole application.</p>
 */
public final class EmbeddedNavigatorRuntime
        implements Application.ActivityLifecycleCallbacks {
    @Nullable private static EmbeddedNavigatorRuntime instance;
    private WeakReference<Activity> navigator = new WeakReference<>(null);

    private EmbeddedNavigatorRuntime() {}

    public static synchronized void install(@NonNull Application application) {
        if (instance != null) return;
        EmbeddedNavigatorRuntime runtime = new EmbeddedNavigatorRuntime();
        instance = runtime;
        application.registerActivityLifecycleCallbacks(runtime);
    }

    public static boolean hasNavigatorActivity() {
        Activity activity = currentNavigator();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    public static void finishNavigatorActivity() {
        Activity activity = currentNavigator();
        if (activity != null && !activity.isFinishing()) activity.runOnUiThread(activity::finish);
    }

    @Nullable
    private static Activity currentNavigator() {
        EmbeddedNavigatorRuntime runtime = instance;
        return runtime == null ? null : runtime.navigator.get();
    }

    private void track(@NonNull Activity activity) {
        if (EmbeddedNavigatorContract.MAP_ACTIVITY.equals(activity.getClass().getName())) {
            navigator = new WeakReference<>(activity);
        }
    }

    private void untrack(@NonNull Activity activity) {
        if (navigator.get() == activity) navigator.clear();
    }

    @Override public void onActivityCreated(@NonNull Activity activity,
            @Nullable Bundle savedInstanceState) { track(activity); }
    @Override public void onActivityStarted(@NonNull Activity activity) { track(activity); }
    @Override public void onActivityResumed(@NonNull Activity activity) { track(activity); }
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivityStopped(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity,
            @NonNull Bundle outState) {}
    @Override public void onActivityDestroyed(@NonNull Activity activity) { untrack(activity); }
}
