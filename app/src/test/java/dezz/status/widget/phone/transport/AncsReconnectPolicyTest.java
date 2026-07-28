/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AncsReconnectPolicyTest {
    @Test public void retryScheduleRecoversQuicklyAndHasABoundedCeiling() {
        assertEquals(250L, AncsReconnectPolicy.retryDelayMillis(0));
        assertEquals(750L, AncsReconnectPolicy.retryDelayMillis(1));
        assertEquals(15_000L, AncsReconnectPolicy.retryDelayMillis(100));
    }

    @Test public void exactAddressDoesNotDependOnAdvertisementShape() {
        assertTrue(AncsReconnectPolicy.candidateMayBeSelected(
                "AA:BB:CC:DD:EE:FF", "aa:bb:cc:dd:ee:ff",
                false, false, false, false));
    }

    @Test public void resolvedPrivateAddressNeedsAncsBondAndUniqueSupportingIdentity() {
        assertTrue(AncsReconnectPolicy.candidateMayBeSelected(
                "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66",
                true, true, true, true));
        assertFalse(AncsReconnectPolicy.candidateMayBeSelected(
                "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66",
                true, true, false, true));
        assertFalse(AncsReconnectPolicy.candidateMayBeSelected(
                "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66",
                true, true, true, false));
        assertFalse(AncsReconnectPolicy.candidateMayBeSelected(
                "AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66",
                true, false, true, true));
    }
}
