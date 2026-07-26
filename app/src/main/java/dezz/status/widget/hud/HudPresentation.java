/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;

/** Actual external-display window. The main display owns only the editor. */
final class HudPresentation extends Presentation {
    @NonNull private HudPanelConfig config;
    @NonNull private final HudRuntimeData data;
    private HudCanvasView canvas;

    HudPresentation(@NonNull Context context, @NonNull Display display,
                    @NonNull HudPanelConfig config, @NonNull HudRuntimeData data) {
        super(context, display);
        this.config = config;
        this.data = data;
        setCancelable(false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0f);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
        canvas = new HudCanvasView(getContext(), config, data, false, null);
        setContentView(canvas);
    }

    void updateConfig(@NonNull HudPanelConfig next) {
        config = next;
        if (canvas != null) canvas.updateConfig(next);
    }

    void invalidateHud() {
        if (canvas != null) canvas.invalidate();
    }
}
