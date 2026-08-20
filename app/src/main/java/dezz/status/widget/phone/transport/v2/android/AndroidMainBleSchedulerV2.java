/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dezz.status.widget.phone.transport.v2.IphoneDualTransportRuntimeV2;
import java.util.Objects;

/** Main-FIFO scheduler shared by both Android BLE framework adapters and the switch runtime. */
public final class AndroidMainBleSchedulerV2
        implements IphoneDualTransportRuntimeV2.SerializedScheduler {
    private final Handler main;

    public AndroidMainBleSchedulerV2() {
        this(new Handler(Looper.getMainLooper()));
    }

    AndroidMainBleSchedulerV2(Handler main) {
        this.main = Objects.requireNonNull(main, "main");
    }

    @Override public long nowMillis() {
        return SystemClock.elapsedRealtime();
    }

    @Override public boolean isCurrent() {
        return Looper.myLooper() == main.getLooper();
    }

    /** Always posts, even from main, so an EffectsPort callback cannot re-enter a commit batch. */
    @Override public void execute(Runnable action) {
        main.post(Objects.requireNonNull(action, "action"));
    }

    @Override public IphoneDualTransportRuntimeV2.Cancellable scheduleAt(
            long absoluteDeadlineMillis, Runnable action) {
        Objects.requireNonNull(action, "action");
        long now = SystemClock.elapsedRealtime();
        long delay = absoluteDeadlineMillis > now ? absoluteDeadlineMillis - now : 0L;
        main.postDelayed(action, delay);
        return () -> main.removeCallbacks(action);
    }
}
