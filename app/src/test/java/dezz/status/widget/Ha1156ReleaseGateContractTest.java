/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Non-negotiable release gates for the HA1156 BLE and driver-rail regression repair. */
public final class Ha1156ReleaseGateContractTest {
    @Test public void helperAdvertisementLeadsToOneDirectGattAndB4BeforeAncs()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String scan = between(transport, "private final ScanCallback scanCallback",
                "private final AdvertiseCallback advertiseCallback");
        String direct = between(transport, "private void connectIphonePeripheral",
                "public void connect(Candidate candidate)");
        String services = between(transport, "private void handleServices",
                "private void subscribeServiceChangedIfAvailable");

        assertTrue(transport.contains("return startSavedPeerScan(device)"));
        assertTrue(scan.contains("advertisesService(record, DIAGNOSTIC_SERVICE)"));
        assertTrue(scan.contains("connectToSavedAdvertisingIphone(result.getDevice(),"));
        assertTrue(direct.contains("connectGatt(context, false, gattCallback"));
        assertFalse(direct.contains("connectGatt(context, true, gattCallback"));
        int helperRead = services.indexOf("startHelperTelemetryRead(callbackGatt)");
        int ancsSubscribe = services.indexOf("descriptorStage = DescriptorStage.DATA_SOURCE");
        assertTrue(helperRead >= 0);
        assertTrue(ancsSubscribe > helperRead);
    }

    @Test public void establishedGattIsRetainedAndSharedCarBluetoothIsNeverCycled()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String callback = between(transport,
                "private void handleIphonePeripheralConnectionState",
                "private final BluetoothGattCallback gattCallback");
        String persistent = between(transport, "private void awaitPersistentGattReconnect",
                "private boolean startSavedPeerScan");
        String controller = source("phone/PhoneConnectorController.java");

        assertTrue(callback.contains("boolean establishedOwner = activeClientEstablished"));
        assertTrue(callback.contains("awaitPersistentGattReconnect(callbackGatt"));
        assertTrue(persistent.contains("expected.connect()"));
        assertFalse(persistent.contains("closeClientGatt(expected)"));
        assertFalse(controller.contains("adapter.disable()"));
        assertFalse(controller.contains("adapter.enable()"));
    }

    @Test public void oneExplicitInsetCannotSwitchTheWholeDriverRailToCompactMode()
            throws Exception {
        String overlay = source("driver/DriverPanelOverlayController.java");
        String settings = source("DriverPanelSettingsActivity.java");

        assertTrue(overlay.contains("DriverControlSpacingPolicy.resolve("));
        assertTrue(settings.contains("DriverControlSpacingPolicy.resolve("));
        assertFalse(overlay.contains("boolean compactSpacing"));
        assertFalse(settings.contains("boolean compactSpacing"));
        assertTrue(overlay.contains("requestedTop[controlIndex] = shortcut.gapBeforePx"));
        assertTrue(overlay.contains("requestedBottom[controlIndex] = shortcut.gapAfterPx"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
