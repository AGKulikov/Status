/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import dezz.status.widget.shell.PrivilegedShell;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Serialized route restoration; bounded transport retries and explicit package-replacement recovery. */
final class ButtonRouteRestorer {
    private static final ButtonRestoreGate gate = new ButtonRestoreGate();
    private static final Handler retry = new Handler(Looper.getMainLooper());
    static void restore(Context context) { restore(context, false); }
    static void restore(Context context, boolean replacement) {
        if (!gate.begin(replacement)) return;
        Context app = context.getApplicationContext();
        DiagnosticJournal.infoAsync("vehicle-buttons", "routes_restore_started reason="
                + (replacement ? "package_replaced" : "startup_or_retry") + ", attempt=" + gate.attempts());
        new Thread(() -> {
          try {
            Context storage = app.createDeviceProtectedStorageContext();
            SharedPreferences media = dezz.status.widget.backup.BackupPreferences.open(storage,"media_buttons", Context.MODE_PRIVATE);
            SharedPreferences vehicle = dezz.status.widget.backup.BackupPreferences.open(storage,"vehicle_buttons", Context.MODE_PRIVATE);
            java.util.List<VehicleButton> requested = new java.util.ArrayList<>();
            if (media.getBoolean("disable_default", false)
                    &&!dezz.status.widget.backup.BackupMaintenance.systemOperationNeedsReview(app,VehicleButton.MEDIA.name())) requested.add(VehicleButton.MEDIA);
            for (VehicleButton b : VehicleButton.values())
                if (b != VehicleButton.MEDIA && b != VehicleButton.STAR
                        && vehicle.getBoolean(b.key + ".disable_default", false)
                        &&!dezz.status.widget.backup.BackupMaintenance.systemOperationNeedsReview(app,b.name())) requested.add(b);
            if (requested.isEmpty()) { completed(app, true); return; }
            MediaButtonController m = MediaButtonController.get(app);
            VehicleButtonController v = VehicleButtonController.get(app);
            m.whenInputReady(() -> v.whenInputReady(() -> {
                if (requested.contains(VehicleButton.MEDIA) && !m.beginStoredRestore()) requested.remove(VehicleButton.MEDIA);
                v.beginStoredRestore(requested, () -> {
                    if (requested.isEmpty()) { completed(app, true); return; }
                    StringBuilder command = new StringBuilder("CLASSPATH=")
                            .append(MediaButtonController.quote(app.getApplicationInfo().sourceDir))
                            .append(" app_process /system/bin dezz.status.widget.media.MediaInputPatchMain restore");
                    for (VehicleButton b : requested) command.append(' ').append(b.name());
                    PrivilegedShell.get(app).runCommand(MediaButtonController.rootCommand(command.toString()), (out, error) -> {
                        if (requested.contains(VehicleButton.MEDIA)) m.finishStoredRestore(out, error);
                        v.finishStoredRestore(requested, out, error);
                        // Restart the reader after an XSF route transaction, rejecting queued old input.
                        v.reconnectAfterUpdate("routes_finished");
                        completed(app, error == null && out != null && out.contains("NATRO_MEDIA_ROUTE=")
                                && !out.contains("NATRO_MEDIA_ERROR="));
                        DiagnosticJournal.infoAsync("vehicle-buttons", "startup_routes_finished uptime_ms="
                                + android.os.SystemClock.uptimeMillis() + ", buttons=" + requested
                                + ", transport_ok=" + (error == null));
                    });
                });
            }));
          } catch (RuntimeException failure) {
            DiagnosticJournal.warn("vehicle-buttons", "routes_restore_failed=" + failure.getClass().getSimpleName());
            completed(app, false);
          }
        }, "natro-button-route-restore").start();
    }
    private static void completed(Context app, boolean success) {
        ButtonRestoreGate.Next next = gate.finish(success);
        DiagnosticJournal.infoAsync("vehicle-buttons", "routes_restore_complete success=" + success
                + ", attempt=" + gate.attempts() + ", next=" + next + ", physical_effect=unobserved");
        if (next == ButtonRestoreGate.Next.FORCE) retry.post(() -> restore(app, true));
        else if (next == ButtonRestoreGate.Next.RETRY)
            retry.postDelayed(() -> restore(app, false), gate.attempts() == 1 ? 5000 : 15000);
    }
}
