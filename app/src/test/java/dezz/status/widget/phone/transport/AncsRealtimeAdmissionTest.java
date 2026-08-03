/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AncsRealtimeAdmissionTest {
    @Test public void protocolRecognizesOnlyThePreExistingFlagBit() {
        AncsProtocol.Event preExisting = event(
                AncsProtocol.EVENT_ADDED, AncsProtocol.EVENT_FLAG_PRE_EXISTING, 1L);
        AncsProtocol.Event otherFlags = event(AncsProtocol.EVENT_ADDED, 0x12, 2L);

        assertTrue(AncsProtocol.isPreExisting(preExisting));
        assertFalse(AncsProtocol.isPreExisting(otherFlags));
        assertFalse(AncsProtocol.isPreExisting(null));
    }

    @Test public void preExistingAddedAndModifiedNeverEnterTheRequestQueue() {
        IphoneAncsTransport.RealtimeAdmission admission =
                new IphoneAncsTransport.RealtimeAdmission();

        assertFalse(admission.shouldRequest(event(
                AncsProtocol.EVENT_ADDED, AncsProtocol.EVENT_FLAG_PRE_EXISTING, 10L)));
        assertFalse(admission.shouldRequest(event(
                AncsProtocol.EVENT_MODIFIED, AncsProtocol.EVENT_FLAG_PRE_EXISTING, 11L)));
        assertTrue(admission.shouldRequest(event(AncsProtocol.EVENT_ADDED, 0, 12L)));
        assertTrue(admission.shouldRequest(event(AncsProtocol.EVENT_MODIFIED, 0, 13L)));
        assertFalse(admission.shouldRequest(event(AncsProtocol.EVENT_REMOVED, 0, 14L)));
    }

    @Test public void removalIsEmittedOnlyOnceForAUidActuallyDeliveredThisSession() {
        IphoneAncsTransport.RealtimeAdmission admission =
                new IphoneAncsTransport.RealtimeAdmission();

        assertFalse(admission.consumeRemoval(21L));
        admission.markDelivered(21L);
        assertTrue(admission.contains(21L));
        assertTrue(admission.consumeRemoval(21L));
        assertFalse(admission.contains(21L));
        assertFalse(admission.consumeRemoval(21L));
    }

    @Test public void clearingRuntimeStartsACompletelyFreshLiveUidSession() {
        IphoneAncsTransport.RealtimeAdmission admission =
                new IphoneAncsTransport.RealtimeAdmission();
        admission.markDelivered(31L);
        admission.markDelivered(32L);

        admission.clear();

        assertFalse(admission.contains(31L));
        assertFalse(admission.contains(32L));
        assertFalse(admission.consumeRemoval(31L));
    }

    private static AncsProtocol.Event event(int eventId, int flags, long uid) {
        return new AncsProtocol.Event(eventId, flags, 0, 0, uid);
    }
}
