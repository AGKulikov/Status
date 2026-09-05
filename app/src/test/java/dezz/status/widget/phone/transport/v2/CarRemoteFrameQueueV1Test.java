/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class CarRemoteFrameQueueV1Test {
    @Test public void statePressureNeverEvictsCatalogOrSyncBoundary() {
        CarRemoteFrameQueueV1 queue = new CarRemoteFrameQueueV1();
        long sequence = 1L;
        for (int id = 1; id <= 39; id++) {
            assertNotNull(queue.offer(frame(IphoneCarRemoteProtocolV1.Type.CATALOG,
                    id, 1, id == 39 ? 0 : IphoneCarRemoteProtocolV1.FLAG_MORE,
                    sequence++)));
        }
        queue.offer(frame(IphoneCarRemoteProtocolV1.Type.SYNC_COMPLETE,
                0, 0, 0, sequence++));
        for (int id = 1; id <= 180; id++) {
            queue.offer(frame(IphoneCarRemoteProtocolV1.Type.STATE,
                    id, 0, IphoneCarRemoteProtocolV1.FLAG_AVAILABLE, sequence++));
        }

        int catalogs = 0;
        int sync = 0;
        byte[] raw;
        while ((raw = queue.poll()) != null) {
            IphoneCarRemoteProtocolV1.Frame decoded = IphoneCarRemoteProtocolV1.decode(raw);
            assertNotNull(decoded);
            if (decoded.type == IphoneCarRemoteProtocolV1.Type.CATALOG) catalogs++;
            if (decoded.type == IphoneCarRemoteProtocolV1.Type.SYNC_COMPLETE) sync++;
        }
        assertEquals(39, catalogs);
        assertEquals(1, sync);
    }

    @Test public void latestStateForOneControlReplacesOlderSnapshot() {
        CarRemoteFrameQueueV1 queue = new CarRemoteFrameQueueV1();
        queue.offer(state(11, 1, 1));
        assertEquals(CarRemoteFrameQueueV1.OfferResult.REPLACED_STATE,
                queue.offer(state(11, 2, 2)));
        assertEquals(1, queue.size());
        IphoneCarRemoteProtocolV1.Frame latest =
                IphoneCarRemoteProtocolV1.decode(queue.poll());
        assertNotNull(latest);
        assertEquals(2, latest.value);
    }

    private static byte[] state(int id, int value, long sequence) {
        return IphoneCarRemoteProtocolV1.encode(new IphoneCarRemoteProtocolV1.Frame(
                IphoneCarRemoteProtocolV1.Type.STATE, id, 0,
                IphoneCarRemoteProtocolV1.FLAG_AVAILABLE, 0, sequence, value, 0));
    }

    private static byte[] frame(IphoneCarRemoteProtocolV1.Type type, int id, int code,
                                int flags, long sequence) {
        return IphoneCarRemoteProtocolV1.encode(new IphoneCarRemoteProtocolV1.Frame(
                type, id, code, flags, 0, sequence, 0, 0));
    }
}
