/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ManagedIncomingPublicationPolicyTest {
    @Test public void durableLineageAdvancesAndWrapsWithoutReservedValues() {
        assertEquals(1, ManagedIncomingPublicationPolicy.nextPublicationNonce(0));
        assertEquals(2, ManagedIncomingPublicationPolicy.nextPublicationNonce(1));
        assertEquals(1, ManagedIncomingPublicationPolicy.nextPublicationNonce(0xFFFFFE));
        assertEquals(1, ManagedIncomingPublicationPolicy.nextPublicationNonce(0xFFFFFF));
        assertEquals(1, ManagedIncomingPublicationPolicy.nextPublicationNonce(-1));

        assertTrue(ManagedIncomingPublicationPolicy.isValidPublicationNonce(1));
        assertTrue(ManagedIncomingPublicationPolicy.isValidPublicationNonce(0xFFFFFE));
        assertFalse(ManagedIncomingPublicationPolicy.isValidPublicationNonce(0));
        assertFalse(ManagedIncomingPublicationPolicy.isValidPublicationNonce(0xFFFFFF));
    }

    @Test public void repeatedCandidateFromSamePersistedValueIsStableUntilCommit() {
        int firstAttempt = ManagedIncomingPublicationPolicy.nextPublicationNonce(41);
        int failedAddServiceRetry = ManagedIncomingPublicationPolicy.nextPublicationNonce(41);
        assertEquals(42, firstAttempt);
        assertEquals(firstAttempt, failedAddServiceRetry);
        assertEquals(43,
                ManagedIncomingPublicationPolicy.nextPublicationNonce(firstAttempt));
    }

    @Test public void v2ManufacturerFrameIsSixBytesBigEndianAndKeepsGeneration() {
        assertArrayEquals(new byte[]{
                        0x02, 0x2F, 0x04, 0x12, 0x34, 0x56
                }, ManagedIncomingPublicationPolicy.publicationNonceFrame(
                        0x2F04, 0x123456));
    }

    @Test public void scanResponseRetainsExactLegacyV1Prefix() {
        assertArrayEquals(new byte[]{0x01, 0x2F, 0x04},
                ManagedIncomingPublicationPolicy.legacyNamespaceFrame(0x2F04));
    }

    @Test public void oldCallbacksCannotMutateANewerPublication() {
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.OBSERVE_STALE,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        false, 7L, 41, 8L, 42, false, true));
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.OBSERVE_STALE,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        false, 7L, 41, 8L, 42, false, false));
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.OBSERVE_STALE,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        true, 7L, 41, 8L, 42, false, false));
    }

    @Test public void currentCallbackIsTerminalAndIdempotent() {
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.ACCEPT_SUCCESS,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        true, 8L, 42, 8L, 42, false, true));
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.ACCEPT_FAILURE,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        true, 8L, 42, 8L, 42, false, false));
        assertEquals(ManagedIncomingPublicationPolicy.AdvertisingCallbackAction.IGNORE_DUPLICATE,
                ManagedIncomingPublicationPolicy.advertisingCallbackAction(
                        true, 8L, 42, 8L, 42, true, false));
    }

    @Test(expected = IllegalArgumentException.class)
    public void v2FrameRejectsMissingNonce() {
        ManagedIncomingPublicationPolicy.publicationNonceFrame(0x2F04, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void framesRejectReservedNamespace() {
        ManagedIncomingPublicationPolicy.legacyNamespaceFrame(0xFFFF);
    }
}
