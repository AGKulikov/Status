/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;

import dezz.status.widget.PassengerLauncherActivity;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** A distinct task on the passenger screen. Never launches HOME through the global resolver. */
public final class PassengerHomeLauncher {
    public static final int DISPLAY_ID = 3;
    private PassengerHomeLauncher() {}

    public static boolean available(Context context) {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(DISPLAY_ID);
        return display != null && display.isValid() && display.getState() != Display.STATE_OFF;
    }

    public static Bundle options(Context context, Bundle original) {
        if (!available(context)) throw new IllegalStateException("Passenger display unavailable");
        Bundle result = original == null ? new Bundle() : new Bundle(original);
        result.putAll(ActivityOptions.makeBasic().setLaunchDisplayId(DISPLAY_ID).toBundle());
        return result;
    }

    public static boolean open(Context context) {
        Context app = context.getApplicationContext();
        try {
            Intent intent = new Intent(app, PassengerLauncherActivity.class)
                    .setAction(Intent.ACTION_MAIN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            app.startActivity(intent, options(app, null));
            DiagnosticJournal.infoAsync("passenger-home", "launch_submitted display=3; pixels=unobserved");
            return true;
        } catch (RuntimeException error) {
            reportFailure(context, error);
            return false;
        }
    }

    public static void reportFailure(Context context, RuntimeException error) {
        DiagnosticJournal.warn("passenger-home", "launch_failed=" + error.getClass().getSimpleName());
        Context app = context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app,
                "Не удалось открыть окно пассажира. Проверьте, включён ли экран.",
                Toast.LENGTH_LONG).show());
    }
}
