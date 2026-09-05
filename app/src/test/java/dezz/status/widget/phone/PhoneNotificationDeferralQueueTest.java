/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class PhoneNotificationDeferralQueueTest {
    @Test
    public void dueItemsReleaseInOrderAndNearestDeadlineMovesExactly() {
        PhoneNotificationDeferralQueue<String> queue =
                new PhoneNotificationDeferralQueue<>();
        queue.offer("first", 1_000L);
        queue.offer("second", 1_500L);
        queue.offer("third", 3_000L);

        assertEquals(3_000L, queue.nextDeadline(2));
        assertEquals(Collections.emptyList(), queue.drainDue(2_999L, 2));
        assertEquals(Arrays.asList("first", "second"), queue.drainDue(3_500L, 2));
        assertEquals(5_000L, queue.nextDeadline(2));
        assertEquals(Collections.singletonList("third"), queue.drainAll());
        assertTrue(queue.isEmpty());
        assertEquals(-1L, queue.nextDeadline(2));
    }

    @Test
    public void shorterEditedWaitRecomputesDeadlineWithoutReordering() {
        PhoneNotificationDeferralQueue<Integer> queue =
                new PhoneNotificationDeferralQueue<>();
        queue.offer(1, 10_000L);
        queue.offer(2, 11_000L);
        assertEquals(Collections.emptyList(), queue.drainDue(14_999L, 30));
        assertEquals(Arrays.asList(1, 2), queue.drainDue(16_000L, 5));
    }

    @Test
    public void pathologicalFloodIsMemoryBoundedAndReportedToCaller() {
        PhoneNotificationDeferralQueue<Integer> queue =
                new PhoneNotificationDeferralQueue<>();
        for (int value = 0; value < PhoneNotificationDeferralQueue.MAX_ITEMS; value++) {
            assertTrue(queue.offer(value, value));
        }
        assertEquals(PhoneNotificationDeferralQueue.MAX_ITEMS, queue.size());
        org.junit.Assert.assertFalse(queue.offer(999, 999L));
        assertEquals(PhoneNotificationDeferralQueue.MAX_ITEMS, queue.size());
        assertEquals(Integer.valueOf(0), queue.drainAll().get(0));
    }
}
