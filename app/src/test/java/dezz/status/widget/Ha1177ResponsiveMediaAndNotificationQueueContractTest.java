/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Regression contract for the HA1177 launcher geometry and notification sequencing release. */
public final class Ha1177ResponsiveMediaAndNotificationQueueContractTest {
    @Test public void freeMediaFramesUseBothAxesButKeepPhysicalCurvesProportional()
            throws Exception {
        String proxy = source("launcher/LauncherGlobalElementProxyView.java");
        String panel = source("launcher/media/MediaPanelView.java");

        assertTrue(proxy.contains("isResponsiveMediaControl(source)"));
        assertTrue(proxy.contains("MediaPanelConfig.PROGRESS"));
        assertTrue(proxy.contains("MediaPanelConfig.VOLUME"));
        assertTrue(proxy.contains("viewportWidth / sourceWidth, viewportHeight / sourceHeight"));
        assertTrue(proxy.contains("sourceToScreenScaleY / sourceToScreenScaleX"));
        assertTrue(proxy.contains("float uniformScale = Math.min("));

        String progress = between(panel,
                "class ResponsiveProgressBar extends View",
                "/** Touch-capable volume track");
        assertTrue(progress.contains("float radiusX = radiusY * scaleY / scaleX"));
        assertTrue(progress.contains("canvas.drawRoundRect(bounds, radiusX, radiusY"));
        assertFalse(progress.contains("drawCircle"));
        assertFalse(progress.contains("drawOval"));

        String volume = between(panel,
                "class ResponsiveVolumeBar extends View",
                "private static float matrixScaleX");
        assertTrue(volume.contains("thumbRadiusY * scaleY / scaleX"));
        assertTrue(volume.contains("trackRadiusY * scaleY / scaleX"));
        assertTrue(volume.contains("canvas.drawOval(bounds, progressPaint)"));
        assertFalse(volume.contains("canvas.drawCircle"));
    }

    @Test public void progressTouchSeeksTheExactVisiblePlayerAndHasNoThumb() throws Exception {
        String panel = source("launcher/media/MediaPanelView.java");
        String controller = source("launcher/LauncherMediaController.java");
        String command = source("launcher/MediaResumeCommand.java");

        assertTrue(panel.contains("progress.setOnProgressChanged((value, fromUser) ->"));
        assertTrue(panel.contains("controls.seekTo(positionMs)"));
        assertTrue(panel.contains("controls.finishSeek(positionMs)"));
        assertTrue(panel.contains("progress == null || !progress.isPressed()"));
        assertTrue(controller.contains("public void seekTo(long positionMs)"));
        assertTrue(command.contains("controller.getTransportControls().seekTo("));
        assertFalse(command.contains("seekTo(Math.max(0L, positionMs));\n"
                + "                return send("));
    }

    @Test public void burstNotificationsUseAOneSecondFifoWithoutReplacingTheVisibleCard()
            throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("PHONE_NOTIFICATION_QUEUE_SLOT_MS = 1_000L"));
        assertTrue(service.contains("ArrayDeque<QueuedPhoneNotification>"));
        assertTrue(service.contains("queuedPhoneNotifications.addLast(delivery)"));
        assertTrue(service.contains("queuedPhoneNotifications.pollFirst()"));
        assertTrue(service.contains("hasActiveRoutinePhoneNotificationDestination()"));
        assertTrue(service.contains("postDelayed(phoneNotificationQueueAdvance,"));

        String changed = between(service,
                "private void onPhoneValuesChanged(",
                "private void rememberPhoneNotificationItems(");
        assertTrue(changed.contains("enqueuePhoneNotification(presentation, selected)"));
        assertFalse(changed.contains("updatePhoneNotificationFieldStates("));

        String present = between(service,
                "private boolean presentPhoneNotification(",
                "private boolean hasActiveRoutinePhoneNotificationDestination(");
        assertTrue(present.contains("updatePhoneNotificationFieldStates("));
        assertTrue(present.contains("showPhoneStatusNotification("));
        assertTrue(present.contains("showPhonePopupNotification("));
    }

    @Test public void phonePopupEditorExposesThePersistedRuntimePositionLock() throws Exception {
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        String controller = source("popup/PopupOverlayController.java");
        String config = source("popup/PopupOverlayConfig.java");

        assertTrue(editor.contains(
                "Заблокировать положение всплывающего уведомления"));
        assertTrue(editor.contains("checked -> overlay.positionLocked = checked"));
        assertTrue(controller.contains("if (!isPositionLocked()"));
        assertTrue(controller.contains("return currentConfig != null "
                + "&& currentConfig.positionLocked"));
        assertTrue(config.contains("put(\"positionLocked\", positionLocked)"));
        assertTrue(config.contains("optBoolean(\"positionLocked\""));
    }

    @Test public void releaseIdentityAdvancesToHa1177() throws Exception {
        String build = rootProject("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1188'"));
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
            Path settings = current.resolve("settings.gradle");
            Path candidate = current.resolve(relative);
            if (Files.isRegularFile(settings) && Files.isRegularFile(candidate)) {
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
