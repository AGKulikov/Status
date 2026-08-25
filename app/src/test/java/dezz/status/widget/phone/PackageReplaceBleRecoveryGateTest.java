/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class PackageReplaceBleRecoveryGateTest {
    @Test public void recentSameBootReplacementGetsOnlyTheRemainingQuietWindow() {
        long marked = 100_000L;
        assertEquals(PackageReplaceBleRecoveryGate.QUIET_MS,
                PackageReplaceBleRecoveryGate.remainingQuietMillis(marked, marked));
        assertEquals(3_000L,
                PackageReplaceBleRecoveryGate.remainingQuietMillis(marked, marked + 5_000L));
        assertEquals(0L, PackageReplaceBleRecoveryGate.remainingQuietMillis(
                marked, marked + PackageReplaceBleRecoveryGate.QUIET_MS));
    }

    @Test public void rebootAndStaleStorageMarksCannotDelayThePhone() {
        assertEquals(0L,
                PackageReplaceBleRecoveryGate.remainingQuietMillis(100_000L, 2_000L));
        assertEquals(0L,
                PackageReplaceBleRecoveryGate.remainingQuietMillis(100_000L, 200_001L));
        assertEquals(0L,
                PackageReplaceBleRecoveryGate.remainingQuietMillis(Long.MIN_VALUE, 2_000L));
    }
}
