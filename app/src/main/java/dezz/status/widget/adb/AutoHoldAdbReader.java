/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.adb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dezz.status.widget.Preferences;
import dezz.status.widget.car.*;
import dezz.status.widget.diagnostics.DiagnosticJournal;
import dezz.status.widget.shell.AdbTransport;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.*;

/** Optional passive AutoHold capture; closes only its own ADB/socket and tcpdump child. */
public final class AutoHoldAdbReader {
    private static AutoHoldAdbReader instance;
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "natro-autohold-adb"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile long generation;
    private volatile Socket socket;
    private Future<?> task;
    private volatile boolean drive, stopped;
    private volatile long speedAt, gearAt;
    private long lastTrue, lastPublish;
    private Boolean lastState;
    private final CarIntegration.TelemetryListener telemetry = value -> {
        long now = SystemClock.elapsedRealtime();
        if (value.id.equals("ISensor.speed")) { stopped = (int) (Math.abs(value.value) * 3.72) == 0; speedAt = now; }
        else if (value.id.equals("ISensor.gear")) {
            int gear = (int) value.value;
            drive = gear == 2097696 || gear >= 2097665 && gear <= 2097674 || gear <= -10001 && gear >= -10010; gearAt = now;
        }
    };
    private AutoHoldAdbReader(Context context) { this.context = context.getApplicationContext(); }
    public static synchronized void reconcile(Context context) {
        if (instance == null) instance = new AutoHoldAdbReader(context);
        instance.restart();
    }
    private synchronized void restart() {
        long owner = ++generation;
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
        if (task != null) task.cancel(true);
        Preferences prefs = new Preferences(context);
        boolean enabled = prefs.adbAutoHoldCapture.get(), snapshot = prefs.adbAutoHoldSnapshot.get(), shell = prefs.adbAutoHoldUseShell.get();
        main.post(() -> {
            if (owner != generation) return;
            CarIntegration car = CarIntegrations.get(context);
            car.unsubscribeTelemetry(telemetry);
            if (enabled) car.subscribeTelemetry(new HashSet<>(Arrays.asList("ISensor.speed", "ISensor.gear")), telemetry);
        });
        AutoHoldStateRepository.invalidateCapture(context);
        if (enabled) task = worker.submit(() -> capture(owner, snapshot, shell));
    }
    private void capture(long owner, boolean snapshot, boolean shell) {
        lastState = null; lastTrue = lastPublish = 0;
        while (owner == generation && !Thread.currentThread().isInterrupted()) {
            AdbTransport adb = null;
            try {
                for (int port : new int[]{5555, 7777}) {
                    if (owner != generation) return;
                    if (!AdbTransport.probe("127.0.0.1", port, value -> takeSocket(owner, value))) continue;
                    try { adb = AdbTransport.connect(context, "127.0.0.1", port, value -> takeSocket(owner, value)); break; }
                    catch (Exception unavailable) { if (port == 7777) throw unavailable; }
                }
                if (adb == null) throw new IllegalStateException("Нет локального ADB");
                if (owner != generation) return;
                String capture = "tcpdump -i eth0 -s 192 -w - -U " + (snapshot ? "-c 1 " : "")
                        + "'udp and src port 50500 and dst port 50335 and udp[8:2]=0x0026 and udp[10:2]=0x0020'";
                // No global pkill, no shared capture file. The shell owns just this child and
                // reaps it on HUP/TERM/EXIT. A bounded 15 s capture also limits orphan lifetime.
                String owned = "trap 'test -z \"$natro_child\" || kill \"$natro_child\" 2>/dev/null' EXIT HUP INT TERM; "
                        + "timeout 15 " + capture + " 2>/dev/null & natro_child=$!; wait \"$natro_child\"";
                AutoHoldPcap pcap = new AutoHoldPcap(value -> sample(owner, value));
                adb.readService("exec:" + (shell ? "sh -c " : "su 0 sh -c ") + AdbShellResult.quote(owned), pcap::accept);
                if (owner == generation && SystemClock.elapsedRealtime() - lastPublish > 5000) AutoHoldStateRepository.invalidateCapture(context);
            } catch (Exception error) {
                if (owner == generation) {
                    AutoHoldStateRepository.invalidateCapture(context);
                    DiagnosticJournal.warn("adb", "AutoHold capture unavailable: " + error.getClass().getSimpleName());
                }
            } finally { if (adb != null) adb.close(); }
            try { Thread.sleep(snapshot ? 150 : 2000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }
    private void takeSocket(long owner, Socket value) {
        socket = value; if (owner != generation) try { value.close(); } catch (Exception ignored) {}
    }
    private void sample(long owner, boolean bit) {
        if (owner != generation) return;
        long now = SystemClock.elapsedRealtime();
        if (speedAt == 0 || gearAt == 0 || now - speedAt > 5000) { AutoHoldStateRepository.invalidateCapture(context); return; }
        boolean value = bit && drive && stopped;
        if (value) lastTrue = now;
        else if (drive && stopped && now - lastTrue <= 1111) return;
        if (lastState == null || lastState != value || now - lastPublish >= 1000) {
            lastState = value; lastPublish = now; AutoHoldStateRepository.acceptCapture(context, value);
        }
    }
}
