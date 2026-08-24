/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 19:17 Android/Helper road journals. */
public final class Natro232RoadLogRecoveryContractTest {
    @Test public void releaseIdentityAndUnchangedHelperAreExplicit() throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.2-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.2-HELPER69-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.3.2')"));
        assertTrue(build.contains("return 208021256"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.2'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021256'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
    }

    @Test public void silentEnrolledOwnerNeverDrainsAndScanFailureKeepsItsTimer()
            throws Exception {
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String recovery = between(route,
                "public static BleRouteTransition<State> systemConnectionRecoveryElapsed(",
                "private static long waitSystemRecoveryMillis");
        assertTrue(recovery.contains("REASSERT_SAME_GATT"));
        assertFalse(recovery.contains("return retry("));
        assertFalse(recovery.contains("CLOSE_GATT"));

        String transport = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String failure = between(transport, "private void handleScanFailure(",
                "private void handleScanResult(");
        assertTrue(failure.contains("!retainsEnrolledSystemOwner(token)"));
        assertTrue(failure.contains("postRouteDeadline(token)"));
    }

    @Test public void c5AndPreexistingReplayAreBounded() throws Exception {
        String remote = read("app/src/main/java/dezz/status/widget/phone/"
                + "CarRemoteControllerV1.java");
        String trace = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AncsDeliveryTraceV2.java");
        assertTrue(remote.contains("lastStateFingerprints"));
        assertTrue(remote.contains("previous.longValue() == fingerprint"));
        assertTrue(remote.contains("lastStateFingerprints.clear()"));
        assertTrue(trace.contains("preExistingOnly"));
        assertTrue(trace.contains("isPowerOfTwo(this.sourceRecords)"));
    }

    @Test public void yandexRecoveryWaitsForARealProcessWithoutOpeningUi() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(controller.contains("YANDEX_MAX_ATTEMPTS = 24"));
        assertTrue(controller.contains("YANDEX_BROWSER_RETRY_COOLDOWN_MS = 30_000L"));
        assertTrue(controller.contains("repeatYandexReceiver = attempt > 0"));
        assertTrue(command.contains("\"running\".equals(processState)"));
        assertTrue(command.contains("!\"not_running\".equals(processState)"));
        assertFalse(command.contains("MediaAppLauncher"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
        assertTrue(browser.contains("new Intent().setComponent(SERVICE)"));
        assertTrue(browser.contains("new MediaBrowser(context, SERVICE"));
    }

    @Test public void overlayLifetimesLteGrowthAndSprutSlashAreCovered() throws Exception {
        String widget = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        String hint = between(widget,
                "binding.overlayContainer.setSizeChangeHint(",
                "binding.overlayContainer.setLayoutTransition(null)");
        assertTrue(hint.contains("beginBufferedTransition(true)"));
        assertFalse(hint.contains("if (newW >= oldW) return"));
        assertTrue(widget.contains("pausePhoneNotificationForExternalOverlay"));
        assertTrue(widget.contains("resumePhoneNotificationAfterExternalOverlay"));
        assertTrue(widget.contains("pausedPhoneNotificationRemainingMs"));
        assertTrue(widget.contains("updatePhonePopupAutomationExpiry(0L)"));

        String sprut = read("app/src/main/java/dezz/status/widget/sprut/"
                + "SprutHubController.java");
        assertTrue(sprut.contains("hasParserUnsafeProofCharacters(answer)"));
        assertTrue(sprut.contains("answer.indexOf('/') >= 0"));
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
