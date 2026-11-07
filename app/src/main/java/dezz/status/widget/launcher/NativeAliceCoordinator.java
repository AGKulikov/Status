/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.media.session.MediaController;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;
import java.util.function.BooleanSupplier;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** One continuation for native and Natro launches. Entry is authenticated by the provider. */
public final class NativeAliceCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile AliceMusicResume pending;
    private static volatile long generation;
    private static MediaController currentPlayer;
    private static BooleanSupplier currentPermit;
    private static String launchId;
    private static MediaController launchPlayer;
    private static BooleanSupplier launchPermit;
    private static long launchDeadline;
    private NativeAliceCoordinator() {}
    private static void journal(String detail) { DiagnosticJournal.infoAsync("alice-resume", detail); }
    static boolean active() { return pending != null && pending.active(); }

    static void rememberLaunch(String id, MediaController player, BooleanSupplier permit, long deadline) {
        launchId = id; launchPlayer = player; launchPermit = permit; launchDeadline = deadline;
    }
    static void forgetLaunch(String id) {
        if (id != null && id.equals(launchId)) {
            launchId = null; launchPlayer = null; launchPermit = null; launchDeadline = 0;
        }
    }
    public static void cancel() {
        MAIN.post(() -> { generation++; if (pending != null) pending.cancel(); });
    }
    public static void prepare(Context context, String id, Bundle extras) {
        try {
            if (id == null || id.length() > 80 || extras == null) return;
            long deadline = extras.getLong("deadline");
            long now = SystemClock.elapsedRealtime();
            if (deadline <= now || deadline - now > 2000) return;
            ResultReceiver reply = extras.getParcelable("reply");
            String origin = extras.getString("origin");
            if (reply == null) return;
            Context app = context.getApplicationContext();
            MAIN.post(() -> begin(app, id, origin, deadline, reply));
        } catch (RuntimeException badParcel) { Log.w("NatroNavigatorAlice", "Bad native prepare parcel"); }
    }
    private static void begin(Context app, String id, String origin, long deadline, ResultReceiver reply) {
        if (SystemClock.elapsedRealtime() >= deadline) return;
        boolean carry = pending != null && pending.active() && currentPlayer != null
                && currentPermit != null && currentPermit.getAsBoolean();
        MediaController carriedPlayer = currentPlayer;
        BooleanSupplier carriedPermit = currentPermit;
        long ticket = ++generation;
        journal("stage=prepare, generation=" + ticket + ", carry=" + carry);
        if (pending != null) pending.cancel();
        BooleanSupplier permit = NavigatorAliceAction.musicResumePermit();
        if (origin != null && origin.equals(launchId) && SystemClock.elapsedRealtime() < launchDeadline) {
            MediaController player = launchPlayer;
            BooleanSupplier saved = launchPermit;
            forgetLaunch(origin);
            ready(app, id, ticket, deadline, reply, player, saved);
        } else if (carry) {
            ready(app, id, ticket, deadline, reply, carriedPlayer, carriedPermit);
        } else {
            AliceMediaPlayback.capture(app, player -> ready(app, id, ticket, deadline, reply, player, permit));
        }
    }
    private static void ready(Context app, String id, long ticket, long deadline, ResultReceiver reply,
                              MediaController player, BooleanSupplier permit) {
        if (ticket != generation || SystemClock.elapsedRealtime() >= deadline) return;
        final java.util.concurrent.atomic.AtomicLong lastEvent = new java.util.concurrent.atomic.AtomicLong();
        final AliceMusicResume[] holder = new AliceMusicResume[1];
        holder[0] = new AliceMusicResume((delay, task) -> MAIN.postDelayed(task, delay),
                player == null ? null : () -> {
                    long dispatch = holder[0].revision();
                    journal("stage=timer_elapsed, generation=" + ticket + ", revision=" + dispatch);
                    AliceMediaPlayback.resume(app, player,
                        () -> ticket == generation && pending == holder[0]
                                && permit.getAsBoolean() && holder[0].mayResume()
                                && holder[0].revision() == dispatch
                                && SystemClock.elapsedRealtime() - lastEvent.get() < 750,
                        played -> {
                            journal("stage=play_result, generation=" + ticket + ", sent=" + played);
                            holder[0].complete(dispatch, played);
                        });
                }, true);
        pending = holder[0];
        journal("stage=prepared, generation=" + ticket + ", previous_playing=" + (player != null));
        currentPlayer = player; currentPermit = permit;
        final long[] sequence = {0};
        final int[] lastCode = {0};
        ResultReceiver events = new ResultReceiver(MAIN) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (ticket != generation || pending != holder[0] || data == null) return;
                try {
                    long seq = data.getLong("sequence");
                    if (!id.equals(data.getString("id")) || seq <= sequence[0]) return;
                    sequence[0] = seq;
                    long at = data.getLong("elapsed");
                    long age = SystemClock.elapsedRealtime() - at;
                    if (age < 0 || age > 750) { holder[0].cancel(); return; }
                    lastEvent.set(at);
                    if (code != 33804 && code != 33805 && code != 33806) { holder[0].cancel(); return; }
                    holder[0].event(code);
                    if (lastCode[0] != code) Log.i("NatroNavigatorAlice", "native=" + id + " event=" + code
                            + " seq=" + seq + " source_elapsed=" + data.getLong("elapsed"));
                    if (lastCode[0] != code) journal("stage=engine, generation=" + ticket + ", event=" + code
                            + ", source_elapsed=" + at + ", continuation_active=" + holder[0].active());
                    lastCode[0] = code;
                } catch (RuntimeException malformed) { holder[0].cancel(); }
            }
        };
        Bundle answer = new Bundle();
        answer.putParcelable("events", framework(events));
        reply.send(34101, answer);
        MAIN.postDelayed(() -> {
            // A prepare ACK alone is not proof that Alice started or finished.
            if (ticket == generation && sequence[0] == 0) holder[0].cancel();
        }, 10000);
        MAIN.postDelayed(new Runnable() {
            @Override public void run() {
                if (ticket != generation || pending != holder[0] || !holder[0].active()) return;
                long seen = lastEvent.get();
                if (sequence[0] != 0 && SystemClock.elapsedRealtime() - seen > 1500) {
                    journal("stage=cancel, reason=engine_heartbeat_lost, generation=" + ticket);
                    holder[0].cancel();
                    return;
                }
                MAIN.postDelayed(this, 500);
            }
        }, 500);
        MAIN.postDelayed(holder[0]::cancel, 31 * 60 * 1000);
    }
    private static ResultReceiver framework(ResultReceiver local) {
        Parcel parcel = Parcel.obtain();
        try {
            local.writeToParcel(parcel, 0); parcel.setDataPosition(0);
            return ResultReceiver.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
}
