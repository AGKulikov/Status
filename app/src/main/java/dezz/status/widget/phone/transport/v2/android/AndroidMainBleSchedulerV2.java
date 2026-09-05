/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Objects;

import dezz.status.widget.phone.transport.v2.IphoneDualTransportRuntimeV2;

/**
 * Process-wide FIFO for the role-switch coordinator and its durable write-ahead snapshots.
 *
 * <p>The Android adapters still serialize every framework/GATT operation on the main looper.
 * Coordinator state, however, can call synchronous {@code SharedPreferences.commit()} before a
 * Bluetooth effect is released. Field watchdogs captured that fsync blocking the KX11 main
 * thread for almost ten seconds. A single process-lifetime HandlerThread preserves the exact
 * commit-before-effect order without freezing UI, display or Bluetooth callback deadlines.</p>
 */
public final class AndroidMainBleSchedulerV2
        implements IphoneDualTransportRuntimeV2.SerializedScheduler {
    private static final Object SHARED_LOCK = new Object();
    private static Handler sharedHandler;

    private final Handler fifo;

    public AndroidMainBleSchedulerV2() {
        this(sharedFifo());
    }

    AndroidMainBleSchedulerV2(Handler fifo) {
        this.fifo = Objects.requireNonNull(fifo, "fifo");
    }

    private static Handler sharedFifo() {
        synchronized (SHARED_LOCK) {
            if (sharedHandler == null) {
                HandlerThread thread = new HandlerThread("NatroAncsCoordinator");
                thread.start();
                sharedHandler = new Handler(thread.getLooper());
            }
            return sharedHandler;
        }
    }

    @Override public long nowMillis() {
        return SystemClock.elapsedRealtime();
    }

    @Override public boolean isCurrent() {
        return Looper.myLooper() == fifo.getLooper();
    }

    /** Always posts so an EffectsPort callback cannot re-enter a coordinator commit batch. */
    @Override public void execute(Runnable action) {
        fifo.post(Objects.requireNonNull(action, "action"));
    }

    @Override public IphoneDualTransportRuntimeV2.Cancellable scheduleAt(
            long absoluteDeadlineMillis, Runnable action) {
        Objects.requireNonNull(action, "action");
        long now = SystemClock.elapsedRealtime();
        long delay = absoluteDeadlineMillis > now ? absoluteDeadlineMillis - now : 0L;
        fifo.postDelayed(action, delay);
        return () -> fifo.removeCallbacks(action);
    }
}
