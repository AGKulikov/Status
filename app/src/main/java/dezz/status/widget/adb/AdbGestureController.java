/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import dezz.status.widget.Preferences;
import dezz.status.widget.WidgetAccessibilityService;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.media.ButtonAction;
import dezz.status.widget.media.VehicleButtonController;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Optional ECARX fullscreen touch listener and OEM HOME hotspot, owned by Natro accessibility. */
public final class AdbGestureController {
    private static AdbGestureController active;
    private final WidgetAccessibilityService service;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Preferences prefs;
    private Object manager;
    private View home;
    private boolean tracking, moved, held;
    private float startY;
    private int pointer;
    private final View.OnTouchListener listener = this::touch;
    private final Runnable hold = this::performHold;
    private void performHold() { if (tracking && !moved) {
        held = true; service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    } }

    private AdbGestureController(WidgetAccessibilityService service) { this.service = service; prefs = new Preferences(service); }
    public static void attach(WidgetAccessibilityService service) {
        if (active != null) active.close();
        active = new AdbGestureController(service); active.refresh();
    }
    public static void detach(WidgetAccessibilityService service) {
        if (active != null && active.service == service) { active.close(); active = null; }
    }
    public static void refreshActive() { new Handler(Looper.getMainLooper()).post(() -> { if (active != null) active.refresh(); }); }
    public static void setEnabled(boolean enabled) throws Exception {
        CompletableFuture<Void> result = new CompletableFuture<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (active == null) throw new IllegalStateException("Специальные возможности Natro не подключены");
                if (enabled) active.register(); else active.unregister();
                active.prefs.adbThreeFinger.set(enabled); result.complete(null);
            } catch (Exception error) { result.completeExceptionally(error); }
        });
        result.get(5, TimeUnit.SECONDS);
    }
    private void refresh() {
        try { if (prefs.adbThreeFinger.get()) register(); else unregister(); }
        catch (Exception error) { DiagnosticJournal.warn("adb", "Three-finger registration failed: " + error.getClass().getSimpleName()); }
        removeHome();
        if (!prefs.driverPanelEnabled.get() || !prefs.adbHomeHotspotEnabled.get()) return;
        try {
            home = new View(service);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(160, 120,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 552, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.START | Gravity.BOTTOM;
            boolean rtl = service.getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            params.x = rtl ? 1800 : -160; params.y = prefs.adbGmcSidebar.get() ? 130 : 50;
            GestureDetector detector = new GestureDetector(service, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapConfirmed(MotionEvent event) { action(ButtonAction.HOME); return true; }
                @Override public boolean onDoubleTap(MotionEvent event) { action(ButtonAction.RECENTS); return true; }
                @Override public void onLongPress(MotionEvent event) { action(ButtonAction.LAST_APP); }
            });
            home.setOnTouchListener((view, event) -> detector.onTouchEvent(event));
            service.getSystemService(WindowManager.class).addView(home, params);
        } catch (RuntimeException error) { home = null; DiagnosticJournal.warn("adb", "HOME hotspot unavailable: " + error.getClass().getSimpleName()); }
    }
    private void register() throws Exception {
        if (manager != null) return;
        Object interaction = Class.forName("com.ecarx.xui.adaptapi.uiinteraction.UiInteractionImpl")
                .getMethod("create", android.content.Context.class).invoke(null, service.getApplicationContext());
        Object candidate = interaction.getClass().getMethod("getTouchManager").invoke(interaction);
        Object accepted = candidate.getClass().getMethod("registerFullScreenTouchListener", int.class, View.OnTouchListener.class)
                .invoke(candidate, 0, listener);
        if (!Boolean.TRUE.equals(accepted)) throw new IllegalStateException("ECARX отклонил регистрацию жестов");
        manager = candidate; DiagnosticJournal.infoAsync("adb", "Three-finger listener registered");
    }
    private void unregister() throws Exception {
        main.removeCallbacks(hold); tracking = false;
        if (manager != null) {
            manager.getClass().getMethod("unregisterFullScreenTouchListener", View.OnTouchListener.class).invoke(manager, listener);
            manager = null;
        }
    }
    private boolean touch(View view, MotionEvent original) {
        MotionEvent event = MotionEvent.obtain(original);
        main.post(() -> { try { process(event); } finally { event.recycle(); } }); return false;
    }
    private void process(MotionEvent event) {
        int action = event.getActionMasked();
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && event.getPointerCount() == 3) {
            startY = event.getY(); pointer = event.getPointerId(0); tracking = true; moved = held = false;
            main.removeCallbacks(hold); main.postDelayed(hold, 600);
        }
        if (!tracking) return;
        int index = event.findPointerIndex(pointer);
        if (index < 0 || action == MotionEvent.ACTION_CANCEL) { tracking = false; main.removeCallbacks(hold); return; }
        float delta = event.getY(index) - startY;
        if (Math.abs(delta) > 50) { moved = true; main.removeCallbacks(hold); }
        if (action != MotionEvent.ACTION_UP) return;
        main.removeCallbacks(hold); tracking = false;
        if (held) return;
        if (delta <= -250) action(ButtonAction.LAST_APP);
        else if (delta < -50) action(ButtonAction.RECENTS);
        else if (delta >= 250) action(ButtonAction.HOME);
        else if (delta > 50) action(ButtonAction.BACK);
        else service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
    }
    private void action(ButtonAction action) { VehicleButtonController.get(service).executeAdbGesture(action); }
    private void removeHome() {
        if (home != null) try { service.getSystemService(WindowManager.class).removeView(home); } catch (RuntimeException ignored) {}
        home = null;
    }
    private void close() { try { unregister(); } catch (Exception ignored) {} removeHome(); main.removeCallbacksAndMessages(null); }
}
