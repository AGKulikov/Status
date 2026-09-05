/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Release boundary for Natro 2.2.15 / Helper 68 recovery and timing diagnostics. */
public final class Natro2215RecoveryMediaTraceContractTest {
    @Test public void releaseIdentityAndHelperPairAdvanceTogether() throws Exception {
        String build = read("build.gradle");
        String helperProject = read("ios/KX11-iPhone-ANCS-Helper-v68-personal/"
                + "KX11ANCSHelper.xcodeproj/project.pbxproj");
        String manifest = read("release-manifests/NATRO-2.2.15-HELPER68-PERSONAL.md");

        assertTrue(build.contains("if (version == '2.2.15')"));
        assertTrue(build.contains("return 208021249"));
        assertTrue(helperProject.contains("CURRENT_PROJECT_VERSION = 68;"));
        assertTrue(helperProject.contains("MARKETING_VERSION = 68.0;"));
        assertTrue(manifest.contains("Android version: `2.2.15`"));
        assertTrue(manifest.contains("Helper build: `68`"));
    }

    @Test public void optionalC5CannotPoisonAncsOwner() throws Exception {
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String helper = read("ios/KX11-iPhone-ANCS-Helper-v68-personal/"
                + "HelperPeripheralRoute.swift");
        String drain = between(route, "private void drainCarRemoteWrites()",
                "private void cancelTelemetryRefresh()");

        assertTrue(helper.contains(
                "properties: [.write, .writeWithoutResponse, .indicate]"));
        assertTrue(drain.contains("WRITE_TYPE_NO_RESPONSE"));
        assertTrue(drain.contains("CAR_REMOTE_NO_RESPONSE_SETTLE_MS"));
        assertTrue(drain.contains("ancsPreserved=true"));
        assertFalse(drain.contains("resetCurrentOwner("));
    }

    @Test public void reconnectTimersAreFastAndMeasuredEndToEnd() throws Exception {
        String policy = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "ClassicAncsRecoveryPolicy.java");
        String route = read("app/src/main/java/dezz/status/widget/phone/transport/v2/"
                + "android/AndroidCentralTransportV2.java");
        String controller = read("app/src/main/java/dezz/status/widget/phone/"
                + "PhoneConnectorController.java");
        String helper = read("ios/KX11-iPhone-ANCS-Helper-v68-personal/"
                + "HelperSwitchRuntimeCoordinator.swift");

        assertTrue(policy.contains("FIRST_BACKOFF_MS = 5_000L"));
        assertTrue(policy.contains("MAX_BACKOFF_MS = 30_000L"));
        assertTrue(route.contains("route_timer_fired armedPhase="));
        assertTrue(route.contains("service_inventory status="));
        assertTrue(controller.contains("recovery_timer fired generation="));
        assertTrue(helper.contains("UIApplication.shared.beginBackgroundTask"));
        assertTrue(helper.contains("same_role_drain fired plannedMs="));
        assertTrue(helper.contains("ble_recovery target_start totalMs="));
    }

    @Test public void mediaTraceIdentifiesEveryQueueAndDispatchBoundary() throws Exception {
        String controller = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeController.java");
        String command = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaResumeCommand.java");
        String receiver = read("app/src/main/java/dezz/status/widget/launcher/"
                + "MediaAutoResumeReceiver.java");

        for (String marker : new String[]{
                "event=receiver_boundary_enqueue",
                "event=receiver_boundary_dequeue",
                "event=lifecycle_captured",
                "event=plan_created",
                "event=in_process_timer_delivery",
                "event=alarm_delivery",
                "event=command_dispatched",
                "event=playing_observed"
        }) {
            assertTrue("missing media trace marker " + marker, controller.contains(marker));
        }
        assertTrue(command.contains("static DispatchTrace playWithTrace"));
        assertTrue(command.contains("activeSessions="));
        assertTrue(command.contains("receiverQueryError="));
        assertTrue(receiver.contains("recordAlarmDelivery(bootToken, attempt)"));
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError(start + " -> " + end);
        return source.substring(from, to);
    }

    private static String read(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path file = current.resolve(relative);
            if (Files.isRegularFile(file)) {
                return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Project file not found: " + relative);
    }
}
