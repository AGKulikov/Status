/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Guards every field-proven ANCS disk/Keystore stall away from Android's main looper. */
public final class AndroidAncsIoThreadContractTest {
    @Test public void durableCoordinatorUsesOneProcessWorkerInsteadOfMainLooper()
            throws Exception {
        String scheduler = source(
                "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                        + "AndroidMainBleSchedulerV2.java");
        assertTrue(scheduler.contains("new HandlerThread(\"NatroAncsCoordinator\")"));
        assertTrue(scheduler.contains("new Handler(thread.getLooper())"));
        assertTrue(scheduler.contains("fifo.post("));
        assertFalse(scheduler.contains("Looper.getMainLooper()"));

        String store = source(
                "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                        + "AndroidIphoneBleStateStoreV2.java");
        assertTrue(store.contains("commitPhoneBleV2SwitchSnapshot"));
        assertTrue(store.contains("durability mismatch"));
    }

    @Test public void routineConstructionDoesNotDecryptEnrollmentOnMain() throws Exception {
        String controller = source(
                "app/src/main/java/dezz/status/widget/phone/PhoneConnectorController.java");
        String start = between(controller,
                "private void startV2RuntimeOnMain",
                "private final class V2TransportListener");
        assertFalse(start.contains("phoneBleV2HelperInstallationId()"));
        assertTrue(start.contains("initialRadioEnabled,\n"
                + "                        // Explicit LE enrollment"));

        String transport = source(
                "app/src/main/java/dezz/status/widget/phone/transport/v2/android/"
                        + "AndroidCentralTransportV2.java");
        String epochLoad = between(transport,
                "private void startRouteAfterEnrollmentLoad",
                "private void apply(BleRouteTransition");
        assertTrue(epochLoad.contains("enrollmentIo.execute("));
        assertTrue(epochLoad.contains("readEnrollmentRecords("));
        assertTrue(epochLoad.contains("main.post("));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get(System.getProperty("user.dir"));
        Path direct = root.resolve(relative);
        Path path = Files.exists(direct) || root.getParent() == null
                ? direct : root.getParent().resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        if (from < 0 || to < 0 || to <= from) {
            throw new AssertionError("missing source boundary: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
