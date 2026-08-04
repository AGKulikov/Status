/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression barriers for the two independent BLE-role failures reported against HA1163. */
public final class Ha1164BleRoleRecoveryContractTest {
    @Test public void originalRouteCannotConsumeTheReverseRoutesPrivateIdentity()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String ensure = between(controller, "private void ensureGatt",
                "private void ensureLegacyBatteryGatt");

        assertTrue(ensure.contains("PhoneBleRole.isIphoneCentral(current.bleRole)"));
        assertTrue(ensure.contains("? current.ancsDeviceAddress : current.deviceAddress"));
        assertTrue(controller.contains(": created.connectSavedIphone(address)"));
    }

    @Test public void reverseRouteAttachesDirectlyAndNeverDropsItsHealthyServerLink()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String secureStart = between(transport, "private void scheduleSecureClientStart",
                "private void startSamePeerAttach");
        String clientRecovery = between(transport, "private void recoverIncomingClientRole",
                "private void cancelClientAttemptCallbacks");
        String callback = between(transport,
                "private final BluetoothGattCallback gattCallback",
                "@Override\n        public void onServicesDiscovered");

        assertTrue(secureStart.contains("startSamePeerAttach(false"));
        assertFalse(secureStart.contains("startSamePeerAttach(true"));
        assertTrue(clientRecovery.contains("failedClient.close()"));
        assertTrue(clientRecovery.contains("scheduleIncomingClientAttachRetry(reason)"));
        assertFalse(clientRecovery.contains("failedClient.disconnect()"));
        assertFalse(clientRecovery.contains("stopAdvertising()"));
        assertTrue(callback.contains("scheduleIncomingClientAttachRetry("));
        assertFalse(callback.contains("scheduleManagedIncomingRestart("));
    }

    @Test public void helper13DropsStaleHandlesAndWaitsForFreshAdvertising()
            throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v13/"
                + "KX11ANCSHelper/ViewController.swift");
        String reset = between(helper, "private func resetCentralLink",
                "private func isCentralEncryptionError");
        String modified = between(helper, "func peripheral(_ peripheral: CBPeripheral, "
                        + "didModifyServices",
                "}\n}\n\nextension ViewController: CBPeripheralManagerDelegate");

        assertTrue(helper.contains("KX11 ANCS HELPER v13"));
        assertTrue(reset.contains("centralRequireFreshAdvertisement = true"));
        assertTrue(reset.contains("cancelPeripheralConnection(peripheral)"));
        assertTrue(helper.contains("LE encryption was not restored"));
        assertTrue(helper.contains("encrypted telemetry write failed"));
        assertTrue(modified.contains("resetCentralLink(reason: \"D2D9 service invalidated\")"));
        assertFalse(modified.contains("beginCentralDiscovery(peripheral)"));
    }

    @Test public void releaseIdentityIsHa1164() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1165'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
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
