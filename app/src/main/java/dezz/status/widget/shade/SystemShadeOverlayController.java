/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.shade;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.Preferences;

/** Owns the main-display application overlay and makes every reconcile idempotent. */
final class SystemShadeOverlayController implements DisplayManager.DisplayListener {
    @NonNull private final Context app;
    @NonNull private final Preferences preferences;
    @NonNull private final SystemShadeStore store;
    @Nullable private DisplayManager displays;
    @Nullable private WindowManager manager;
    @Nullable private SystemShadeRootLayout root;
    @Nullable private SystemShadePanelView panel;
    private boolean started;
    private boolean vehicleOverlayActive;

    SystemShadeOverlayController(@NonNull Context context, @NonNull Preferences preferences) {
        Context value = context.getApplicationContext();
        app = value == null ? context : value;
        this.preferences = preferences;
        store = new SystemShadeStore(preferences);
    }

    void start() {
        if (started) return;
        started = true;
        displays = (DisplayManager) app.getSystemService(Context.DISPLAY_SERVICE);
        if (displays != null) displays.registerDisplayListener(this, null);
        reconcile();
    }

    void stop() {
        started = false;
        if (displays != null) displays.unregisterDisplayListener(this);
        displays = null;
        dismiss();
    }

    void setVehicleOverlayActive(boolean active) {
        vehicleOverlayActive = active;
        SystemShadeRootLayout current = root;
        if (current != null) current.setSuppressed(active);
    }

    void reload() {
        dismiss();
        reconcile();
    }

    private void reconcile() {
        if (!started || !store.isEnabled() || !Settings.canDrawOverlays(app)) {
            dismiss();
            return;
        }
        SystemShadeConfig config = store.load();
        Display display = displays == null ? null : displays.getDisplay(config.displayId);
        if (display == null) display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) return;
        show(display, config);
    }

    private void show(@NonNull Display display, @NonNull SystemShadeConfig config) {
        dismiss();
        Context displayContext = app.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        manager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return;
        SystemShadeRootLayout host = new SystemShadeRootLayout(displayContext, config);
        SystemShadePanelView content = new SystemShadePanelView(displayContext, preferences, config,
                () -> { if (config.closeAfterAction) host.close(true); });
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, config.gestureHandleHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        params.setTitle("Natro system shade");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        host.setListener(new SystemShadeRootLayout.Listener() {
            @Override public void onGestureCaptureStarted() {
                updateWindowHeight(host, params, WindowManager.LayoutParams.MATCH_PARENT);
            }

            @Override public void onOpenStateChanged(boolean open) {
                updateWindowHeight(host, params, open
                        ? WindowManager.LayoutParams.MATCH_PARENT
                        : config.gestureHandleHeightPx);
            }
        });
        host.setPanel(content, config);
        host.setSuppressed(vehicleOverlayActive);
        try {
            manager.addView(host, params);
            root = host;
            panel = content;
            content.start();
        } catch (RuntimeException failure) {
            manager = null;
            root = null;
            panel = null;
        }
    }

    private void updateWindowHeight(@NonNull SystemShadeRootLayout host,
                                    @NonNull WindowManager.LayoutParams params, int height) {
        WindowManager current = manager;
        if (current == null || root != host || params.height == height) return;
        params.height = height;
        try { current.updateViewLayout(host, params); }
        catch (RuntimeException ignored) { }
    }

    private void dismiss() {
        SystemShadePanelView oldPanel = panel;
        panel = null;
        if (oldPanel != null) oldPanel.stop();
        WindowManager oldManager = manager;
        SystemShadeRootLayout oldRoot = root;
        manager = null;
        root = null;
        if (oldManager != null && oldRoot != null) {
            try { oldManager.removeViewImmediate(oldRoot); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public void onDisplayAdded(int displayId) { reconcile(); }
    @Override public void onDisplayRemoved(int displayId) { reconcile(); }
    @Override public void onDisplayChanged(int displayId) { }
}
