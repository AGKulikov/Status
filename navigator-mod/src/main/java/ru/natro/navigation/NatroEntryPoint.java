/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** The only class referenced by the one-method MapActivity smali hook. */
public final class NatroEntryPoint {
    private static final String TAG = "NatroNavigatorHook";
    private static final Map<Activity, FloatingWindowController> CONTROLLERS =
            new WeakHashMap<>();

    private NatroEntryPoint() {}

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            FloatingWindowController controller = controllerFor(activity);
            controller.consumeIntent(activity.getIntent());
            NavigationBridgeClient.attachActivity(activity);
        } catch (Throwable failure) {
            reportFailure("onActivityResumed", failure);
        }
    }

    public static void onNewIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null || activity.isFinishing()) return;
        try {
            activity.setIntent(intent);
            FloatingWindowController controller = controllerFor(activity);
            // Keep the already attached MapActivity and its OEM window token. The previous restart
            // path called finish() before the replacement obtained a token; device logs show the
            // authenticated Navigator bridge disappearing less than a second later. The controller
            // is specifically designed to resize the attached application window in place.
            controller.consumeIntent(intent);
        } catch (Throwable failure) {
            reportFailure("onNewIntent", failure);
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        try {
            NavigationBridgeClient.detachActivity(activity);
            FloatingWindowController controller = CONTROLLERS.remove(activity);
            if (controller != null) controller.destroy();
        } catch (Throwable failure) {
            reportFailure("onActivityDestroyed", failure);
        }
    }

    static void applyConfiguration(String rawConfiguration) {
        for (FloatingWindowController controller
                : new ArrayList<>(CONTROLLERS.values())) {
            if (controller == null) continue;
            try {
                controller.applyConfiguration(rawConfiguration);
            } catch (Throwable failure) {
                reportFailure("applyConfiguration", failure);
            }
        }
    }

    static void setWindowMode(int mode) {
        for (FloatingWindowController controller
                : new ArrayList<>(CONTROLLERS.values())) {
            if (controller == null) continue;
            try {
                controller.setWindowMode(mode);
            } catch (Throwable failure) {
                reportFailure("setWindowMode", failure);
            }
        }
    }

    private static FloatingWindowController controllerFor(Activity activity) {
        FloatingWindowController controller = CONTROLLERS.get(activity);
        if (controller != null) return controller;
        controller = new FloatingWindowController(activity);
        CONTROLLERS.put(activity, controller);
        controller.install();
        return controller;
    }

    private static void reportFailure(String stage, Throwable failure) {
        String message = "Navigator hook " + stage + " failed: "
                + failure.getClass().getSimpleName()
                + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        Log.e(TAG, message, failure);
        try {
            NavigationBridgeClient.reportDiagnostic(message);
        } catch (Throwable ignored) {
            // Diagnostics are strictly secondary to preserving the host MapActivity.
        }
    }
}
