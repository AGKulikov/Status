/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 21:58 road and parktronic journals. */
public final class Natro234RoadLogRecoveryContractTest {
    @Test public void releaseIdentityAndUnchangedHelperAreExplicit() throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.4-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.4-HELPER69-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.3.4')"));
        assertTrue(build.contains("return 208021258"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.4'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021258'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
    }

    @Test public void yandexUsesReceiverThenExactBrowserSessionWithoutUi() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(controller.contains(
                "requestYandexBrowserBootstrap = yandexBootRecovery"));
        assertTrue(controller.contains("!yandexReceiverGraceActive"));
        assertTrue(controller.contains("YANDEX_RECEIVER_GRACE_MS = 10_000L"));
        assertTrue(command.contains("route=exact_foreground_receiver"));
        assertTrue(command.contains("route=exact_session_play"));
        assertTrue(command.contains("route=waiting_for_exact_session"));
        assertTrue(command.contains("isUsablePlaySession"));
        assertFalse(browser.contains("startService("));
        assertTrue(browser.contains("new MediaBrowser(context, SERVICE"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
    }

    @Test public void hfpBackfillRequiresLiveAgEvidenceAndOutlastsEarlyBoot()
            throws Exception {
        String phone = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String helperTelemetry = between(phone,
                "private void applyHelperTelemetryV2(",
                "private void applyStandardBatteryPercentage(");
        String hfpEvent = between(phone,
                "private void applyHfpEvent(",
                "private void applyHfpAudioState(");

        assertTrue(phone.contains("hfpNetworkLiveSeenThisConnection"));
        assertTrue(phone.contains(
                "1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 16_000L, 32_000L"));
        assertTrue(phone.contains("hfp-initial-state"));
        assertFalse(helperTelemetry.contains("hfpNetworkLiveSeenThisConnection = true"));
        assertTrue(hfpEvent.contains("hfpNetworkLiveSeenThisConnection = true"));
    }

    @Test public void parkingDistanceSignalPausesIndependentlyFromCamera() throws Exception {
        String policy = read("app/src/main/java/dezz/status/widget/car/"
                + "EcarxExternalOverlayPolicy.java");
        String fallback = read("app/src/geely/java/dezz/status/widget/car/"
                + "EcarxSignalFallback.java");
        String integration = read("app/src/geely/java/dezz/status/widget/car/"
                + "GeelyCarIntegration.java");

        assertTrue(policy.contains("PROPERTY_PARKING_DISTANCE_CONTROL_STATUS = 28995"));
        assertTrue(policy.contains("parkingDistanceStatus == 2"));
        assertTrue(policy.contains("parkingDistanceStatus == 3"));
        assertTrue(fallback.contains(
                "EcarxExternalOverlayPolicy.PROPERTY_PARKING_DISTANCE_CONTROL_STATUS"));
        assertTrue(integration.contains("externalOverlayParkingRaw"));
        assertTrue(integration.contains("externalOverlaySwitchRaw, externalOverlayVisionRaw,"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
    }

    private static String read(String relative) throws Exception {
        return new String(Files.readAllBytes(projectRoot().resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static Path projectRoot() {
        Path current = Paths.get("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("project root not found");
        return current;
    }
}

