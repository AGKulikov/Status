/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24 20:51 road and external-overlay journals. */
public final class Natro233FastStartupOverlayContractTest {
    @Test public void releaseIdentityKeepsHelper69Unchanged() throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.3-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.3-HELPER69-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.3.3')"));
        assertTrue(build.contains("return 208021257"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.3'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021257'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
    }

    @Test public void silentRegisteredGattRecoversAtEightSecondFence() throws Exception {
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String transport = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");

        assertTrue(route.contains("ENROLLED_SCAN_TIMEOUT_MS = 8_000L"));
        assertTrue(route.contains("base.consecutiveFailures % 2 == 0"));
        assertTrue(transport.contains("recoverRegisteredSilentGatt"));
        assertTrue(transport.contains("getDeclaredField(\"mClientIf\")"));
        assertTrue(transport.contains("exact.registrationProven = true"));
        assertTrue(transport.contains("exact.cacheRefreshRequested = true"));
        assertFalse(transport.contains("removeBond("));
    }

    @Test public void yandexRacesOneExactReceiverWithTheBrowserOnFastCadence()
            throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");

        assertTrue(controller.contains("YANDEX_MAX_ATTEMPTS = 24"));
        assertTrue(controller.contains("YANDEX_FAST_SESSION_POLL_ATTEMPTS = 8"));
        assertTrue(controller.contains("YANDEX_FAST_RECEIVER_RETRY_MS = 2_000L"));
        assertTrue(controller.contains(
                "requestYandexBrowserBootstrap = yandexBootRecovery"));
        assertTrue(command.contains("RECEIVER_AND_BROWSER_BOOTSTRAP"));
        assertTrue(command.contains("route=exact_receiver_browser_race"));
        assertTrue(command.contains("route=waiting_for_exact_session"));
        assertTrue(command.contains("KEYCODE_MEDIA_PLAY"));
        assertTrue(command.contains("new Intent(Intent.ACTION_MEDIA_BUTTON)"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));
    }

    @Test public void hfpAndCellWidthRecoverFromLateTelemetry() throws Exception {
        String phone = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String panel = read("app/src/main/java/dezz/status/widget/launcher/information/"
                + "InformationPanelView.java");

        assertTrue(phone.contains("HFP_STATE_BACKFILL_GAPS_MS"));
        assertTrue(phone.contains("queryInitialProfileState(token, adapter, "
                + "PROFILE_HEADSET_CLIENT)"));
        assertTrue(phone.contains("hfp-network-backfill"));
        assertTrue(panel.contains("PhoneCellularDisplayPolicy.measurementFallback("));
        assertTrue(panel.contains("value.setMinWidth("));
    }

    @Test public void vehicleSignalsPauseNotificationsWithoutAccessibilityEvent()
            throws Exception {
        String fallback = read("app/src/geely/java/dezz/status/widget/car/"
                + "EcarxSignalFallback.java");
        String policy = read("app/src/main/java/dezz/status/widget/car/"
                + "EcarxExternalOverlayPolicy.java");
        String widget = read("app/src/main/java/dezz/status/widget/WidgetService.java");

        assertTrue(policy.contains("PROPERTY_DISPLAY_SWITCH_STATUS = 29021"));
        assertTrue(policy.contains("PROPERTY_VISION_IMAGE_MODE = 29043"));
        assertTrue(fallback.contains("onExternalOverlaySignal(propertyId, raw)"));
        assertTrue(widget.contains(
                "setPhoneExternalOverlayActive(phoneVehicleOverlayActive)"));
        assertFalse(widget.contains("phoneAccessibilityOverlayActive"));
        assertTrue(widget.contains("pausePhoneNotificationForExternalOverlay"));
        assertTrue(widget.contains("resumePhoneNotificationAfterExternalOverlay"));
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
