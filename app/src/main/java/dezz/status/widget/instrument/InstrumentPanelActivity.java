/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

import dezz.status.widget.AppRuntimeBootstrap;
import dezz.status.widget.Preferences;
import dezz.status.widget.StatusWidgetApplication;

/** Touch-free 1920x720 activity projected to the driver's instrument display. */
public final class InstrumentPanelActivity extends Activity {
    private static volatile WeakReference<InstrumentPanelActivity> active =
            new WeakReference<>(null);

    private InstrumentPanelStore store;
    private InstrumentPanelView panel;
    private boolean receiverRegistered;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (InstrumentPanelStore.ACTION_CLOSE.equals(action)) {
                finishAndRemoveTask();
            } else if (InstrumentPanelStore.ACTION_CONFIG_CHANGED.equals(action)) {
                reload();
            }
        }
    };

    public static boolean isActive() {
        InstrumentPanelActivity value = active.get();
        return value != null && !value.isFinishing() && !value.isDestroyed();
    }

    public static void requestReload() {
        InstrumentPanelActivity value = active.get();
        if (value != null) value.runOnUiThread(value::reload);
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new InstrumentPanelStore(this);
        if (savedInstanceState == null && !store.consumeLaunchToken(
                getIntent() == null ? null
                        : getIntent().getStringExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN))) {
            finishAndRemoveTask();
            return;
        }
        if (!store.isEnabled()) {
            finishAndRemoveTask();
            return;
        }
        active = new WeakReference<>(this);
        configureWindow();
        panel = new InstrumentPanelView(this, store.load(), false, null);
        setContentView(panel);
        StatusWidgetApplication.notifyFirstUsefulSurface(this);
        AppRuntimeBootstrap.reconcileServices(this, new Preferences(this));
        IntentFilter filter = new IntentFilter();
        filter.addAction(InstrumentPanelStore.ACTION_CONFIG_CHANGED);
        filter.addAction(InstrumentPanelStore.ACTION_CLOSE);
        ContextCompat.registerReceiver(this, receiver, filter,
                ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null || store == null || !store.consumeLaunchToken(
                intent.getStringExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN))) {
            return;
        }
        setIntent(intent);
        reload();
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        reload();
    }

    @Override protected void onDestroy() {
        InstrumentPanelActivity value = active.get();
        if (value == this) active = new WeakReference<>(null);
        if (receiverRegistered) {
            receiverRegistered = false;
            try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        // A steering-wheel Back event must not remove the instrument panel while driving.
    }

    private void reload() {
        if (store == null) return;
        if (!store.isEnabled()) {
            finishAndRemoveTask();
            return;
        }
        if (panel != null) panel.updateConfig(store.load());
    }

    private void configureWindow() {
        Window window = getWindow();
        if (window == null) return;
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        immersive();
    }

    private void immersive() {
        Window window = getWindow();
        if (window == null) return;
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
