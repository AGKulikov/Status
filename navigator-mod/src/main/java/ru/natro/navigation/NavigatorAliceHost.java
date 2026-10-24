/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Version-specific public entry points verified against the paired Navigator 30.3.0 APK. */
final class NavigatorAliceHost implements NavigatorAliceReceiver.Target {
    private final WeakReference<Activity> activity;
    NavigatorAliceHost(Activity value) { activity = new WeakReference<>(value); }

    private Activity activity() {
        Activity value = activity.get();
        if (value == null || value.isFinishing() || value.isDestroyed())
            throw new IllegalStateException("Navigator Activity unavailable");
        return value;
    }
    private Object router() throws ReflectiveOperationException {
        Activity value = activity();
        // MapActivity.r() is the dedicated maps_activity_alice_container router.
        Object router = value.getClass().getMethod("r").invoke(value);
        if (router == null) throw new IllegalStateException("Alice router unavailable");
        return router;
    }
    @Override public int state() {
        try {
            Object router = router();
            Class<?> type = Class.forName("com.bluelinelabs.conductor.c0", false,
                    activity().getClassLoader());
            return ((Integer) type.getMethod("g").invoke(router)) > 0 ? 1 : 0;
        } catch (ReflectiveOperationException | RuntimeException failure) { return -1; }
    }
    @Override public boolean ready() {
        try { return state() == 0 && launcher() != null; }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    private Object launcher() throws ReflectiveOperationException {
        Activity value = activity();
        Object injects = value.getClass().getMethod("u").invoke(value);
        // Public MapActivity injects.d is app.x3, also used by the stock voice button.
        return injects == null ? null : injects.getClass().getField("d").get(injects);
    }
    @Override public boolean start() {
        try {
            Object launcher = launcher();
            if (launcher == null || state() != 0) return false;
            ClassLoader loader = activity().getClassLoader();
            Class<?> type = Class.forName("ru.yandex.yandexmaps.app.x3", false, loader);
            Class<?> source = Class.forName(
                    "ru.yandex.yandexmaps.multiplatform.analytics.GeneratedAppAnalytics$AliceStartSource", false, loader);
            Method open = type.getMethod("x", type, source, String.class, String.class, int.class);
            // Exactly the native BUTTON entry: permission/availability checks remain native.
            // No Activity Intent, task move, view attach or window configuration is performed.
            open.invoke(null, launcher, source.getField("BUTTON").get(null), null, null, 6);
            return true; // Dispatch only. STARTED is reported separately from the actual router.
        } catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
}
