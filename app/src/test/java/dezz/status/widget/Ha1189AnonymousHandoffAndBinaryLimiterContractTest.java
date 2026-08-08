/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1189 regressions derived from the latest Android 9/v26 in-car traces. */
public final class Ha1189AnonymousHandoffAndBinaryLimiterContractTest {
    @Test public void anonymousPhaseTwoNeedsEncryptedPhysicalReleaseWindow() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String binding = between(transport,
                "private boolean bindAnonymousHandoffClientIdentity",
                "private boolean observePlannedAncsBootstrapRelease");
        String incoming = between(transport,
                "private boolean handleIncomingAncsHandoffLink",
                "private void scheduleSecureClientStart");
        String anonymous = between(incoming,
                "if (previous != null && bindAnonymousHandoffClientIdentity",
                "log(\"ANCS handoff: жду resolved bonded peer");
        String lookup = between(transport,
                "private GattServerPeer findConnectedServerPeer",
                "private boolean issueCurrentLinkSecurityChallenge");

        assertTrue(binding.contains("ancsBootstrapDisconnectRequested"));
        assertTrue(binding.contains("ancsBootstrapReleaseObserved"));
        assertTrue(binding.contains("BluetoothDevice.DEVICE_TYPE_UNKNOWN"));
        assertTrue(binding.contains("BluetoothDevice.BOND_NONE"));
        assertTrue(binding.contains("BluetoothDevice.BOND_BONDED"));
        assertTrue(binding.contains("peer.clientIdentity = stableIdentity"));
        assertTrue(incoming.contains("bindAnonymousHandoffClientIdentity(device, previous)"));
        assertTrue(anonymous.contains("managedResolvedPeer = previous"));
        assertFalse(anonymous.contains("verifiedPeer = device"));
        assertTrue(lookup.contains("sameDevice(peer.clientIdentity, device)"));
    }

    @Test public void helperKeepsRequiresAncsOwnerWhenB4IsAbsent() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v27/"
                + "KX11ANCSHelper/ViewController.swift");
        String optionalRelay = between(helper,
                "private func observeOptionalCentralTelemetryRelay",
                "private func cancelCentralConnectTimeout");

        assertTrue(helper.contains("KX11 ANCS HELPER v27"));
        assertTrue(helper.contains("ANCS OWNER АКТИВЕН · B4 OPTIONAL"));
        assertTrue(optionalRelay.contains("сохраняю без reset"));
        assertFalse(optionalRelay.contains("resetCentralLink"));
    }

    @Test public void limiterRecorderPreservesReadOnlyByteArrayChanges() throws Exception {
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");
        String integration = project(
                "app/src/geely/java/dezz/status/widget/car/GeelyCarIntegration.java");
        String decoder = project(
                "app/src/main/java/dezz/status/widget/car/EcarxSignalDecoder.java");

        assertTrue(fallback.contains("EcarxSignalDecoder.coerceByteArray(value)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
        assertTrue(decoder.contains("if (value instanceof byte[])"));
        assertTrue(decoder.contains("((byte[]) value).clone()"));
        assertTrue(integration.contains("ECARX_ADAS_BINARY_BASELINE"));
        assertTrue(integration.contains("ECARX_ADAS_BINARY_CHANGE"));
        assertTrue(integration.contains("changed_indices"));
        assertTrue(integration.contains("\"write_enabled\", false"));
    }

    @Test public void releaseIdentityAdvancesToHa1189() throws Exception {
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1190'"));
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
