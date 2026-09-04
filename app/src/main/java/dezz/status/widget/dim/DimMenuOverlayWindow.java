/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.Map;

import dezz.status.widget.launcher.LauncherShortcutStore;

/** Exact bounded overlay; it can never intercept touch input on the driver's display. */
final class DimMenuOverlayWindow {
    @NonNull private final WindowManager manager;
    @NonNull private final DimMenuPanelView view;
    private boolean attached = true;

    private DimMenuOverlayWindow(@NonNull WindowManager manager,
                                 @NonNull DimMenuPanelView view) {
        this.manager = manager;
        this.view = view;
    }

    @NonNull
    static DimMenuOverlayWindow show(@NonNull Context context, @NonNull Display display,
                                     @NonNull DimMenuPanelConfig config,
                                     @NonNull List<LauncherShortcutStore.Shortcut> items,
                                     int selection) {
        Context displayContext = context.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            displayContext = displayContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        WindowManager manager = displayContext.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("DIM WindowManager unavailable");
        // mNavi inflates the overlay with the application resources and only obtains the window
        // manager from the display context. Mirroring that detail keeps dp/sp metrics identical.
        DimMenuPanelView view = new DimMenuPanelView(context, config, items);
        view.setSelectedIndex(selection);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                config.width, config.height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = config.x;
        params.y = config.y;
        params.alpha = 1f;
        params.dimAmount = 0f;
        params.setTitle("Natro DIM menu");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
        }
        manager.addView(view, params);
        return new DimMenuOverlayWindow(manager, view);
    }

    void setSelectedIndex(int index) {
        if (attached) view.setSelectedIndex(index);
    }

    void setStatuses(@NonNull Map<String, String> statuses) {
        if (attached) view.setStatuses(statuses);
    }

    void dismiss() {
        if (!attached) return;
        attached = false;
        try { manager.removeViewImmediate(view); }
        catch (RuntimeException first) {
            try { manager.removeView(view); }
            catch (RuntimeException ignored) { }
        }
    }
}
