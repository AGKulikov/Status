/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import dezz.status.widget.shell.PrivilegedShell;

/** Calls the Navigator mod receiver without moving an existing task. */
public final class NavigatorAliceAction {
    static final String PACKAGE = "ru.yandex.yandexnavi";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long generation;
    private NavigatorAliceAction() {}

    public static void invoke(Context context) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            long token = ++generation;
            long deadline = SystemClock.uptimeMillis() + 10000;
            // App-level process lists are filtered on Android 9; absence there is not proof.
            PrivilegedShell.get(app).runCommand(() -> {
                if (token != generation || SystemClock.uptimeMillis() > deadline) return null;
                return "ps -A -o NAME 2>/dev/null && echo NATRO_ALICE_PS_OK";
            }, (output, error) -> {
                if (token != generation || SystemClock.uptimeMillis() > deadline) return;
                int state = processState(error == null ? output : null);
                if (state < 0) {
                    // Unknown state must never bring a background task to the foreground.
                    send(app);
                } else if (state > 0) {
                    send(app);
                } else {
                    Intent launch = app.getPackageManager().getLaunchIntentForPackage(PACKAGE);
                    if (launch == null) { unavailable(app); return; }
                    try {
                        app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        MAIN.postDelayed(() -> {
                            if (token == generation && SystemClock.uptimeMillis() <= deadline)
                                send(app);
                        }, 1500);
                    } catch (RuntimeException failure) { unavailable(app); }
                }
            });
        });
    }

    static int processState(String output) {
        if (output == null) return -1;
        // Parse exact process names, including Navigator's secondary processes.
        boolean header = false, complete = false, running = false;
        for (String line : output.split("\\r?\\n")) {
            String value = line.trim();
            if ("NAME".equals(value)) header = true;
            if ("NATRO_ALICE_PS_OK".equals(value)) complete = true;
            if (PACKAGE.equals(value) || value.startsWith(PACKAGE + ":")) running = true;
        }
        return header && complete ? (running ? 1 : 0) : -1;
    }
    private static void send(Context app) {
        try { app.sendBroadcast(new Intent("dd.call_alice").setPackage(PACKAGE)); }
        catch (RuntimeException failure) { unavailable(app); }
    }
    private static void unavailable(Context app) {
        Toast.makeText(app, "Не удалось вызвать Алису в Навигаторе", Toast.LENGTH_SHORT).show();
    }
}
