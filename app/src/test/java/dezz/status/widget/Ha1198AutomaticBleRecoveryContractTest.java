/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release gate for boot-safe Core Bluetooth restoration and fresh Android GATT generations. */
public final class Ha1198AutomaticBleRecoveryContractTest {
    @Test public void unverifiedIncomingDisconnectRotatesTheAndroidNamespace() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String callback = between(transport,
                "private final BluetoothGattServerCallback gattServerCallback",
                "private final BluetoothGattCallback gattCallback");

        assertTrue(callback.contains("managedIncomingMode && getVerifiedPeer() == null"));
        assertTrue(callback.contains("scheduleManagedIncomingRestart(\n"
                + "                                    \"unverified incoming link closed before PAIR/B3\")"));
        assertFalse(callback.contains("Непроверенный link закрыт; GATT server, реклама и namespace "
                + "\n                                    + \"остаются опубликованы"));

        String restart = between(transport,
                "private void scheduleManagedIncomingRestart",
                "private static boolean requiresControllerRetry");
        assertTrue(restart.contains("stopAdvertising()"));
        assertTrue(restart.contains("startGeelyAncsAdvertising()"));

        String publication = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "private void useStaticDiagnosticNamespace");
        assertTrue(publication.contains("rotateManagedIncomingDiagnosticNamespace()"));
        assertTrue(publication.contains("MANAGED_INCOMING_NAMESPACE_GENERATION"));
    }

    @Test public void helperDefersCommandsUntilPoweredOnAndQuarantinesPoisonedNamespace()
            throws Exception {
        String helper = project(
                "ios/KX11-iPhone-ANCS-Helper-v35/KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("KX11 ANCS HELPER v35"));
        assertTrue(helper.contains("private func stopCentralScanSafely"));
        assertTrue(helper.contains("private func cancelCentralConnectionSafely"));
        assertTrue(helper.contains("private func flushDeferredCentralCommands"));
        assertTrue(helper.contains("Central restore сохранён; BLE-команды отложены до poweredOn"));
        assertTrue(helper.contains("generation == centralRejectedNamespaceGeneration"));
        assertTrue(helper.contains("Игнорирую отвергнутый namespace"));
        assertEquals(1, occurrences(helper,
                "centralManager.connect(peripheral, options: options)"));

        String restore = between(helper,
                "willRestoreState dict", "didDiscover peripheral");
        assertFalse(restore.contains("central.stopScan()"));
        assertFalse(restore.contains("central.cancelPeripheralConnection"));

        String connected = between(helper,
                "private func continueCentralConnected", "private func startCentralRouteIfPossible");
        assertFalse(connected.contains("centralReconnectFailureCount = 0"));
    }

    @Test public void restorationManagersAreCreatedBeforeLaunchReturns() throws Exception {
        String delegate = project(
                "ios/KX11-iPhone-ANCS-Helper-v35/KX11ANCSHelper/AppDelegate.swift");
        assertTrue(delegate.contains("let controller = ViewController()"));
        assertTrue(delegate.contains("_ = controller.view"));

        String plist = project(
                "ios/KX11-iPhone-ANCS-Helper-v35/KX11ANCSHelper/Info.plist");
        assertTrue(plist.contains("<string>bluetooth-central</string>"));
        assertTrue(plist.contains("<string>bluetooth-peripheral</string>"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }

    private static int occurrences(String source, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
