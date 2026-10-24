/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;
import dezz.status.widget.shell.PrivilegedShell;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Commands the paired Navigator receiver without moving an existing task. */
public final class NavigatorAliceAction {
    static final String PACKAGE = "ru.yandex.yandexnavi";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile NavigatorAliceRequest current;
    private static volatile AliceMusicResume music;
    private static final AtomicLong MANUAL_GENERATION = new AtomicLong();
    private NavigatorAliceAction() {}

    public static void invoke(Context context) {
        Context app = context.getApplicationContext();
        BooleanSupplier manualPermit = musicResumePermit();
        MAIN.post(() -> {
            if ((current != null && current.active()) || (music != null && music.active())) return;
            if (music != null) music.cancel();
            long deadline = SystemClock.elapsedRealtime() + 10000;
            String id = UUID.randomUUID().toString();
            final NavigatorAliceRequest[] holder = new NavigatorAliceRequest[1];
            final android.media.session.MediaController[] previous = new android.media.session.MediaController[1];
            NavigatorAliceRequest.Backend backend = new NavigatorAliceRequest.Backend() {
                public long now() { return SystemClock.elapsedRealtime(); }
                public void later(long delay, Runnable callback) { MAIN.postDelayed(callback, delay); }
                public void send(boolean probe, IntConsumer reply) {
                    if (!holder[0].active()) return;
                    if (probe) { broadcast(command(id, deadline, true), reply); return; }
                    {
                        android.media.session.MediaController playing = previous[0];
                        if (!holder[0].active()) return;
                        final AliceMusicResume[] continuation = new AliceMusicResume[1];
                        continuation[0] = new AliceMusicResume((delay, task) -> MAIN.postDelayed(task, delay),
                                playing == null ? null : () -> AliceMediaPlayback.resume(app, playing,
                                        () -> manualPermit.getAsBoolean() && music == continuation[0]
                                                && continuation[0].mayResume()));
                        music = continuation[0];
                        MAIN.postDelayed(continuation[0]::cancel, 31 * 60 * 1000);
                        ResultReceiver callback = new ResultReceiver(MAIN) {
                            @Override protected void onReceiveResult(int result, Bundle data) {
                                if (music != continuation[0] || !continuation[0].active()) return;
                                continuation[0].event(result);
                                if (result == 33806) Toast.makeText(app,
                                        "Алиса не подтвердила завершение диалога", Toast.LENGTH_LONG).show();
                            }
                        };
                        broadcast(command(id, deadline, false).putExtra("natro_alice_callback", callback), reply);
                    }
                }
                private void broadcast(Intent intent, IntConsumer reply) {
                    try {
                        app.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent i) { reply.accept(getResultCode()); }
                        }, MAIN, 0, null, null);
                    } catch (RuntimeException failure) { reply.accept(0); }
                }
                public void inspect(IntConsumer reply) {
                    // Android 9 app process lists are filtered: only a complete shell inventory
                    // can establish absence. Expired queued reads do not execute.
                    PrivilegedShell.get(app).runCommand(() ->
                            current == holder[0] && now() < deadline
                                    ? "ps -A -o NAME 2>/dev/null && echo NATRO_ALICE_PS_OK" : null,
                            (output, error) -> reply.accept(processState(error == null ? output : null)));
                }
                public boolean launch() {
                    if (!holder[0].active()) return false;
                    Intent intent = app.getPackageManager().getLaunchIntentForPackage(PACKAGE);
                    if (intent == null) return false;
                    try {
                        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return true;
                    } catch (RuntimeException failure) { return false; }
                }
                public void failure(String reason) {
                    if (music != null) music.cancel();
                    Log.w("NatroNavigatorAlice", "request=" + id + " result=" + reason);
                    String message = "receiver_unavailable".equals(reason)
                            ? "Навигатор не готов к вызову Алисы. Нужен патч Natro 3.3.8"
                            : "state_unknown".equals(reason)
                            ? "Не удалось проверить запуск Навигатора; его окно не изменено"
                            : "Не удалось вызвать Алису: Навигатор не подтвердил команду";
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                }
            };
            holder[0] = new NavigatorAliceRequest(backend, deadline);
            current = holder[0];
            // Capture BEFORE even a cold Navigator launch can take audio focus.
            MAIN.postDelayed(() -> {
                if (current == holder[0] && !holder[0].active()) holder[0].expire();
            }, 10000);
            AliceMediaPlayback.capture(app, playing -> {
                if (current != holder[0] || !holder[0].active()) return;
                previous[0] = playing;
                holder[0].start();
            });
        });
    }

    /** A manual transport command supersedes the pending automatic continuation. */
    public static void onManualTransportControl() {
        MANUAL_GENERATION.incrementAndGet();
        AliceMusicResume pending = music;
        if (pending != null) pending.cancel();
    }

    /** Created before asynchronous capture/launch so a manual pause cannot be forgotten. */
    static BooleanSupplier musicResumePermit() {
        long generation = MANUAL_GENERATION.get();
        return () -> generation == MANUAL_GENERATION.get();
    }

    static Intent command(String id, long deadline, boolean probe) {
        return new Intent("dd.call_alice").setPackage(PACKAGE)
                .putExtra("natro_alice_id", id).putExtra("natro_alice_deadline", deadline)
                .putExtra("natro_alice_probe", probe);
    }

    static int processState(String output) {
        if (output == null) return -1;
        String[] lines = output.trim().split("\\r?\\n");
        if (lines.length < 2 || !"NAME".equals(lines[0].trim())
                || !"NATRO_ALICE_PS_OK".equals(lines[lines.length - 1].trim())) return -1;
        boolean running = false;
        for (int i = 1; i < lines.length - 1; i++) {
            String value = lines[i].trim();
            if (value.isEmpty() || value.contains(" ") || value.contains("\t")) return -1;
            if (PACKAGE.equals(value) || value.startsWith(PACKAGE + ":")) running = true;
        }
        // A real system always includes init. A header + marker is not a complete inventory.
        return lines.length > 2 ? (running ? 1 : 0) : -1;
    }
}
