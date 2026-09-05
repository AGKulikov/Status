/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Process-wide serialization gate for every v2 Android GATT-client registration.
 *
 * <p>Android P registers {@code clientIf} asynchronously. Closing a Java wrapper before its first
 * callback cannot prove that a late registration will not connect. Consequently Route A's public
 * client and Route B's hidden opportunistic observer share one exact-identity registration lease.
 * A lease is released only after a registration-proving callback followed by close, a synchronous
 * creation failure, or an explicit Bluetooth radio reset.</p>
 *
 * <p>A drain reservation is deliberately distinct from the actual registration lease. When the
 * lease becomes free, a queued drain waiter receives an atomic reservation before its callback is
 * dispatched. Normal acquisition is forbidden until that exact drain owner releases or cancels
 * the reservation. Normal waiters queued before or during that fenced drain are invalidated; a
 * target activation is constructed only after source disposal and must acquire afresh. This makes
 * the observed zero-owner interval durable across asynchronous coordinator callbacks instead of
 * relying on a racy {@code isHeld() == false} snapshot.</p>
 */
final class ProcessGattRegistrationGateV2 {
    private static final Object LOCK = new Object();

    private static final class Waiter {
        final Object exactKey;
        final Runnable callback;
        final boolean drain;

        Waiter(Object exactKey, Runnable callback, boolean drain) {
            this.exactKey = exactKey;
            this.callback = callback;
            this.drain = drain;
        }
    }

    private static final List<Waiter> WAITERS = new ArrayList<>();
    private static Object leaseOwner;
    private static Object drainReservation;

    private ProcessGattRegistrationGateV2() {
    }

    static boolean tryAcquire(Object exactOwner) {
        Objects.requireNonNull(exactOwner, "exactOwner");
        synchronized (LOCK) {
            if (drainReservation != null) return false;
            if (leaseOwner != null && leaseOwner != exactOwner) return false;
            leaseOwner = exactOwner;
            removeWaiterLocked(exactOwner);
            return true;
        }
    }

    static boolean owns(Object exactOwner) {
        synchronized (LOCK) {
            return leaseOwner == exactOwner;
        }
    }

    /** Counts only a real registration lease, never a zero-owner drain reservation. */
    static boolean isHeld() {
        synchronized (LOCK) {
            return leaseOwner != null;
        }
    }

    /** Blocks new registration for either a real lease or an atomic drain reservation. */
    static boolean acquisitionBlocked() {
        synchronized (LOCK) {
            return leaseOwner != null || drainReservation != null;
        }
    }

    static boolean ownsDrainReservation(Object exactWaiter) {
        synchronized (LOCK) {
            return drainReservation == exactWaiter;
        }
    }

    static void whenFree(Object exactWaiter, Runnable callback) {
        enqueueOrRun(exactWaiter, callback, false);
    }

    /**
     * Queues an exact drain owner, or atomically reserves an already-free gate for it.
     *
     * <p>Drain waiters have priority over normal waiters regardless of insertion order, while
     * preserving FIFO order among drain waiters.</p>
     */
    static void whenFreeForDrain(Object exactWaiter, Runnable callback) {
        enqueueOrRun(exactWaiter, callback, true);
    }

    private static void enqueueOrRun(
            Object exactWaiter, Runnable callback, boolean drain) {
        Objects.requireNonNull(exactWaiter, "exactWaiter");
        Objects.requireNonNull(callback, "callback");
        Runnable ready = null;
        synchronized (LOCK) {
            if (drainReservation == exactWaiter) return;
            if (leaseOwner == null && drainReservation == null) {
                if (drain) {
                    drainReservation = exactWaiter;
                    removeWaiterLocked(exactWaiter);
                    removeNormalWaitersLocked();
                    ready = callback;
                } else if (!hasDrainWaiterLocked()) {
                    removeWaiterLocked(exactWaiter);
                    ready = callback;
                } else {
                    putWaiterLocked(exactWaiter, callback, false);
                    ready = promoteLocked();
                }
            } else {
                putWaiterLocked(exactWaiter, callback, drain);
            }
        }
        run(ready);
    }

    /** Cancels a queued waiter and, if exact, its active drain reservation. */
    static void cancelWaiter(Object exactWaiter) {
        if (exactWaiter == null) return;
        List<Runnable> ready = null;
        synchronized (LOCK) {
            removeWaiterLocked(exactWaiter);
            if (drainReservation == exactWaiter) {
                drainReservation = null;
                removeNormalWaitersLocked();
                ready = promoteAllLocked();
            }
        }
        runAll(ready);
    }

    static void releaseDrainReservation(Object exactWaiter) {
        if (exactWaiter == null) return;
        List<Runnable> ready = null;
        synchronized (LOCK) {
            if (drainReservation != exactWaiter) return;
            drainReservation = null;
            // Every acquisition queued before or during this drain belongs to the fenced source
            // epoch. The target is constructed only after disposal and must acquire afresh.
            removeNormalWaitersLocked();
            ready = promoteAllLocked();
        }
        runAll(ready);
    }

    static void release(Object exactOwner) {
        if (exactOwner == null) return;
        List<Runnable> ready = null;
        synchronized (LOCK) {
            if (leaseOwner != exactOwner) return;
            leaseOwner = null;
            ready = promoteAllLocked();
        }
        runAll(ready);
    }

    /**
     * Bluetooth power loss is the sole alternate proof that the process clientIf is gone.
     *
     * <p>Normal acquisition waiters are stale across a radio epoch and are discarded. An existing
     * drain reservation remains authoritative; otherwise the first queued drain waiter receives
     * the reservation atomically.</p>
     */
    static void radioReset() {
        List<Runnable> ready = null;
        synchronized (LOCK) {
            leaseOwner = null;
            removeNormalWaitersLocked();
            if (drainReservation == null) ready = promoteAllLocked();
        }
        runAll(ready);
    }

    /** Must be called with {@link #LOCK}; returns the first drain callback, if any. */
    private static Runnable promoteLocked() {
        if (leaseOwner != null || drainReservation != null) return null;
        for (int index = 0; index < WAITERS.size(); index++) {
            Waiter waiter = WAITERS.get(index);
            if (!waiter.drain) continue;
            WAITERS.remove(index);
            drainReservation = waiter.exactKey;
            removeNormalWaitersLocked();
            return waiter.callback;
        }
        return null;
    }

    /**
     * Must be called with {@link #LOCK}. Drain gets one atomic callback; otherwise all normal
     * callbacks are released so a stale first waiter cannot strand the free gate.
     */
    private static List<Runnable> promoteAllLocked() {
        Runnable drain = promoteLocked();
        if (drain != null) {
            List<Runnable> one = new ArrayList<>(1);
            one.add(drain);
            return one;
        }
        if (leaseOwner != null || drainReservation != null || WAITERS.isEmpty()) return null;
        List<Runnable> normal = new ArrayList<>();
        for (int index = 0; index < WAITERS.size();) {
            Waiter waiter = WAITERS.get(index);
            if (waiter.drain) {
                index++;
                continue;
            }
            WAITERS.remove(index);
            normal.add(waiter.callback);
        }
        return normal.isEmpty() ? null : normal;
    }

    private static boolean hasDrainWaiterLocked() {
        for (Waiter waiter : WAITERS) {
            if (waiter.drain) return true;
        }
        return false;
    }

    private static void putWaiterLocked(Object exactKey, Runnable callback, boolean drain) {
        for (int index = 0; index < WAITERS.size(); index++) {
            if (WAITERS.get(index).exactKey == exactKey) {
                WAITERS.set(index, new Waiter(exactKey, callback, drain));
                return;
            }
        }
        WAITERS.add(new Waiter(exactKey, callback, drain));
    }

    private static void removeWaiterLocked(Object exactKey) {
        for (int index = 0; index < WAITERS.size(); index++) {
            if (WAITERS.get(index).exactKey == exactKey) {
                WAITERS.remove(index);
                return;
            }
        }
    }

    private static void removeNormalWaitersLocked() {
        for (int index = 0; index < WAITERS.size();) {
            if (WAITERS.get(index).drain) {
                index++;
            } else {
                WAITERS.remove(index);
            }
        }
    }

    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }

    private static void runAll(List<Runnable> callbacks) {
        if (callbacks == null) return;
        for (Runnable callback : callbacks) callback.run();
    }
}
