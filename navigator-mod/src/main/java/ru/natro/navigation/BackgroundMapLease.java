/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.os.Handler;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Keeps MapKit rendering only while an external HUD/cluster Surface actually needs it.
 *
 * <p>Navigator's process lifecycle stops MapKit shortly after its Activity goes to the
 * background. The active Guidance foreground service keeps the navigation process/session alive,
 * but that lifecycle stop would otherwise freeze every independent OffscreenMapWindow. This lease
 * reasserts MapKit once after the host transition and releases it as soon as both external
 * surfaces disappear. There is deliberately no timer loop or polling.</p>
 */
final class BackgroundMapLease {
    interface DiagnosticSink {
        void report(String detail);
    }

    private static final String TAG = "NatroBackgroundMap";
    /** ProcessLifecycleOwner applies its background transition after a short grace period. */
    static final long HOST_STOP_SETTLE_MS = 1_000L;

    private final Handler main;
    private final DiagnosticSink diagnostics;
    private boolean activityForeground = true;
    private boolean externalMapActive;
    private boolean backgroundStartApplied;

    BackgroundMapLease(Handler main, DiagnosticSink diagnostics) {
        this.main = main;
        this.diagnostics = diagnostics;
    }

    void onActivityStarting() {
        main.removeCallbacks(reassertAfterHostStop);
        activityForeground = true;
        // The host lifecycle owns the foreground start. MapKit's onStart/onStop API is stateful,
        // not a reference-counted resource, so stopping our background lease here could race the
        // host's own onStart on Android 9. Simply hand ownership back to the foreground lifecycle.
        backgroundStartApplied = false;
    }

    void onActivityStopped() {
        activityForeground = false;
        reconcile();
    }

    void setExternalMapActive(boolean active) {
        if (externalMapActive == active) return;
        externalMapActive = active;
        reconcile();
    }

    private void reconcile() {
        main.removeCallbacks(reassertAfterHostStop);
        if (!activityForeground && externalMapActive) {
            startMapKit("host stopped");
            // AndroidX delays the process ON_STOP event. Reassert exactly once after that window;
            // this is event-driven and adds no recurring work while navigation runs.
            main.postDelayed(reassertAfterHostStop, HOST_STOP_SETTLE_MS);
        } else if (!activityForeground && backgroundStartApplied) {
            stopMapKit("external surfaces released");
        } else if (activityForeground) {
            backgroundStartApplied = false;
        }
    }

    private final Runnable reassertAfterHostStop = () -> {
        if (!activityForeground && externalMapActive) {
            startMapKit("host background transition settled");
        }
    };

    private void startMapKit(String reason) {
        try {
            Object mapKit = mapKit();
            invoke(mapKit, "onStart");
            backgroundStartApplied = true;
            String detail = "background MapKit lease active: " + reason;
            Log.i(TAG, detail);
            diagnostics.report(detail);
        } catch (Throwable failure) {
            backgroundStartApplied = false;
            String detail = "background MapKit lease could not start: " + shortMessage(failure);
            Log.w(TAG, detail, failure);
            diagnostics.report(detail);
        }
    }

    private void stopMapKit(String reason) {
        try {
            invoke(mapKit(), "onStop");
            String detail = "background MapKit lease released: " + reason;
            Log.i(TAG, detail);
            diagnostics.report(detail);
        } catch (Throwable failure) {
            String detail = "background MapKit lease could not stop: " + shortMessage(failure);
            Log.w(TAG, detail, failure);
            diagnostics.report(detail);
        } finally {
            backgroundStartApplied = false;
        }
    }

    private static Object mapKit() throws Exception {
        Class<?> factory = Class.forName("com.yandex.mapkit.MapKitFactory");
        return factory.getMethod("getInstance").invoke(null);
    }

    private static void invoke(Object target, String name) throws Exception {
        Method method = Class.forName("com.yandex.mapkit.MapKit").getMethod(name);
        method.invoke(target);
    }

    private static String shortMessage(Throwable failure) {
        Throwable value = failure;
        while (value.getCause() != null && value.getCause() != value) value = value.getCause();
        String message = value.getMessage();
        return value.getClass().getSimpleName()
                + (message == null || message.isEmpty() ? "" : ": " + message);
    }
}
