/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1191 regressions derived from the 17:49 handoff race and the KX11 byte-array trace. */
public final class Ha1191AncsProofAndTypedLimiterContractTest {
    @Test public void phaseTwoIsNeverCancelledAndNeedsExplicitReadyProof() throws Exception {
        String transport = project(
                "app/src/main/java/dezz/status/widget/phone/transport/IphoneAncsTransport.java");
        String handoff = between(transport,
                "private void beginAncsHandoffReconnect",
                "private void cancelAncsHandoffReconnectTimeout");
        String incoming = between(transport,
                "private boolean handleIncomingAncsHandoffLink",
                "private boolean canAcceptAncsReady");

        assertFalse(handoff.contains("cancelConnection"));
        assertTrue(handoff.contains("Android не ставит delayed cancel"));
        assertTrue(incoming.contains("didConnect ещё не считается готовностью ANCS"));
        assertTrue(transport.contains("\"ANCS-READY\".equals(command)"));
        assertTrue(transport.contains("ANCS-SUBSCRIBED"));
        assertTrue(transport.contains("DATA_SOURCE + NOTIFICATION_SOURCE CCCD success"));
    }

    @Test public void helperTurnsGreenOnlyAfterAndroidCccdAcknowledgement() throws Exception {
        String helper = project("ios/KX11-iPhone-ANCS-Helper-v29/"
                + "KX11ANCSHelper/ViewController.swift");
        String status = between(helper,
                "private func updateConnectionStatus",
                "private func setStatus");

        assertTrue(helper.contains("Data(\"ANCS-READY\".utf8)"));
        assertTrue(helper.contains("command == \"ANCS-SUBSCRIBED\""));
        assertTrue(helper.contains("confirmCentralAncsReady"));
        assertTrue(status.contains("centralHandoffActive && centralAncsProved"));
        assertTrue(status.contains("B4 АКТИВЕН · ЖДУ ANCS ACK"));
        assertFalse(status.contains("centralRelayProved && telemetrySubscribers.isEmpty"));
    }

    @Test public void limiterAggregatesUseVendorBytesAccessorWithGlobalArea() throws Exception {
        String catalog = project(
                "app/src/main/java/dezz/status/widget/car/EcarxAdasSignalCatalog.java");
        String fallback = project(
                "app/src/geely/java/dezz/status/widget/car/EcarxSignalFallback.java");

        assertTrue(catalog.contains("BINARY_DISCOVERY_PROPERTY_IDS"));
        assertTrue(catalog.contains("33287"));
        assertTrue(catalog.contains("33292"));
        assertTrue(catalog.contains("33462"));
        assertTrue(catalog.contains("33655"));
        assertTrue(fallback.contains("ECARX_GLOBAL_AREA = 1"));
        assertTrue(fallback.contains("getBytesProperty"));
        assertTrue(fallback.contains("bytesReader.invoke(manager, propertyId, ECARX_GLOBAL_AREA)"));
        assertTrue(fallback.contains("listener.onAdasBinarySignal"));
    }

    @Test public void releaseIdentityAdvancesAsMatchedPair() throws Exception {
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1191'"));
        String helperProject = project("ios/KX11-iPhone-ANCS-Helper-v29/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        assertTrue(helperProject.contains("MARKETING_VERSION = 29.0"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 29"));
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
