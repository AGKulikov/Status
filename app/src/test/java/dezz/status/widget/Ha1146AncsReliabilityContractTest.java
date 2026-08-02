/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression barriers for the serialized, restart-safe ANCS transport introduced in HA1146. */
public final class Ha1146AncsReliabilityContractTest {
    @Test public void coldStartAndReconnectRetainExactlyOneBackgroundGattOwner()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String connect = between(transport, "private boolean startManagedBackgroundAttach",
                "private void scheduleSavedPeerDirectFallback");
        String callback = between(transport,
                "private void handleIphonePeripheralConnectionState",
                "private final BluetoothGattCallback gattCallback");
        String autoWait = between(transport, "private void awaitBackgroundAutoReconnect",
                "private boolean startSavedPeerScan");

        assertTrue(connect.contains("connectGatt(context, true, gattCallback"));
        assertTrue(connect.contains("gatt != null || clientConnectInFlight"));
        assertTrue(callback.contains("activeClientAutoConnect && activeClientEstablished"));
        assertTrue(callback.contains("awaitBackgroundAutoReconnect(callbackGatt"));
        assertTrue(autoWait.contains("if (closing || gatt != expected"));
        assertTrue(autoWait.contains("closeClientGatt(expected)"));
        assertTrue(autoWait.contains("scheduleSavedPeerDirectFallback"));
    }

    @Test public void ambiguousAclLossProbesBeforeDestroyingAHealthyAncsLink()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String transport = source("phone/transport/IphoneAncsTransport.java");

        assertTrue(controller.contains("transport == BluetoothDevice.TRANSPORT_LE"));
        assertTrue(controller.contains(
                "current.requestSavedPeerReconnect(detail, confirmedLeLoss)"));
        assertTrue(transport.contains("expected.readRemoteRssi()"));
        assertTrue(transport.contains("GATT liveness probe OK"));
        assertTrue(transport.contains("cancelAmbiguousAclProbe()"));
    }

    @Test public void mandatoryAncsQueueCannotBeKilledByOptionalBatteryOperations()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String controller = source("phone/PhoneConnectorController.java");

        assertTrue(transport.contains("Data Source → Notification Source"));
        assertTrue(transport.contains("ANCS_DESCRIPTOR_WRITE_TIMEOUT_MS = 90_000L"));
        assertTrue(transport.contains("optional operation skipped, ANCS stays READY"));
        assertFalse(transport.contains("state(\"BAS OPERATION TIMEOUT"));
        assertFalse(controller.contains("state.contains(\"BAS OPERATION TIMEOUT\")"));
    }

    @Test public void onlyNonSessionTelemetrySurvivesARecoveryWindow() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String store = source("phone/PhoneTelemetryStore.java");
        String protocol = source("phone/transport/AncsProtocol.java");

        assertTrue(store.contains("createDeviceProtectedStorageContext()"));
        assertTrue(store.contains("battery_updated_at"));
        assertTrue(store.contains("network_updated_at"));
        assertTrue(controller.contains("persistCurrentTelemetry()"));
        assertTrue(controller.contains("telemetry.stale"));
        assertTrue(controller.contains("retainedBatteryFresh(now)"));
        assertTrue(controller.contains("retainedNetworkFresh(now)"));
        assertFalse(store.contains("notification_uid"));
        assertTrue(protocol.contains("EVENT_FLAG_PRE_EXISTING"));
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
