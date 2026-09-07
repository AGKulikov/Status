/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.instrument;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.lang.ref.WeakReference;

import dezz.status.widget.AppRuntimeBootstrap;
import dezz.status.widget.Preferences;
import dezz.status.widget.StatusWidgetApplication;
import dezz.status.widget.navigation.NavigationHudEndpointService;

/** Touch-free 1920x720 activity projected to the driver's instrument display. */
public final class InstrumentPanelActivity extends Activity {
    private static volatile WeakReference<InstrumentPanelActivity> active = new WeakReference<>(null);
    private InstrumentPanelStore store;
    private InstrumentPanelView panel;
    private boolean receiverRegistered, authorized, started, drawn, destroyed;
    private int pendingAuthorizations;
    private String lastWindowState = "";
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            () -> reportWindowState("layout");
    private final ViewTreeObserver.OnDrawListener drawListener = () -> {
        if (!drawn && panel != null && panel.isAttachedToWindow() && panel.isShown()) {
            drawn = true;
            // Report after this traversal, never change the hierarchy inside onDraw.
            panel.post(() -> reportWindowState("first-draw"));
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (InstrumentPanelStore.ACTION_CLOSE.equals(action)) {
                finishPanel("close-command");
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

    /** A real display power/QuickBoot event invalidates only this Activity, never the package. */
    static void finishForDisplayRestore() {
        InstrumentPanelActivity value = active.get();
        if (value != null && !value.isFinishing()) value.finishPanel("display-lifecycle-restore");
    }

    static InstrumentPanelLaunchCoordinator.WindowState windowState() {
        InstrumentPanelActivity value = active.get();
        if (value == null || value.destroyed || value.isFinishing() || !value.authorized
                || value.panel == null) return InstrumentPanelLaunchCoordinator.WindowState.absent();
        View root = value.panel;
        Display display = root.getDisplay();
        return new InstrumentPanelLaunchCoordinator.WindowState(true, value.started,
                root.isAttachedToWindow(), root.isShown() && root.getWindowVisibility() == View.VISIBLE,
                value.drawn, display == null ? -1 : display.getDisplayId(),
                root.getWidth(), root.getHeight());
    }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new InstrumentPanelStore(this);
        InstrumentDisplayLauncher.trace("activity create task=" + getTaskId()
                + " restored=" + (savedInstanceState != null) + " display=" + actualDisplayId());
        if (!store.isEnabled()) { finishPanel("disabled-on-create"); return; }
        if (savedInstanceState != null) {
            // Android restores an already-authorized Activity after its one-use token was consumed.
            authorized = true;
            initializePanel();
        } else {
            authorizeIntent(getIntent());
        }
    }

    private void authorizeIntent(@Nullable Intent intent) {
        String candidate = null;
        try {
            if (intent != null) candidate = intent.getStringExtra(InstrumentPanelStore.EXTRA_LAUNCH_TOKEN);
        } catch (RuntimeException invalidExtras) {
            InstrumentDisplayLauncher.trace("activity invalid launch extras");
        }
        if (candidate == null || candidate.length() < 16 || candidate.length() > 128) {
            InstrumentDisplayLauncher.trace("activity rejected invalid capability format");
            if (!authorized && pendingAuthorizations == 0) finishPanel("invalid-capability");
            return;
        }
        pendingAuthorizations++;
        InstrumentDisplayLauncher.trace("activity authorization pending task=" + getTaskId());
        InstrumentDisplayLauncher.authorize(this, candidate, accepted -> {
            pendingAuthorizations--;
            if (destroyed || isFinishing()) return;
            if (!accepted) {
                InstrumentDisplayLauncher.trace("activity authorization rejected task=" + getTaskId());
                // An unrelated invalid onNewIntent must not cancel a valid in-flight creation.
                if (!authorized && pendingAuthorizations == 0) finishPanel("capability-rejected");
                return;
            }
            if (!store.isEnabled()) { finishPanel("disabled-after-authorization"); return; }
            authorized = true;
            setIntent(intent);
            InstrumentDisplayLauncher.trace("activity authorization accepted task=" + getTaskId());
            initializePanel();
        });
    }

    private void initializePanel() {
        if (actualDisplayId() != store.load().displayId) {
            finishPanel("wrong-display actual=" + actualDisplayId()
                    + " expected=" + store.load().displayId);
            return;
        }
        if (panel != null) { reload(); reportWindowState("new-intent"); return; }
        active = new WeakReference<>(this);
        configureWindow();
        NavigationHudEndpointService.ensureClusterEndpointStarted(this);
        panel = new InstrumentPanelView(this, store.load(), false, null);
        panel.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                drawn = false;
                reportWindowState("attached");
            }
            @Override public void onViewDetachedFromWindow(View view) {
                drawn = false;
                reportWindowState("detached");
            }
        });
        panel.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        panel.getViewTreeObserver().addOnDrawListener(drawListener);
        setContentView(panel);
        StatusWidgetApplication.notifyFirstUsefulSurface(this);
        AppRuntimeBootstrap.reconcileServices(this, new Preferences(this));
        IntentFilter filter = new IntentFilter();
        filter.addAction(InstrumentPanelStore.ACTION_CONFIG_CHANGED);
        filter.addAction(InstrumentPanelStore.ACTION_CLOSE);
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        receiverRegistered = true;
        reportWindowState("content-created");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (store != null) authorizeIntent(intent);
    }

    @Override protected void onStart() {
        super.onStart();
        started = true;
        reportWindowState("start");
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        reload();
        reportWindowState("resume");
    }

    @Override protected void onPause() {
        super.onPause();
        // Android 9 has a single RESUMED Activity across displays. PAUSED is not hidden.
        InstrumentDisplayLauncher.trace("activity pause task=" + getTaskId()
                + " " + windowState());
    }

    @Override protected void onStop() {
        started = false;
        super.onStop();
        reportWindowState("stop");
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        reportWindowState("focus=" + hasFocus);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        InstrumentDisplayLauncher.trace("activity destroy task=" + getTaskId()
                + " finishing=" + isFinishing() + " configuration=" + isChangingConfigurations());
        if (panel != null && panel.getViewTreeObserver().isAlive()) {
            panel.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
            panel.getViewTreeObserver().removeOnDrawListener(drawListener);
        }
        if (active.get() == this) active = new WeakReference<>(null);
        if (receiverRegistered) {
            receiverRegistered = false;
            try { unregisterReceiver(receiver); } catch (RuntimeException ignored) {}
        }
        super.onDestroy();
        InstrumentDisplayLauncher.windowChanged();
    }

    @Override public void onBackPressed() {
        // A steering-wheel Back event must not remove the instrument panel while driving.
    }

    private void reload() {
        if (store == null || !authorized) return;
        if (!store.isEnabled()) { finishPanel("disabled-on-reload"); return; }
        if (panel != null) panel.updateConfig(store.load());
    }

    private int actualDisplayId() {
        Display display = getWindowManager().getDefaultDisplay();
        return display == null ? -1 : display.getDisplayId();
    }

    private void finishPanel(String reason) {
        InstrumentDisplayLauncher.trace("activity finish reason=" + reason + " task=" + getTaskId());
        finishAndRemoveTask();
    }

    private void reportWindowState(String reason) {
        if (destroyed) return;
        String state = windowState().toString();
        if (!state.equals(lastWindowState)) {
            lastWindowState = state;
            InstrumentDisplayLauncher.trace("activity window reason=" + reason
                    + " task=" + getTaskId() + " " + state);
        }
        InstrumentDisplayLauncher.windowChanged();
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
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
