/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression barriers for Helper v19 telemetry on the established reverse-route ANCS owner. */
public final class Ha1176UnifiedTelemetryRelayContractTest {
    @Test public void androidDiscoversGenerationThreeRelayInBothClientRoutes()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String discovery = between(transport, "private void handleServices",
                "private void subscribeServiceChangedIfAvailable");
        String relayGate = between(transport, "private boolean helperTelemetryClientEnabled",
                "private void continueAfterHelperTelemetrySubscription");

        assertTrue(transport.contains("d2d9e4b0-47f1-4e44-a8bb-a932fd5a2f03"));
        assertTrue(transport.contains("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f03"));
        assertTrue(discovery.contains("getService(TELEMETRY_RELAY_SERVICE)"));
        assertTrue(discovery.contains("getCharacteristic(TELEMETRY_RELAY_CHARACTERISTIC)"));
        assertTrue(discovery.contains("getCharacteristic(TELEMETRY_CHARACTERISTIC)"));
        assertTrue(discovery.contains("startHelperTelemetryRead(callbackGatt)"));
        assertTrue(relayGate.contains("iphonePeripheralMode || managedIncomingMode"));
        assertTrue(transport.contains("|| TELEMETRY_RELAY_CHARACTERISTIC.equals(uuid)"));
    }

    @Test public void optionalRelayCannotReplaceOrBlockTheAncsOwner() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String discovery = between(transport, "private void handleServices",
                "private void subscribeServiceChangedIfAvailable");
        String continuation = between(transport,
                "private void continueAfterHelperTelemetrySubscription",
                "private void finishAncsReadySetup");

        assertTrue(discovery.indexOf("startHelperTelemetryRead(callbackGatt)")
                < discovery.indexOf("getService(AncsProtocol.SERVICE)"));
        assertTrue(discovery.contains("&& iphonePeripheralMode"));
        assertTrue(continuation.contains("else if (managedIncomingMode)"));
        assertTrue(continuation.contains("handleServices(callbackGatt, GATT_SUCCESS)"));
        assertTrue(transport.contains("SAME-PEER HANDOFF · ANCS CLIENT PRESERVED"));
        assertTrue(transport.contains("Android ANCS client remains owner"));
    }

    @Test public void helperPublishesReadNotifyRelayAndEndsBootstrapReconnectLoop()
            throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v19/"
                + "KX11ANCSHelper/ViewController.swift");
        String publication = between(helper, "private func publishServiceIfPossible",
                "private func clearPublishedService");
        String disconnect = between(helper, "private func handleCentralDisconnect",
                "func centralManager(_ central: CBCentralManager,\n"
                        + "                        didUpdateANCSAuthorizationFor");

        assertTrue(helper.contains("KX11 ANCS HELPER v19"));
        assertTrue(helper.contains("D2D9E4B0-47F1-4E44-A8BB-A932FD5A2F03"));
        assertTrue(helper.contains("D2D9E4B4-47F1-4E44-A8BB-A932FD5A2F03"));
        assertTrue(publication.contains("properties: [.read, .notify]"));
        assertTrue(publication.contains("service.characteristics = [telemetry]"));
        assertTrue(disconnect.contains("if completedSecureHandoff && hardReset == nil"));
        assertTrue(disconnect.contains("Защищённый Central bootstrap завершён"));
        assertTrue(disconnect.contains("waitForCentralTelemetryRelay"));
        assertTrue(helper.contains("confirmCentralTelemetryRelay(\"B4 read\")"));
        assertTrue(helper.contains("ANCS + ТЕЛЕМЕТРИЯ АКТИВНЫ"));
        assertFalse(helper.contains("centralTelemetryFrames"));
        assertFalse(helper.contains("peripheral.setNotifyValue(true, for: telemetry)"));
    }

    @Test public void relayCarriesExactPublicPowerAndNetworkSnapshot() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v19/"
                + "KX11ANCSHelper/ViewController.swift");
        String snapshot = between(helper, "private func captureTelemetrySnapshot",
                "private func crc8");
        String network = between(helper, "private func currentNetworkType",
                "private func networkLabel");

        assertTrue(snapshot.contains("UIDevice.current"));
        assertTrue(snapshot.contains("case .charging:"));
        assertTrue(snapshot.contains("case .full:"));
        assertTrue(snapshot.contains("case .unplugged:"));
        assertTrue(snapshot.contains("networkCode: currentNetworkCode()"));
        assertTrue(network.contains("CTTelephonyNetworkInfo()"));
        assertTrue(network.contains("serviceCurrentRadioAccessTechnology"));
        assertTrue(helper.contains("makeTelemetryFrame"));
        assertTrue(helper.contains("bytes.append(crc8(bytes))"));
    }

    @Test public void releaseIdentityIsHa1176AndHelperBuildIsNineteen() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        String project = project("ios/KX11-iPhone-ANCS-Helper-v19/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");

        assertTrue(build.contains("return 'v2.8.2-ha1176'"));
        assertTrue(project.contains("MARKETING_VERSION = 19.0"));
        assertTrue(project.contains("CURRENT_PROJECT_VERSION = 19"));
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
