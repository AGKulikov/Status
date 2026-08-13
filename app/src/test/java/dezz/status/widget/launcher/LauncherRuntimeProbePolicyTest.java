/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public final class LauncherRuntimeProbePolicyTest {
    @Test public void absentRuntimeUsesBoundedRecoveryBackoff() {
        long[] actual = new long[9];
        for (int attempt = 0; attempt < actual.length; attempt++) {
            actual[attempt] = LauncherRuntimeProbePolicy.nextDelayMillis(false, attempt);
        }
        assertArrayEquals(new long[]{
                100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 15_000L, 15_000L, 15_000L
        }, actual);
    }

    @Test public void liveRuntimeUsesOnlyLowRateSafetyWatchdog() {
        assertEquals(30_000L,
                LauncherRuntimeProbePolicy.nextDelayMillis(true, 0));
        assertEquals(30_000L,
                LauncherRuntimeProbePolicy.nextDelayMillis(true, 100));
    }
}
