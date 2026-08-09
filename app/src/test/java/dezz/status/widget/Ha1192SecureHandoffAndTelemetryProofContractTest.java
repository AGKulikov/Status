/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regressions taken directly from the 18:53/18:55 in-car Central-route trace. */
public final class Ha1192SecureHandoffAndTelemetryProofContractTest {
    @Test public void secureProofIsCommittedBeforeB3ReadSuccessCanReachIos() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String read = between(transport,
                "public void onCharacteristicReadRequest(BluetoothDevice device,",
                "public void onDescriptorReadRequest");

        assertTrue(transport.contains("private volatile boolean secureAttConfirmed"));
        assertTrue(transport.contains("private Boolean markSecureAttConfirmed"));
        int proofCommit = read.indexOf("markSecureAttConfirmed(device)");
        int successResponse = read.indexOf("\"SECURE ATT OK\".getBytes");
        assertTrue(proofCommit >= 0);
        assertTrue(successResponse >= 0);
        assertTrue(proofCommit < successResponse);
        assertTrue(read.contains("finishSecureAttSuccess("));
    }

    @Test public void anonymousDidConnectNeverStartsPrePairCreateBond() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String connection = between(transport,
                "public void onConnectionStateChange(BluetoothDevice device,",
                "public void onCharacteristicReadRequest");

        assertFalse(transport.contains("requestIncomingPrePairLeBond"));
        assertFalse(transport.contains("incomingPrePairBondRequested"));
        assertFalse(connection.contains("requestBond(device)"));
        assertTrue(connection.contains("LE security начинается только после"));
        assertTrue(connection.contains("PAIR/B3 challenge"));
    }

    @Test public void readyProofRequiresActualB4PayloadAndRetries() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String publicReady = between(transport,
                "public boolean isAncsReady()",
                "private boolean claimVerifiedPeer");
        String proof = between(transport,
                "private boolean startHelperAncsReadyProof",
                "private void scheduleHelperAncsReadyProofRetry");

        assertTrue(transport.contains("iphoneHelperValidTelemetryReceived"));
        assertTrue(transport.contains("acceptHelperTelemetryFrame"));
        assertTrue(proof.contains("!iphoneHelperValidTelemetryReceived"));
        assertTrue(proof.contains("!iphoneHelperTelemetrySubscribed"));
        assertTrue(proof.contains("valid battery/network payload"));
        assertTrue(transport.contains("helperAncsReadyProofAcknowledged"));
        assertTrue(transport.contains("ANCS-SUBSCRIBED retry через 1 с"));
        assertTrue(publicReady.contains("iphoneHelperValidTelemetryReceived"));
        assertTrue(publicReady.contains("iphoneHelperTelemetrySubscribed"));
        assertTrue(publicReady.contains("helperAncsReadyProofAcknowledged"));
        assertTrue(transport.contains("ANCS CCCD OK · ЖДУ B4 ДАННЫЕ"));
        assertTrue(transport.contains("ANCS READY · B4 VERIFIED"));
    }

    @Test public void helperNeedsIndependentAncsAndRelayProofs() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v30/"
                + "KX11ANCSHelper/ViewController.swift");
        String ancs = between(helper,
                "private func confirmCentralAncsReady",
                "private func observeOptionalCentralTelemetryRelay");
        String status = between(helper,
                "private func updateConnectionStatus",
                "private func setStatus");

        assertFalse(ancs.contains("centralRelayProved = true"));
        assertTrue(ancs.contains("ANCS АКТИВЕН · ЖДУ B4 ДАННЫЕ"));
        assertTrue(status.contains("centralAncsProved && centralRelayProved"));
        assertTrue(helper.contains("не повторяю ту же операцию, запрашиваю свежий namespace"));
        assertTrue(helper.contains("uuidNotAllowed on current ATT owner"));
    }

    @Test public void releaseIdentityIsOneMatchedPair() throws Exception {
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1196'"));
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v30/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(helperProject.contains("MARKETING_VERSION = 30.0"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 30"));
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
