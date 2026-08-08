/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1187 barriers for the two-link ANCS bootstrap and typed callback-only car recorder. */
public final class Ha1187TwoPhaseAncsAndLimiterCaptureContractTest {
    @Test public void androidPreservesTrustAcrossExplicitRequiresAncsHandoff()
            throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String secure = between(transport,
                "private void handleSecureAttSuccess",
                "private void scheduleSecureClientStart");
        String disconnect = between(transport,
                "private void handleVerifiedServerLinkDisconnected",
                "private static String deviceKey");
        String serverConnection = between(transport,
                "public void onConnectionStateChange(BluetoothDevice device,",
                "public void onCharacteristicReadRequest");

        assertTrue(transport.contains("ANCS-HANDOFF"));
        assertTrue(transport.contains("awaitingAncsHandoffReconnect"));
        assertTrue(transport.contains("beginAncsHandoffReconnect"));
        assertTrue(transport.contains("handleIncomingAncsHandoffLink"));
        assertTrue(secure.contains("Android client на bootstrap-link не запускается"));
        assertTrue(disconnect.contains("BOOTSTRAP LINK RELEASED"));
        assertTrue(disconnect.contains("verified peer и Geely_ANCS"));
        assertTrue(serverConnection.indexOf("handleIncomingAncsHandoffLink(device)")
                < serverConnection.indexOf("requestIncomingPrePairLeBond(device)"));
    }

    @Test public void rotatingAnonymousCallbacksCannotStartRepeatedBonding() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String prePair = between(transport,
                "private void requestIncomingPrePairLeBond",
                "private boolean createLeBond");

        assertTrue(prePair.contains("incomingPrePairBondRequested"));
        assertTrue(prePair.contains("PRE-PAIR LE bond deduplicated"));
        assertTrue(prePair.contains("incomingPrePairBondTargetKey"));
        assertFalse(prePair.contains("claimVerifiedPeer"));
    }

    @Test public void helperUsesPlainBootstrapThenRequiresAncsWithoutCustomDiscovery()
            throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v26/"
                + "KX11ANCSHelper/ViewController.swift");

        assertTrue(helper.contains("enum CentralLinkPhase"));
        assertTrue(helper.contains("case bootstrap"));
        assertTrue(helper.contains("case requiresAncs"));
        assertTrue(helper.contains("Data(\"ANCS-HANDOFF\".utf8)"));
        assertTrue(helper.contains("CBConnectPeripheralOptionRequiresANCS: requiresAncs"));
        assertTrue(helper.contains("centralLinkPhase == .requiresAncs"));
        assertTrue(helper.contains("custom F04 discovery intentionally skipped"));
        assertFalse(helper.contains("CBConnectPeripheralOptionRequiresANCS: true"));
    }

    @Test public void runtimeIntegerCatalogIsSubscribedButNeverHealthPolled()
            throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String scan = between(fallback,
                "private void scanPropertyIds",
                "private LinkedHashSet<Integer> recorderPropertyIds");
        String healthRead = between(fallback,
                "private ReadResult readCurrentValues",
                "private void scheduleHealthRead");

        assertTrue(scan.contains("isIntegerCallbackProperty"));
        assertTrue(fallback.contains("typedRecorderDiscoveryIds"));
        assertTrue(fallback.contains("recorderIds.addAll(typedRecorderDiscoveryIds)"));
        assertTrue(fallback.contains("ids.addAll(typedRecorderDiscoveryIds)"));
        assertFalse(healthRead.contains("typedRecorderDiscoveryIds"));
        assertFalse(healthRead.contains("activeRecorderIds"));
    }

    @Test public void releaseIdentityAdvancesToHa1187() throws Exception {
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1187'"));
    }

    private static String project(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
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
