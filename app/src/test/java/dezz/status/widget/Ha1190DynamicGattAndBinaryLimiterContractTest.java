/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1190 regressions derived from the fixed-F04 cache failure and binary limiter trace. */
public final class Ha1190DynamicGattAndBinaryLimiterContractTest {
    @Test public void androidPublishesCacheBustingNamespaceBehindStableBeacon()
            throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String start = between(transport,
                "private boolean startGeelyAncsAdvertising",
                "/** Allocates one persistent namespace");
        String namespace = between(transport,
                "private void rotateManagedIncomingDiagnosticNamespace",
                "/** Legacy comparison test");
        String disconnect = between(transport,
                "private void preserveManagedIncomingPublicationAfterLinkLoss",
                "private static String deviceKey");

        assertTrue(transport.contains("d2d9e4bf-47f1-4e44-a8bb-a932fd5affff"));
        assertTrue(start.contains("rotateManagedIncomingDiagnosticNamespace()"));
        assertTrue(start.contains("addManufacturerData"));
        assertTrue(start.contains("MANAGED_INCOMING_BEACON_SERVICE"));
        assertTrue(namespace.contains("SharedPreferences"));
        assertTrue(namespace.contains("managedIncomingUuid(0, generation)"));
        assertTrue(namespace.contains("serverControlCharacteristic = managedIncomingUuid(2"));
        assertTrue(namespace.contains("serverSecureCharacteristic = managedIncomingUuid(3"));
        assertTrue(disconnect.contains("resetVerifiedPeerSession()"));
        assertFalse(disconnect.contains("stopAdvertising()"));
        assertFalse(disconnect.contains("closeGattServer()"));
        assertFalse(disconnect.contains("rotateManagedIncomingDiagnosticNamespace"));
        assertTrue(transport.contains("serverDiagnosticService.equals(service.getUuid())"));
    }

    @Test public void helperResolvesFreshGenerationBeforeBootstrap() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v28/"
                + "KX11ANCSHelper/ViewController.swift");
        String bootstrap = between(helper,
                "private func beginCentralDiscovery",
                "private func stopCentralRoute");
        String discovery = between(helper,
                "func centralManager(_ central: CBCentralManager, didDiscover peripheral",
                "func centralManager(_ central: CBCentralManager, didConnect peripheral");

        assertTrue(helper.contains("KX11 ANCS HELPER v28"));
        assertTrue(helper.contains("managedIncomingBeaconUUID"));
        assertTrue(helper.contains("decodeCentralNamespace"));
        assertTrue(helper.contains("applyCentralNamespace(generation)"));
        assertTrue(helper.contains("managedIncomingUUID(kind: 2"));
        assertTrue(helper.contains("withServices: [managedIncomingBeaconUUID]"));
        assertTrue(discovery.contains("let generation = advertisedCentralNamespace"));
        assertTrue(bootstrap.contains("peripheral.discoverServices([centralServiceUUID])"));
        assertFalse(bootstrap.contains("centralNamespaceResolved = false"));
    }

    @Test public void binaryAggregatesUseDeclaredGettersAndFastDiagnosticPolling()
            throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String discovery = between(fallback,
                "private static Set<String> discoverBinaryCallbackGetterNames",
                "private static boolean isIntegerReturnType");
        String reads = between(fallback,
                "private ReadResult readCurrentValues",
                "private void handleCallbackArguments");

        assertTrue(fallback.contains("RECORDER_HEALTH_READ_MILLIS = 250L"));
        assertTrue(discovery.contains("method.getReturnType() == byte[].class"));
        assertTrue(discovery.contains("SignalId_"));
        assertTrue(fallback.contains("binaryRecorderGetterNames.putAll"));
        assertTrue(reads.contains("readBinaryCurrentValues(manager)"));
        assertTrue(reads.contains("findTwoIntMethod(manager.getClass(), \"getBytesProperty\")"));
        assertTrue(reads.contains("bytesReader.invoke(manager, propertyId, ECARX_GLOBAL_AREA)"));
        assertTrue(reads.contains("EcarxSignalDecoder.coerceByteArray(value)"));
        assertFalse(reads.contains("reader.getReturnType() != byte[].class"));
        assertTrue(reads.contains("reader.invoke(manager)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
        assertTrue(fallback.contains("adasRecorderDemand ? RECORDER_HEALTH_READ_MILLIS"));
    }

    @Test public void releaseIdentityAdvancesAsMatchedPair() throws Exception {
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1194'"));
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v28/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(helperProject.contains("MARKETING_VERSION = 28.0"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 28"));
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
}
