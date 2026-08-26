/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Intent;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** The only class referenced by the one-method MapActivity smali hook. */
public final class NatroEntryPoint {
    private static final Map<Activity, FloatingWindowController> CONTROLLERS =
            new WeakHashMap<>();

    private NatroEntryPoint() {}

    public static void onActivityReady(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        FloatingWindowController controller = CONTROLLERS.get(activity);
        if (controller == null) {
            controller = new FloatingWindowController(activity);
            CONTROLLERS.put(activity, controller);
            controller.install();
        }
        controller.consumeIntent(activity.getIntent());
        NavigationBridgeClient.attachActivity(activity);
    }

    public static void onNewIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null) return;
        FloatingWindowController controller = CONTROLLERS.get(activity);
        if (controller == null) {
            controller = new FloatingWindowController(activity);
            CONTROLLERS.put(activity, controller);
            controller.install();
        }
        controller.consumeIntent(intent);
    }

    public static void onActivityDestroyed(Activity activity) {
        NavigationBridgeClient.detachActivity(activity);
        FloatingWindowController controller = CONTROLLERS.remove(activity);
        if (controller != null) controller.destroy();
    }

    static void applyConfiguration(String rawConfiguration) {
        for (FloatingWindowController controller
                : new ArrayList<>(CONTROLLERS.values())) {
            if (controller != null) controller.applyConfiguration(rawConfiguration);
        }
    }

    static void setWindowMode(int mode) {
        for (FloatingWindowController controller
                : new ArrayList<>(CONTROLLERS.values())) {
            if (controller != null) controller.setWindowMode(mode);
        }
    }
}
