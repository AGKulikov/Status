/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** HA1181 contracts for autonomous passive capture, media scrubbing and phone notification policy. */
public final class Ha1181ExpandedRecorderAndPhonePolicyContractTest {
    @Test public void manifestAndUiExposePersistentOneTimeDiagnosticsGrants()
            throws Exception {
        String manifest = project("app/src/main/AndroidManifest.xml");
        String screen = source("DiagnosticsActivity.java");
        assertTrue(manifest.contains("android.permission.READ_LOGS"));
        assertTrue(manifest.contains("android.permission.DUMP"));
        assertTrue(screen.contains("pm grant \" + packageName + \" android.permission.READ_LOGS"));
        assertTrue(screen.contains("pm grant \" + packageName + \" android.permission.DUMP"));
        assertTrue(screen.contains(
                "pm grant \" + packageName + \" android.permission.PACKAGE_USAGE_STATS"));
        assertTrue(screen.contains("GET_USAGE_STATS allow"));
        assertTrue(screen.contains("Выдать через встроенный ADB"));
        assertTrue(screen.contains("Копировать команды"));
    }

    @Test public void expandedCollectorIsSessionScopedBoundedAndPassive() throws Exception {
        String collector = source("diagnostics/PrivilegedActionCollector.java");
        String app = source("StatusWidgetApplication.java");
        String preferences = source("Preferences.java");

        assertTrue(app.contains("PrivilegedActionCollector.initialize(this)"));
        assertTrue(collector.contains("this::onRecordingChanged"));
        assertTrue(collector.contains("startLogcat(currentGeneration)"));
        assertTrue(collector.contains("stopProcesses()"));
        assertTrue(collector.contains("MAX_LOG_EVENTS = 2_500"));
        assertTrue(collector.contains("MAX_EVENTS_PER_SECOND = 45"));
        assertTrue(collector.contains("captureSnapshots(\"session_start\""));
        assertTrue(collector.contains("\"passive_only\", true"));
        assertFalse(collector.contains("setprop"));
        assertFalse(collector.contains("setenforce"));
        assertTrue(preferences.contains(
                "actionRecorderRootInputEnabled\", false"));
    }

    @Test public void optionalRootPathDropsTouchesAndKeepsOnlyEvKey() throws Exception {
        String collector = source("diagnostics/PrivilegedActionCollector.java");
        String screen = source("DiagnosticsActivity.java");
        assertTrue(collector.contains("getevent -lt"));
        assertTrue(collector.contains("contains(\"EV_KEY\")"));
        assertTrue(collector.contains("\"touch_coordinates\", false"));
        assertTrue(screen.contains("без координат касаний"));
        assertTrue(screen.contains("su не предоставил root"));
    }

    @Test public void progressSendsThrottledMovesAndAnImmediateFinalSeek() throws Exception {
        String panel = source("launcher/media/MediaPanelView.java");
        String controller = source("launcher/LauncherMediaController.java");
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        assertTrue(panel.contains("controls.seekTo(positionMs)"));
        assertTrue(panel.contains("controls.finishSeek(positionMs)"));
        assertTrue(controller.contains("SEEK_COMMAND_INTERVAL_MS"));
        assertTrue(controller.contains("dispatchPendingSeek(true)"));
        assertTrue(controller.contains("activeMediaNotificationControllers(context)"));
        assertTrue(proxy.contains("gestureSource"));
        assertTrue(proxy.contains("gestureTransform"));
        assertTrue(proxy.contains("requestDisallowInterceptTouchEvent(true)"));
    }

    @Test public void lastQueuedPhoneNotificationGetsItsOwnFullDurationAndLockMustBeTrue()
            throws Exception {
        String service = source("WidgetService.java");
        String policy = source("phone/PhoneNotificationLockPolicy.java");
        assertTrue(service.contains("PHONE_NOTIFICATION_QUEUE_SLOT_MS = 1_000L"));
        assertTrue(service.contains("releasePhoneNotificationBurstToConfiguredExpiry()"));
        String release = between(service,
                "private void releasePhoneNotificationBurstToConfiguredExpiry()",
                "private boolean phoneNotificationAllowedByLockState()");
        assertFalse(release.contains("clearPhoneStatusNotification"));
        assertFalse(release.contains("clearPhonePopupNotification"));
        assertTrue(policy.contains("Boolean.TRUE.equals(phoneLocked)"));
        assertTrue(service.contains("suppressPhoneNotificationsUnlessLockAllows()"));
    }

    @Test public void releaseIdentityAdvancesToHa1181() throws Exception {
        assertTrue(rootProject("build.gradle").contains("return 'v2.8.2-ha1215'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
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

    private static String rootProject(String relative) throws Exception {
        Path current = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 8 && current != null;
             depth++, current = current.getParent()) {
            if (!Files.isRegularFile(current.resolve("settings.gradle"))) continue;
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Root project file not found: " + relative);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
