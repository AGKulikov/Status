/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the notification-loss report and mSaver 2.7 comparison. */
public final class Natro236NotificationMediaRecoveryContractTest {
    @Test public void releaseIdentityKeepsHelper69AndStableAndroidCertificate()
            throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.6-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.6-HELPER69-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.3.6')"));
        assertTrue(build.contains("return 208021260"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.6'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021260'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
        assertTrue(manifest.contains("6e9855aedc008bbdd8a7fbf3f490be07"
                + "f964b7ac658a837a1592647a08365c75"));
    }

    @Test public void onlySelectedPackagesAndExactVehicleSignalsCanHoldDelivery()
            throws Exception {
        String widget = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        String accessibility = read(
                "app/src/main/java/dezz/status/widget/WidgetAccessibilityService.java");
        String settings = read("app/src/main/java/dezz/status/widget/"
                + "PhoneNotificationAutomationSettingsActivity.java");
        String policy = read("app/src/main/java/dezz/status/widget/car/"
                + "EcarxExternalOverlayPolicy.java");
        String tracking = between(widget,
                "private boolean phoneNotificationForegroundTrackingNeeded()",
                "private boolean phoneNotificationBlockedByForeground()");

        assertTrue(widget.contains("prefs.phoneNotificationDelayInPackages.get()"));
        assertTrue(widget.contains(
                "setPhoneExternalOverlayActive(phoneVehicleOverlayActive)"));
        assertTrue(widget.contains("phoneExternalOverlayDeadlineBypass = true"));
        assertTrue(widget.contains("vehicle overlay maximum wait reached"));
        assertFalse(tracking.contains("phoneNotificationDelayForExternalOverlays"));
        assertFalse(accessibility.contains("ExternalOverlayWindowPolicy"));
        assertFalse(accessibility.contains("externalOverlayWindows"));
        assertTrue(settings.contains(
                "checked && !delayPackages.isEmpty() && !foregroundTrackingAvailable()"));
        assertFalse(settings.contains(
                "phone_notification_delay_external_overlays_access"));
        assertFalse(Files.exists(projectRoot().resolve(
                "app/src/main/java/dezz/status/widget/phone/ExternalOverlayWindowPolicy.java")));
        assertTrue(policy.contains("PROPERTY_DISPLAY_SWITCH_STATUS = 29021"));
        assertTrue(policy.contains("PROPERTY_VISION_IMAGE_MODE = 29043"));
        assertTrue(policy.contains("PROPERTY_PARKING_DISTANCE_CONTROL_STATUS = 28995"));
    }

    @Test public void batteryLatchIsWrittenOnlyAfterRealPresentation() throws Exception {
        String widget = read("app/src/main/java/dezz/status/widget/WidgetService.java");
        String evaluate = between(widget, "private void handlePhoneLowBatteryAlert(",
                "private void updatePhoneNotificationFieldStates(");
        String present = between(widget, "private boolean presentPhoneNotification(",
                "private boolean hasActiveRoutinePhoneNotificationDestination()");

        assertTrue(evaluate.contains("phoneLowBatteryAlertPending"));
        assertTrue(evaluate.contains("enqueuePhoneLowBatteryAlert(level, color, stage)"));
        assertFalse(evaluate.contains("phoneLowBatteryAlertLatched.set(true)"));
        assertTrue(present.contains("markPhoneLowBatteryAlertPresented"));
        assertTrue(present.contains("phoneLowBatteryAlertLatched.set(true)"));
        assertTrue(widget.contains("phoneLowBatteryPresentedLatchMigration"));
        assertTrue(widget.contains("phonePresentationGateChanged"));
    }

    @Test public void yandexColdStartMatchesMSaverSequentialReceiverRoute() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(command.contains("DebugMediaButtonReceiver"));
        assertTrue(command.contains("route=exact_foreground_receiver"));
        assertTrue(command.contains("MAIN.postDelayed(keyUp, keyUpDelayMs)"));
        assertTrue(command.contains("YANDEX_PLAY_KEY_UP_DELAY_MS = 100L"));
        assertTrue(command.contains("route=waiting_for_exact_session"));
        assertFalse(command.contains("SystemClock.sleep"));
        assertTrue(command.contains("FLAG_RECEIVER_FOREGROUND"));
        assertTrue(controller.contains("exact foreground receiver is intentionally one-shot"));
        assertTrue(controller.contains("YANDEX_FAST_SESSION_POLL_ATTEMPTS"));
        assertTrue(browser.contains("MusicBrowserService"));
        assertFalse(controller.contains("YandexMusicBrowserStarter.requestWarmup(app)"));
        assertTrue(browser.contains("playBrowserSession"));
        assertFalse(browser.contains("startService("));
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
