/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression boundary for the 2026-08-24/25 reboot, ANCS and notification journals. */
public final class Natro235RoadLogRecoveryContractTest {
    @Test public void releaseIdentityAndUnchangedHelperAreExplicit() throws Exception {
        String build = read("build.gradle");
        String workflow = read(
                ".github/workflows/verify-natro-2.3.5-helper69-personal.yml");
        String manifest = read(
                "release-manifests/NATRO-2.3.5-HELPER69-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.3.5')"));
        assertTrue(build.contains("return 208021259"));
        assertTrue(workflow.contains("VERSION_NAME: '2.3.5'"));
        assertTrue(workflow.contains("VERSION_CODE: '208021259'"));
        assertTrue(workflow.contains("testGeelyDebugUnitTest assembleGeelyRelease"));
        assertTrue(workflow.contains("verify-v69-personal-contract.sh"));
        assertTrue(manifest.contains("Helper 69 is intentionally unchanged"));
        assertTrue(manifest.contains("6e9855aedc008bbdd8a7fbf3f490be07"
                + "f964b7ac658a837a1592647a08365c75"));
    }

    @Test public void registeredSilentGattRetiresBehindOneOwnerFence() throws Exception {
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "AndroidCentralRoute.java");
        String transport = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");

        assertTrue(route.contains("STARTUP_QUIET_MS = 3_000L"));
        assertTrue(transport.contains("REGISTERED_GATT_RETIRE_SETTLE_MS = 2_000L"));
        assertTrue(transport.contains("clientIfPositive=true"));
        assertTrue(transport.contains("retirementSettleRequested = true"));
        assertTrue(transport.contains("gatt_retirement_settle phase=armed"));
        assertTrue(transport.contains("ProcessGattRegistrationGateV2.release(closing)"));
        assertTrue(transport.contains("never creates a second simultaneous wrapper"));
        assertFalse(transport.contains("removeBond("));
    }

    @Test public void helperRefreshAndHfpNetworkEvidenceCannotOverwriteEachOther()
            throws Exception {
        String transport = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String phone = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String helper = between(phone, "private void applyHelperTelemetryV2(",
                "private void applyStandardBatteryPercentage(");

        assertTrue(transport.contains("TELEMETRY_INITIAL_REFRESH_MS = 100L"));
        assertTrue(transport.contains(
                "scheduleTelemetryRefreshAfter(TELEMETRY_INITIAL_REFRESH_MS)"));
        assertTrue(transport.contains("scheduleCarRemoteSubscribe(effect.token, 250L)"));
        assertTrue(phone.contains("agBundle=false, read="));
        assertTrue(phone.contains("reflectionFailure(failure)"));
        assertTrue(helper.contains("markTelemetryUpdated(true, false)"));
        assertFalse(helper.contains("markTelemetryUpdated(true, true)"));
        assertFalse(helper.contains("networkLiveSeenThisConnection = true"));
    }

    @Test public void notificationPresentationNeverWaitsForCosmeticAppName()
            throws Exception {
        String phone = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String incoming = between(phone, "private void handleAncsNotificationFields",
                "private void handleAncsTransportAppName");

        assertTrue(incoming.contains("decoded item ready; category="));
        assertTrue(incoming.contains("appName=\" + (hasDisplayName ? \"resolved\" : \"fallback\")"));
        assertTrue(incoming.contains("presentAncsNotification(token, record, true)"));
        assertTrue(phone.contains("PhoneAppCatalog.displayNameFallback(appIdentifier)"));
        assertFalse(phone.contains("APP_DISPLAY_NAME_WAIT_TIMEOUT_MS"));
        assertFalse(phone.contains("scheduleUnresolvedNotificationExpiry"));
        assertFalse(phone.contains("decoded item waiting for app name"));
    }

    @Test public void yandexRejectsStateNoneButKeepsExactReceiverDuringBrowserBind()
            throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String browser = read("app/src/main/java/dezz/status/widget/launcher/"
                + "YandexMusicBrowserStarter.java");

        assertTrue(controller.contains("YANDEX_BROWSER_RETRY_COOLDOWN_MS = 10_000L"));
        assertTrue(controller.contains("boolean yandexBootRecovery ="));
        assertTrue(command.contains("!isUsablePlaySession(state)"));
        assertTrue(command.contains("state.getState() == PlaybackState.STATE_NONE"));
        assertTrue(command.contains("yandexBootstrap = \"cooldown_active\""));
        assertTrue(command.contains("ignoredSessionState="));
        assertFalse(command.contains("route=waiting_for_exact_session"));
        assertFalse(command.contains("dispatchMediaKeyEvent"));
        assertTrue(browser.contains("CONNECTION_TIMEOUT_MS = 10_000L"));
        assertFalse(controller.contains("MediaAppLauncher.launchPackage"));
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
