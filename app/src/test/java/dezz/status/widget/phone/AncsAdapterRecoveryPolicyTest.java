/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AncsAdapterRecoveryPolicyTest {
    @Test public void neverCyclesBluetoothBeforeOneProvenAncsSession() {
        assertFalse(AncsAdapterRecoveryPolicy.mayReset(
                false, false, false, 1_000_000L, 0L));
    }

    @Test public void healthyOrAlreadyRecoveringSessionCannotCycleAgain() {
        assertFalse(AncsAdapterRecoveryPolicy.mayReset(
                true, true, false, 1_000_000L, 0L));
        assertFalse(AncsAdapterRecoveryPolicy.mayReset(
                true, false, true, 1_000_000L, 0L));
    }

    @Test public void cooldownPreventsABluetoothResetLoop() {
        long last = 900_000L;
        assertFalse(AncsAdapterRecoveryPolicy.mayReset(
                true, false, false, 1_000_000L, last));
        assertTrue(AncsAdapterRecoveryPolicy.mayReset(
                true, false, false,
                last + AncsAdapterRecoveryPolicy.RESET_COOLDOWN_MS, last));
    }
}
