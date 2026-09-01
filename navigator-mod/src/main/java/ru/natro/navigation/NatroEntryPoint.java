/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.util.Log;
import android.view.MotionEvent;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** The only class referenced by the one-method MapActivity smali hook. */
public final class NatroEntryPoint {
    private static final String TAG = "NatroNavigatorHook";
    private static final String ACTION_FLOATING = "navi_win/ru.yandex.yandexnavi";
    private static final String EXTRA_WINDOWED = "ddnavwin";
    private static final String EXTRA_FORCE_FULLSCREEN = "ddnavforcewinfull";
    private static final Map<Activity, FloatingWindowController> CONTROLLERS =
            new WeakHashMap<>();
    private static final Map<Activity, Boolean> MOVABLE_MAP_ACTIVITIES =
            new WeakHashMap<>();

    private NatroEntryPoint() {}

    /** Called at the beginning of MapActivity.onStart, before Navigator resumes its UI. */
    public static void onActivityStarting(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            NavigationBridgeClient.onActivityStarting();
        } catch (Throwable failure) {
            reportFailure("onActivityStarting", failure);
        }
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            FloatingWindowController controller = controllerFor(activity);
            applyHostedConfiguration(activity, controller);
            controller.install();
            controller.consumeIntent(activity.getIntent());
            NavigationBridgeClient.attachActivity(activity);
        } catch (Throwable failure) {
            reportFailure("onActivityResumed", failure);
        }
    }

    /** Called by MapActivity.dispatchTouchEvent before Navigator consumes the map gesture. */
    public static void onMapTouch(Activity activity, MotionEvent event) {
        if (activity == null || event == null || activity.isFinishing()) return;
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        try {
            controllerFor(activity).onMapTouch(event);
        } catch (Throwable failure) {
            reportFailure("onMapTouch", failure);
        }
    }

    /** Called at the end of MapActivity.onStop after every host controller has settled. */
    public static void onActivityStopped(Activity activity) {
        if (activity == null) return;
        try {
            NavigationBridgeClient.onActivityStopped();
        } catch (Throwable failure) {
            reportFailure("onActivityStopped", failure);
        }
    }

    public static boolean onNewIntent(Activity activity, Intent intent) {
        if (activity == null || intent == null || activity.isFinishing()) return false;
        try {
            activity.setIntent(intent);
            FloatingWindowController controller = controllerFor(activity);
            applyHostedConfiguration(activity, controller);
            boolean requestedFloating = controller.requestsFloating(intent);
            if (requestedFloating != controller.isFloating()) {
                controller.restartInMode(requestedFloating, intent);
                // The replacement Activity retains the complete source Intent, including a route
                // URI. The old Activity must not let Yandex process the same command afterwards.
                return true;
            } else {
                controller.consumeIntent(intent);
                // Pure ddnavwin/fullscreen control commands belong only to this controller.
                // Route deep links still continue through MapActivity's original onNewIntent.
                return isPureWindowCommand(intent);
            }
        } catch (Throwable failure) {
            reportFailure("onNewIntent", failure);
            return false;
        }
    }

    public static void onActivityDestroyed(Activity activity) {
        try {
            NavigationBridgeClient.detachActivity(activity);
            FloatingWindowController controller = CONTROLLERS.remove(activity);
            synchronized (MOVABLE_MAP_ACTIVITIES) {
                MOVABLE_MAP_ACTIVITIES.remove(activity);
            }
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

    /**
     * Called while MapKit constructs its platform view. A floating launch must use MapKit's
     * built-in movable TextureView renderer: unlike SurfaceView/Vulkan it remains in the Android
     * view hierarchy and Android 9 can safely clip it to a rounded decor outline.
     */
    public static boolean shouldUseMovableMap(Context context) {
        Activity activity = activityFrom(context);
        if (activity == null) return false;
        Intent intent = activity.getIntent();
        boolean movable = intent != null
                && !intent.getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false)
                && (intent.getBooleanExtra(EXTRA_WINDOWED, false)
                        || ACTION_FLOATING.equals(intent.getAction()));
        synchronized (MOVABLE_MAP_ACTIVITIES) {
            if (movable) MOVABLE_MAP_ACTIVITIES.put(activity, Boolean.TRUE);
            else MOVABLE_MAP_ACTIVITIES.remove(activity);
        }
        return movable;
    }

    static boolean usesMovableMap(Activity activity) {
        synchronized (MOVABLE_MAP_ACTIVITIES) {
            return Boolean.TRUE.equals(MOVABLE_MAP_ACTIVITIES.get(activity));
        }
    }

    private static Activity activityFrom(Context context) {
        Context current = context;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context base = ((ContextWrapper) current).getBaseContext();
            if (base == current) return null;
            current = base;
        }
        return null;
    }

    private static FloatingWindowController controllerFor(Activity activity) {
        FloatingWindowController controller = CONTROLLERS.get(activity);
        if (controller != null) return controller;
        controller = new FloatingWindowController(activity);
        CONTROLLERS.put(activity, controller);
        return controller;
    }

    private static void applyHostedConfiguration(Activity activity,
                                                 FloatingWindowController controller) {
        String raw = NavigationBridgeClient.readHostedConfiguration(activity);
        if (raw != null) controller.applyConfiguration(raw);
    }

    private static boolean isPureWindowCommand(Intent intent) {
        if (intent.getData() != null) return false;
        return intent.hasExtra(EXTRA_WINDOWED)
                || intent.hasExtra(EXTRA_FORCE_FULLSCREEN)
                || ACTION_FLOATING.equals(intent.getAction());
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
