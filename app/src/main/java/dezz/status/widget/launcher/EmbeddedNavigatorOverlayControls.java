/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.LauncherActivity;
import dezz.status.widget.Permissions;

/** Adds one small control above the bundled Navigator window: expand in-panel or close fullscreen. */
public final class EmbeddedNavigatorOverlayControls {
    private static final long FULLSCREEN_CONTROL_DELAY_MS = 650L;
    private static EmbeddedNavigatorOverlayControls instance;

    @NonNull private final Context applicationContext;
    @NonNull private final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable private WindowManager attachedWindowManager;
    @Nullable private View attachedView;

    public static synchronized EmbeddedNavigatorOverlayControls get(@NonNull Context context) {
        if (instance == null) {
            instance = new EmbeddedNavigatorOverlayControls(context.getApplicationContext());
        }
        return instance;
    }

    private EmbeddedNavigatorOverlayControls(@NonNull Context applicationContext) {
        this.applicationContext = applicationContext;
    }

    public void showExpand(@NonNull Rect panelBounds, int displayId) {
        mainHandler.post(() -> attach("⛶", "Развернуть Навигатор",
                panelBounds.right - dp(54), panelBounds.top + dp(8), displayId, view -> {
                    try {
                        // TransparentSplash forwards this Intent to the already active
                        // MapActivity. Avoid finishing it first: doing so briefly resumes HOME
                        // and can race its automatic panel relaunch against fullscreen mode.
                        applicationContext.startActivity(
                                EmbeddedNavigatorContract.windowIntent(
                                        applicationContext, true));
                        showFullscreenClose(displayId);
                    } catch (RuntimeException ignored) {
                    }
                }));
    }

    public void showFullscreenClose(int displayId) {
        mainHandler.postDelayed(() -> {
            Context displayContext = displayContext(displayId);
            WindowManager manager = displayContext.getSystemService(WindowManager.class);
            if (manager == null) return;
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            Display display = manager.getDefaultDisplay();
            display.getRealMetrics(metrics);
            attach("×", "Закрыть полноэкранный Навигатор",
                    Math.max(0, metrics.widthPixels - dp(62)), dp(10), displayId, view -> {
                        EmbeddedNavigatorRuntime.finishNavigatorActivity();
                        hide();
                        Intent home = new Intent(applicationContext, LauncherActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                        applicationContext.startActivity(home);
                    });
        }, FULLSCREEN_CONTROL_DELAY_MS);
    }

    public void hide() {
        mainHandler.post(this::removeAttachedView);
    }

    private void attach(@NonNull String symbol, @NonNull String description, int x, int y,
            int displayId, @NonNull View.OnClickListener listener) {
        removeAttachedView();
        if (!Permissions.checkOverlayPermission(applicationContext)) return;
        Context displayContext = displayContext(displayId);
        WindowManager manager = displayContext.getSystemService(WindowManager.class);
        if (manager == null) return;

        TextView button = new TextView(displayContext);
        button.setText(symbol);
        button.setTextColor(Color.WHITE);
        button.setTextSize("×".equals(symbol) ? 29f : 22f);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        button.setOnClickListener(listener);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(225, 20, 24, 32));
        background.setStroke(dp(2), Color.argb(230, 255, 255, 255));
        button.setBackground(background);
        button.setElevation(dp(12));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(46), dp(46), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = Math.max(0, x);
        params.y = Math.max(0, y);
        try {
            manager.addView(button, params);
            attachedWindowManager = manager;
            attachedView = button;
        } catch (RuntimeException ignored) {
        }
    }

    @NonNull
    private Context displayContext(int displayId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            return applicationContext;
        }
        DisplayManager manager = applicationContext.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        return display == null ? applicationContext
                : applicationContext.createDisplayContext(display);
    }

    private void removeAttachedView() {
        WindowManager manager = attachedWindowManager;
        View view = attachedView;
        attachedWindowManager = null;
        attachedView = null;
        if (manager == null || view == null) return;
        try {
            manager.removeViewImmediate(view);
        } catch (RuntimeException ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(value * applicationContext.getResources()
                .getDisplayMetrics().density);
    }
}
