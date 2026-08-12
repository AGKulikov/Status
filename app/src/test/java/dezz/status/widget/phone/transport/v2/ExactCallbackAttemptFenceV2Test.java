/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Deterministic behavior gates for same-adapter callback generation reuse. */
public final class ExactCallbackAttemptFenceV2Test {
    @Test public void retiredOldCallbackCannotEnterFreshAttempt() {
        ExactCallbackAttemptFenceV2<Object> fence = new ExactCallbackAttemptFenceV2<>();
        Object oldAttempt = new Object();
        Object freshAttempt = new Object();

        assertTrue(fence.begin(oldAttempt));
        assertTrue(fence.owns(oldAttempt));
        assertTrue(fence.retire(oldAttempt));
        assertTrue(fence.begin(freshAttempt));

        assertFalse(fence.owns(oldAttempt));
        assertTrue(fence.owns(freshAttempt));
        assertFalse(fence.retire(oldAttempt));
        assertTrue(fence.owns(freshAttempt));
    }

    @Test public void overlappingAttemptIsRejectedUntilExactRetirement() {
        ExactCallbackAttemptFenceV2<Object> fence = new ExactCallbackAttemptFenceV2<>();
        Object current = new Object();
        Object contender = new Object();

        assertTrue(fence.begin(current));
        assertFalse(fence.begin(contender));
        assertFalse(fence.retire(contender));
        assertTrue(fence.owns(current));
        assertTrue(fence.retire(current));
        assertTrue(fence.isEmpty());
        assertTrue(fence.begin(contender));
    }
}
