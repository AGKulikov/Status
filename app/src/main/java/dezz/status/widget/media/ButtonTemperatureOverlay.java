/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.Collections;
import java.util.Locale;
import dezz.status.widget.car.CarIntegration;
import dezz.status.widget.car.CarIntegrations;

/** Independently toggled, movable cabin-temperature overlay; subscriptions exist only while shown. */
final class ButtonTemperatureOverlay {
    private final Context context;
    private final VehicleButtonController settings;
    private final Handler main;
    private TextView view;
    private WindowManager manager;
    private final CarIntegration.TelemetryListener listener = new CarIntegration.TelemetryListener() {
        @Override public void onTelemetry(CarIntegration.TelemetryValue value) {
            if (view != null) view.setText(String.format(Locale.ROOT, "%.1f°", value.value));
        }
        @Override public void onTelemetryUnavailable(String id) { if (view != null) view.setText("—°"); }
    };
    ButtonTemperatureOverlay(Context context, VehicleButtonController settings) {
        this.context = context; this.settings = settings; main = new Handler(context.getMainLooper());
    }
    void restore() { if (settings.bool("temperature.visible")) main.post(this::show); }
    void toggle() { main.post(() -> {
        if (view != null) { hide(); settings.put("temperature.visible", false); }
        else if (!Settings.canDrawOverlays(context)) {
            context.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } else { show(); if (view != null) settings.put("temperature.visible", true); }
    }); }
    private void show() {
        if (view != null || !Settings.canDrawOverlays(context)) return;
        manager = context.getSystemService(WindowManager.class);
        if (manager == null) return;
        TextView candidate = new TextView(context);
        candidate.setText("—°"); candidate.setTextSize(22); candidate.setTextColor(-9322241);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, 264, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = settings.integer("temperature.x", 0); params.y = settings.integer("temperature.y", 100);
        candidate.setOnTouchListener(new android.view.View.OnTouchListener() {
            float startX, startY; int x, y;
            @Override public boolean onTouch(android.view.View ignored, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    startX = event.getRawX(); startY = event.getRawY(); x = params.x; y = params.y;
                } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    params.x = x + Math.round(event.getRawX() - startX);
                    params.y = y + Math.round(event.getRawY() - startY);
                    try { manager.updateViewLayout(candidate, params); } catch (RuntimeException lost) { hide(); }
                } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    settings.put("temperature.x", params.x); settings.put("temperature.y", params.y);
                    candidate.performClick();
                }
                return true;
            }
        });
        try {
            manager.addView(candidate, params); view = candidate;
            CarIntegrations.get(context).subscribeTelemetry(Collections.singleton("indoor_temp"), listener);
        } catch (RuntimeException failed) { hide(); }
    }
    private void hide() {
        CarIntegrations.get(context).unsubscribeTelemetry(listener);
        TextView old = view; view = null;
        if (manager != null && old != null) try { manager.removeView(old); } catch (RuntimeException ignored) { }
    }
}
