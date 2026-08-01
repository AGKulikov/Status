/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneTelemetryStoreTest {
    @Test public void retentionWindowRejectsFutureAndExpiredSnapshots() {
        long now = 1_000_000L;
        assertTrue(PhoneTelemetryStore.isFresh(now - 1L, now));
        assertTrue(PhoneTelemetryStore.isFresh(
                now - PhoneTelemetryStore.RETENTION_MS, now));
        assertFalse(PhoneTelemetryStore.isFresh(
                now - PhoneTelemetryStore.RETENTION_MS - 1L, now));
        assertFalse(PhoneTelemetryStore.isFresh(now + 1L, now));
        assertFalse(PhoneTelemetryStore.isFresh(0L, now));
    }

    @Test public void batteryAndNetworkKeepIndependentFreshnessTimestamps() {
        PhoneTelemetryStore.Record record = new PhoneTelemetryStore.Record(
                "aa:bb:cc:dd:ee:ff", 900L, 700L,
                88, "ble_bas", true, false, "ble_bas",
                true, "charging", "good",
                true, 75, false, "Operator", "LTE");

        assertEquals(900L, record.updatedAtWallMs);
        assertEquals(900L, record.batteryUpdatedAtWallMs);
        assertEquals(700L, record.networkUpdatedAtWallMs);
        assertTrue(record.hasUsefulData());
    }
}
