/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pinned 30.3.0 engine observer for button, intent and wake-word activation. */
public final class NativeAliceBridge {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IPC = Executors.newSingleThreadExecutor();
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static Monitor monitor;
    private NativeAliceBridge() {}
    static void attach(Activity value) { activity = new WeakReference<>(value); }
    static void detach(Activity value) {
        if (activity.get() != value) return;
        activity.clear();
        if (monitor != null) monitor.close(true);
    }
    /** Runs immediately BEFORE the engine requests focus, independent of its activation source.
     * OEM Binder work runs on IPC, with a hard 300ms bound on this native handoff. A missed
     * snapshot never authorizes PLAY and never prevents Alice from continuing normally. */
    public static void beforeAudioFocus() {
        Activity host = activity.get();
        if (host == null || host.isDestroyed() || host.isFinishing()) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
            long until = SystemClock.elapsedRealtime() + 300;
            MAIN.post(() -> {
                try { if (SystemClock.elapsedRealtime() < until) prepareFocus(host, until); }
                finally { ready.countDown(); }
            });
            try { ready.await(300, java.util.concurrent.TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        } else prepareFocus(host, SystemClock.elapsedRealtime() + 300);
    }
    private static void prepareFocus(Activity host, long until) {
        try {
            if (host.getPackageManager().checkSignatures(host.getPackageName(), "ru.natro.statuswidget")
                    != android.content.pm.PackageManager.SIGNATURE_MATCH) return;
            if (monitor != null && !monitor.closed) {
                if (!monitor.idle || SystemClock.elapsedRealtime() - monitor.idleSince < 2000) {
                    monitor.state(Boolean.FALSE); // Cancel the pending two-second continuation now.
                    return;
                }
                monitor.close(true);
            }
            Object injects = host.getClass().getMethod("u").invoke(host);
            Object launcher = injects.getClass().getField("d").get(injects);
            Field lazyField = launcher.getClass().getDeclaredField("i"); lazyField.setAccessible(true);
            Object lazy = lazyField.get(launcher);
            Object service = Class.forName("lu1.a", false, host.getClassLoader()).getMethod("get").invoke(lazy);
            String id = UUID.randomUUID().toString();
            String origin = NavigatorAliceReceiver.takeLaunchId();
            java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<ResultReceiver> sink = new java.util.concurrent.atomic.AtomicReference<>();
            ResultReceiver reply = new ResultReceiver(null) {
                @Override protected void onReceiveResult(int code, Bundle data) {
                    try {
                        if (code == 34101 && data != null && SystemClock.elapsedRealtime() < until)
                            sink.set(data.getParcelable("events"));
                    } catch (RuntimeException malformed) { /* No permit. */ }
                    finally { answered.countDown(); }
                }
            };
            Bundle data = new Bundle(); data.putLong("deadline", until);
            data.putString("origin", origin); data.putParcelable("reply", framework(reply));
            IPC.execute(() -> {
                try {
                    host.getContentResolver().call(Uri.parse("content://ru.natro.statuswidget.navigation.configuration"),
                            "alice_prepare_v1", id, data);
                } catch (RuntimeException unavailable) { answered.countDown(); }
            });
            answered.await(Math.max(0, until - SystemClock.elapsedRealtime()), java.util.concurrent.TimeUnit.MILLISECONDS);
            ResultReceiver events = sink.get();
            if (events == null || SystemClock.elapsedRealtime() >= until) {
                Log.w("NatroNavigatorAlice", "Pre-focus snapshot unavailable; automatic PLAY disabled");
                return;
            }
            monitor = new Monitor(host, service, id, events);
            monitor.bind();
            // A focus request is an activation boundary, not proof of completion.
            monitor.state(Boolean.FALSE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (monitor != null) monitor.close(true);
            Log.w("NatroNavigatorAlice", "Engine observer unavailable", failure);
        }
    }
    /** Native IO errors must not become a successful end merely because the engine goes idle. */
    public static void onEngineError(Object error) {
        try {
            Object kind = error.getClass().getMethod("a").invoke(error);
            if (kind instanceof Enum && ((Enum<?>) kind).name().equals("ErrorNoSpeechDetected")) return;
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
        if (Looper.myLooper() == Looper.getMainLooper()) { if (monitor != null) monitor.close(true); }
        else MAIN.post(() -> { if (monitor != null) monitor.close(true); });
    }
    private static final class Monitor {
        final Activity host;
        final Object service;
        final String id;
        final ResultReceiver events;
        Object subscription;
        long sequence, idleSince;
        boolean activeSeen, idle, closed;
        Monitor(Activity host, Object service, String id, ResultReceiver events) {
            this.host = host; this.service = service; this.id = id; this.events = events;
        }
        void bind() throws ReflectiveOperationException {
            String name = service.getClass().getName();
            if (!name.equals("ru.yandex.yandexmaps.alice.internal.alicekit.service.l")
                    && !name.equals("ru.yandex.yandexmaps.alice.internal.quark.c0"))
                throw new IllegalStateException("Unsupported Alice service");
            ClassLoader loader = service.getClass().getClassLoader();
            Class<?> consumer = Class.forName("h02.g", false, loader);
            Object next = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                if (method.getName().equals("accept")) {
                    Object value = args[0];
                    if (Looper.myLooper() == Looper.getMainLooper()) state(value);
                    else MAIN.post(() -> state(value));
                }
                return objectMethod(proxy, method, args);
            });
            Object error = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                if (method.getName().equals("accept")) MAIN.post(() -> close(true));
                return objectMethod(proxy, method, args);
            });
            // Both version-pinned implementations expose the native engine-idle stream here.
            Object observable = service.getClass().getMethod("i").invoke(service);
            subscription = Class.forName("io.reactivex.r", false, loader)
                    .getMethod("subscribe", consumer, consumer).invoke(observable, next, error);
            MAIN.postDelayed(() -> { if (!activeSeen) close(true); }, 10000);
            MAIN.postDelayed(() -> close(true), 30 * 60 * 1000);
            watchdog();
            Log.i("NatroNavigatorAlice", "native=" + id + " backend=" + name);
        }
        void state(Object value) {
            if (closed) return;
            if (!(value instanceof Boolean)) { close(true); return; }
            boolean nextIdle = (Boolean) value;
            if (!nextIdle) {
                if (!activeSeen || idle) emit(33804);
                activeSeen = true; idle = false;
            } else if (activeSeen && !idle) {
                idle = true; idleSince = SystemClock.elapsedRealtime(); emit(33805);
            }
        }
        void emit(int code) {
            Bundle data = new Bundle(); data.putString("id", id);
            data.putLong("sequence", ++sequence); data.putLong("elapsed", SystemClock.elapsedRealtime());
            events.send(code, data);
        }
        void watchdog() {
            if (closed) return;
            if (activity.get() != host || host.isDestroyed() || host.isFinishing()) { close(true); return; }
            if (activeSeen) emit(idle ? 33805 : 33804);
            MAIN.postDelayed(this::watchdog, 250);
        }
        void close(boolean abort) {
            if (closed) return;
            closed = true;
            if (abort) emit(33806);
            if (subscription != null) try {
                Class.forName("io.reactivex.disposables.b", false, service.getClass().getClassLoader())
                        .getMethod("dispose").invoke(subscription);
            } catch (ReflectiveOperationException | RuntimeException ignored) { }
        }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName().equals("equals")) return args != null && proxy == args[0];
        if (method.getName().equals("toString")) return "NatroAliceObserver";
        return null;
    }
    private static ResultReceiver framework(ResultReceiver local) {
        Parcel parcel = Parcel.obtain();
        try {
            local.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
}
