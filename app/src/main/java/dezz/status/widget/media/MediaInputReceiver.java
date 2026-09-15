/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cold-process delivery for Natro's private XSF input address; never receives stock MEDIA. */
public final class MediaInputReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !MediaKeyPolicy.NATRO.equals(intent.getAction())) return;
        PendingResult result = goAsync();
        Handler main = new Handler(Looper.getMainLooper());
        AtomicBoolean complete = new AtomicBoolean();
        Runnable finish = () -> { if (complete.compareAndSet(false, true)) result.finish(); };
        // A stalled external AudioManager Binder must not hold Android's broadcast queue for 10s.
        main.postDelayed(finish, 1500);
        try {
            MediaButtonController.get(context).receive(intent, () -> {
                finish.run(); main.removeCallbacks(finish);
            });
        } catch (RuntimeException failed) { finish.run(); main.removeCallbacks(finish); }
    }
}
