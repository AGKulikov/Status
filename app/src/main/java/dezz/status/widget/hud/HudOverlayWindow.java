/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/**
 * Exact ECARX HUD overlay discovered in the device dump.
 *
 * <p>The OEM/plus.monjaro window on displayId=2 is an application overlay at (0,720), sized
 * 728x190. Reusing that physical window instead of a full 1920x1080 transparent surface makes the
 * safety boundary a WindowManager boundary as well as a Canvas clip.</p>
 */
final class HudOverlayWindow {
    @NonNull private final WindowManager manager;
    @NonNull private final HudCompositeView content;
    private boolean attached = true;

    private HudOverlayWindow(@NonNull WindowManager manager,
                             @NonNull HudCompositeView content) {
        this.manager = manager;
        this.content = content;
    }

    @NonNull
    static HudOverlayWindow show(@NonNull Context context,
                                 @NonNull Display display,
                                 @NonNull HudPanelConfig config,
                                 @NonNull HudRuntimeData data) {
        Context displayContext = context.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        WindowManager manager = (WindowManager) displayContext.getSystemService(
                Context.WINDOW_SERVICE);
        if (manager == null) throw new IllegalStateException("HUD WindowManager unavailable");

        HudCompositeView content = new HudCompositeView(
                displayContext, config, data, true);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                HudViewportPolicy.SAFE_WIDTH,
                HudViewportPolicy.SAFE_HEIGHT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = HudViewportPolicy.SAFE_LEFT;
        params.y = HudViewportPolicy.SAFE_TOP;
        params.setTitle("Natro HUD overlay 728x190");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        manager.addView(content, params);
        return new HudOverlayWindow(manager, content);
    }

    void updateConfig(@NonNull HudPanelConfig config) {
        if (attached) content.updateConfig(config);
    }

    void invalidateHud() {
        if (attached) content.invalidateHud();
    }

    void dismiss() {
        if (!attached) return;
        attached = false;
        try {
            manager.removeViewImmediate(content);
        } catch (RuntimeException ignored) {
            try {
                manager.removeView(content);
            } catch (RuntimeException ignoredAgain) {
                // Display removal may detach the view before the service receives its callback.
            }
        }
    }
}
