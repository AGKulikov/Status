/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Host behavior tests for cross-runtime GATT registration quarantine and drain reservation. */
public final class ProcessGattRegistrationGateV2Test {
    @Test public void recreateCannotAcquireUntilOldExactRegistrationReleases() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldRuntimeOwner = new Object();
        Object freshRuntimeOwner = new Object();
        AtomicInteger resumed = new AtomicInteger();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldRuntimeOwner));
        assertFalse(ProcessGattRegistrationGateV2.tryAcquire(freshRuntimeOwner));
        ProcessGattRegistrationGateV2.whenFree(freshRuntimeOwner, () -> {
            if (ProcessGattRegistrationGateV2.tryAcquire(freshRuntimeOwner)) {
                resumed.incrementAndGet();
            }
        });

        // Closing the old controller is not registration proof: its exact lease remains.
        assertTrue(ProcessGattRegistrationGateV2.owns(oldRuntimeOwner));
        assertEquals(0, resumed.get());

        // The old wrapper's first callback permits exact close/unregister and releases the gate.
        ProcessGattRegistrationGateV2.release(oldRuntimeOwner);
        assertEquals(1, resumed.get());
        assertTrue(ProcessGattRegistrationGateV2.owns(freshRuntimeOwner));
        ProcessGattRegistrationGateV2.release(freshRuntimeOwner);
    }

    @Test public void drainHasPriorityWhenNormalWaiterWasInsertedFirst() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldOwner = new Object();
        Object normal = new Object();
        Object drain = new Object();
        List<String> callbacks = new ArrayList<>();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldOwner));
        ProcessGattRegistrationGateV2.whenFree(normal, () -> callbacks.add("normal"));
        ProcessGattRegistrationGateV2.whenFreeForDrain(drain, () -> callbacks.add("drain"));

        ProcessGattRegistrationGateV2.release(oldOwner);
        assertEquals(1, callbacks.size());
        assertEquals("drain", callbacks.get(0));
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drain));
        assertFalse(ProcessGattRegistrationGateV2.isHeld());
        assertTrue(ProcessGattRegistrationGateV2.acquisitionBlocked());
        assertFalse(ProcessGattRegistrationGateV2.tryAcquire(normal));

        ProcessGattRegistrationGateV2.releaseDrainReservation(drain);
        assertEquals(1, callbacks.size());
        assertFalse(ProcessGattRegistrationGateV2.acquisitionBlocked());
        Object fresh = new Object();
        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(fresh));
        ProcessGattRegistrationGateV2.release(fresh);
    }

    @Test public void drainHasPriorityWhenDrainWaiterWasInsertedFirst() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldOwner = new Object();
        Object drain = new Object();
        Object normal = new Object();
        List<String> callbacks = new ArrayList<>();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldOwner));
        ProcessGattRegistrationGateV2.whenFreeForDrain(drain, () -> callbacks.add("drain"));
        ProcessGattRegistrationGateV2.whenFree(normal, () -> callbacks.add("normal"));

        ProcessGattRegistrationGateV2.release(oldOwner);
        assertEquals(1, callbacks.size());
        assertEquals("drain", callbacks.get(0));
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drain));

        ProcessGattRegistrationGateV2.releaseDrainReservation(drain);
        assertEquals(1, callbacks.size());
        Object fresh = new Object();
        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(fresh));
        ProcessGattRegistrationGateV2.release(fresh);
    }

    @Test public void postedDrainCompletionRetainsAtomicZeroOwnerProof() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldOwner = new Object();
        Object drain = new Object();
        Object contender = new Object();
        List<Runnable> posted = new ArrayList<>();
        AtomicInteger completions = new AtomicInteger();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldOwner));
        ProcessGattRegistrationGateV2.whenFreeForDrain(
                drain, () -> posted.add(completions::incrementAndGet));
        ProcessGattRegistrationGateV2.release(oldOwner);

        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drain));
        assertEquals(1, posted.size());
        assertEquals(0, completions.get());
        assertFalse(ProcessGattRegistrationGateV2.tryAcquire(contender));

        posted.get(0).run();
        assertEquals(1, completions.get());
        assertFalse(ProcessGattRegistrationGateV2.tryAcquire(contender));

        ProcessGattRegistrationGateV2.releaseDrainReservation(drain);
        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(contender));
        ProcessGattRegistrationGateV2.release(contender);
    }

    @Test public void cancelQueuedAndReservedDrainUnblocksNormalAcquisition() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldOwner = new Object();
        Object queuedDrain = new Object();
        Object reservedDrain = new Object();
        Object normal = new Object();
        AtomicInteger normalCallbacks = new AtomicInteger();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldOwner));
        ProcessGattRegistrationGateV2.whenFreeForDrain(queuedDrain,
                () -> { throw new AssertionError("cancelled drain callback"); });
        ProcessGattRegistrationGateV2.cancelWaiter(queuedDrain);
        ProcessGattRegistrationGateV2.whenFreeForDrain(
                reservedDrain, () -> { /* reservation is the assertion */ });
        ProcessGattRegistrationGateV2.whenFree(normal, normalCallbacks::incrementAndGet);

        ProcessGattRegistrationGateV2.release(oldOwner);
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(reservedDrain));
        assertEquals(0, normalCallbacks.get());

        // Adapter close uses cancelWaiter, which also releases its exact reservation.
        ProcessGattRegistrationGateV2.cancelWaiter(reservedDrain);
        assertEquals(0, normalCallbacks.get());
        assertFalse(ProcessGattRegistrationGateV2.acquisitionBlocked());
        Object fresh = new Object();
        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(fresh));
        ProcessGattRegistrationGateV2.release(fresh);
    }

    @Test public void radioResetDiscardsAcquireWaiterAndAtomicallyReservesDrain() {
        ProcessGattRegistrationGateV2.radioReset();
        Object oldOwner = new Object();
        Object acquireWaiter = new Object();
        Object drainWaiter = new Object();
        Object nextOwner = new Object();
        AtomicInteger acquireCallbacks = new AtomicInteger();
        AtomicInteger drainCallbacks = new AtomicInteger();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(oldOwner));
        ProcessGattRegistrationGateV2.whenFree(
                acquireWaiter, acquireCallbacks::incrementAndGet);
        ProcessGattRegistrationGateV2.whenFreeForDrain(
                drainWaiter, drainCallbacks::incrementAndGet);

        ProcessGattRegistrationGateV2.radioReset();
        assertFalse(ProcessGattRegistrationGateV2.isHeld());
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drainWaiter));
        assertEquals(0, acquireCallbacks.get());
        assertEquals(1, drainCallbacks.get());
        assertFalse(ProcessGattRegistrationGateV2.tryAcquire(nextOwner));

        ProcessGattRegistrationGateV2.releaseDrainReservation(drainWaiter);
        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(nextOwner));
        ProcessGattRegistrationGateV2.release(nextOwner);
    }

    @Test public void radioResetDoesNotStealExistingDrainReservation() {
        ProcessGattRegistrationGateV2.radioReset();
        Object drain = new Object();
        Object secondDrain = new Object();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();

        ProcessGattRegistrationGateV2.whenFreeForDrain(drain, first::incrementAndGet);
        ProcessGattRegistrationGateV2.whenFreeForDrain(secondDrain, second::incrementAndGet);
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drain));

        ProcessGattRegistrationGateV2.radioReset();
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(drain));
        assertEquals(1, first.get());
        assertEquals(0, second.get());

        ProcessGattRegistrationGateV2.releaseDrainReservation(drain);
        assertTrue(ProcessGattRegistrationGateV2.ownsDrainReservation(secondDrain));
        assertEquals(1, second.get());
        ProcessGattRegistrationGateV2.releaseDrainReservation(secondDrain);
    }

    @Test public void cancelledNormalWaiterCannotStartLateRegistration() {
        ProcessGattRegistrationGateV2.radioReset();
        Object current = new Object();
        Object stale = new Object();
        AtomicInteger callbacks = new AtomicInteger();

        assertTrue(ProcessGattRegistrationGateV2.tryAcquire(current));
        ProcessGattRegistrationGateV2.whenFree(stale, callbacks::incrementAndGet);
        ProcessGattRegistrationGateV2.cancelWaiter(stale);
        ProcessGattRegistrationGateV2.release(current);

        assertEquals(0, callbacks.get());
        assertFalse(ProcessGattRegistrationGateV2.isHeld());
    }
}
