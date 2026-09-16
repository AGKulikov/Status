/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.concurrent.atomic.AtomicBoolean;
import dezz.status.widget.shell.PrivilegedShell;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** One restore transaction and at most one XSF restart per application startup. */
final class ButtonRouteRestorer {
    private static final AtomicBoolean started = new AtomicBoolean();
    static void restore(Context context) {
        if (!started.compareAndSet(false, true)) return;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Context storage = app.createDeviceProtectedStorageContext();
            SharedPreferences media = storage.getSharedPreferences("media_buttons", Context.MODE_PRIVATE);
            SharedPreferences vehicle = storage.getSharedPreferences("vehicle_buttons", Context.MODE_PRIVATE);
            java.util.List<VehicleButton> requested = new java.util.ArrayList<>();
            if (media.getBoolean("disable_default", false)) requested.add(VehicleButton.MEDIA);
            for (VehicleButton b : VehicleButton.values())
                if (b != VehicleButton.MEDIA && b != VehicleButton.STAR
                        && vehicle.getBoolean(b.key + ".disable_default", false)) requested.add(b);
            if (requested.isEmpty()) return;
            MediaButtonController m = MediaButtonController.get(app);
            VehicleButtonController v = VehicleButtonController.get(app);
            m.whenInputReady(() -> v.whenInputReady(() -> {
                if (requested.contains(VehicleButton.MEDIA) && !m.beginStoredRestore()) requested.remove(VehicleButton.MEDIA);
                v.beginStoredRestore(requested, () -> {
                    if (requested.isEmpty()) return;
                    StringBuilder command = new StringBuilder("CLASSPATH=")
                            .append(MediaButtonController.quote(app.getApplicationInfo().sourceDir))
                            .append(" app_process /system/bin dezz.status.widget.media.MediaInputPatchMain restore");
                    for (VehicleButton b : requested) command.append(' ').append(b.name());
                    PrivilegedShell.get(app).runCommand(MediaButtonController.rootCommand(command.toString()), (out, error) -> {
                        if (requested.contains(VehicleButton.MEDIA)) m.finishStoredRestore(out, error);
                        v.finishStoredRestore(requested, out, error);
                        DiagnosticJournal.infoAsync("vehicle-buttons", "startup_routes_finished uptime_ms="
                                + android.os.SystemClock.uptimeMillis() + ", buttons=" + requested
                                + ", transport_ok=" + (error == null));
                    });
                });
            }));
        }, "natro-button-route-restore").start();
    }
}
