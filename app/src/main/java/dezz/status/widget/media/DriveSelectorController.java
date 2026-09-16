/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import dezz.status.widget.drivemode.knob.KnobReceiver;
import dezz.status.widget.drivemode.service.DriveModeOverlayService;

/** Typed route for seven ready-made actions; never broadcasts to another app. */
public final class DriveSelectorController {
    private static volatile boolean showing;
    public static boolean isShowing() { return showing; }
    public static void setShowing(boolean value) { showing = value; }
    public static void request(Context context, int steps) {
        if (steps < -3 || steps > 3) throw new IllegalArgumentException("Invalid drive steps");
        Context app = context.getApplicationContext();
        ButtonActionDeadline deadline = ButtonActionDeadline.current();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (!deadline.valid()) return;
            if (!Settings.canDrawOverlays(app)) {
                Toast.makeText(app, "Разрешите Natro показ поверх других приложений", Toast.LENGTH_LONG).show(); return;
            }
            Intent service = new Intent(app, DriveModeOverlayService.class);
            service.putExtra(DriveModeOverlayService.EXTRA_ACTION_DEADLINE, deadline.expiresAt);
            service.setAction(steps == 0 ? DriveModeOverlayService.ACTION_SHOW_PREVIEW : DriveModeOverlayService.ACTION_KNOB_STEP);
            service.putExtra(KnobReceiver.EXTRA_STEPS, Math.abs(steps));
            service.putExtra(KnobReceiver.EXTRA_DIRECTION, steps < 0 ? KnobReceiver.DIRECTION_PREV : KnobReceiver.DIRECTION_NEXT);
            try { app.startForegroundService(service); }
            catch (RuntimeException unavailable) { Toast.makeText(app, "Селектор недоступен: " + unavailable.getMessage(), Toast.LENGTH_LONG).show(); }
        });
    }
}
