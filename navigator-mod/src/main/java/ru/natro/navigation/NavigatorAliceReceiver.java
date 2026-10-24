/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;

/** Process-local command receiver. Never starts, resumes or moves an existing Activity. */
public final class NavigatorAliceReceiver extends BroadcastReceiver {
    public static final String ACTION = "dd.call_alice";
    public static final String PERMISSION = "ru.natro.statuswidget.permission.CALL_NAVIGATOR_ALICE";
    public static final int READY = 33801, ACCEPTED = 33802, NOT_READY = 33803;
    private static NavigatorAliceReceiver instance;
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    interface Target { boolean ready(); boolean start(); int state(); }
    private final Target target;
    private NavigatorAliceSession session;
    private final LinkedHashMap<String, Integer> consumed = new LinkedHashMap<>();
    NavigatorAliceReceiver(Target target) { this.target = target; }

    static void attach(Activity value) {
        activity = new WeakReference<>(value);
        if (instance != null) return;
        NavigatorAliceReceiver receiver = new NavigatorAliceReceiver(new Target() {
            private NavigatorAliceHost host() { return new NavigatorAliceHost(activity.get()); }
            public boolean ready() { return host().ready(); }
            public boolean start() { return host().start(); }
            public int state() { return host().state(); }
        });
        value.getApplicationContext().registerReceiver(receiver, new IntentFilter(ACTION),
                PERMISSION, MAIN);
        instance = receiver;
    }
    static void detach(Activity value) {
        if (activity.get() != value) return;
        activity.clear();
        if (instance != null && instance.session != null) instance.session.abort();
    }
    @Override public void onReceive(Context context, Intent intent) {
        int result = receive(intent, SystemClock.elapsedRealtime());
        if (isOrderedBroadcast()) setResultCode(result);
    }
    int receive(Intent intent, long now) {
        if (intent == null || !ACTION.equals(intent.getAction())) return NOT_READY;
        long until = intent.getLongExtra("natro_alice_deadline", 0);
        String id = intent.getStringExtra("natro_alice_id");
        if (id == null || id.isEmpty() || id.length() > 80 || until <= now || until - now > 15000) return NOT_READY;
        if (consumed.containsKey(id)) return consumed.get(id);
        if ((session != null && session.active()) || !target.ready()) return NOT_READY;
        if (intent.getBooleanExtra("natro_alice_probe", false)) return READY;
        ResultReceiver callback = intent.getParcelableExtra("natro_alice_callback");
        if (callback == null) return NOT_READY;
        consumed.put(id, NOT_READY);
        if (consumed.size() > 32) consumed.remove(consumed.keySet().iterator().next());
        session = new NavigatorAliceSession(new NavigatorAliceSession.Backend() {
            public long now() { return SystemClock.elapsedRealtime(); }
            public int state() { return target.state(); }
            public void later(long delay, Runnable task) { MAIN.postDelayed(task, delay); }
            public void event(int result) { callback.send(result, null); }
        });
        try {
            if (!target.start()) { session.abort(); return NOT_READY; }
            consumed.put(id, ACCEPTED);
            MAIN.post(session::poll);
            return ACCEPTED;
        } catch (RuntimeException failure) { session.abort(); return NOT_READY; }
    }
}
