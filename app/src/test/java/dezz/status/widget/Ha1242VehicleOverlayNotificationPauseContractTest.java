/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract for camera/360/PAS notification pause and ordered recovery. */
public final class Ha1242VehicleOverlayNotificationPauseContractTest {
    @Test
    public void vehicleSafetyOverlayCannotBeBypassedByForegroundMaximumWait() throws Exception {
        String service = source("WidgetService.java");
        assertFalse(service.contains("phoneExternalOverlayDeadlineBypass"));
        assertFalse(service.contains("vehicle overlay pause deadline reached"));
        assertFalse(service.contains("vehicle overlay maximum wait reached"));
        assertTrue(service.contains(
                "if (shouldPausePhoneNotificationForExternalOverlay()) return;"));
        assertTrue(service.contains(
                "prefs.phoneNotificationDelayForExternalOverlays.get()\n"
                        + "                && phoneExternalOverlayActive) return true;"));
    }

    @Test
    public void authoritativeCloseResumesLiveCardsAndReleasesHeldQueue() throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("resumePhoneNotificationAfterExternalOverlay()"));
        assertTrue(service.contains("releaseAllDeferredPhoneNotifications()"));
        assertTrue(service.contains("deferred delivery released key="));
        assertTrue(service.contains("mainHandler.postDelayed(phoneNotificationQueueAdvance"));
        assertTrue(service.contains("activePhonePopupNotification = presentation"));
    }

    @Test
    public void freshAncsListDropsOnlyNotificationsRemovedWhileCameraWasOpen() throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains("phoneNotificationStillCurrent"));
        assertTrue(service.contains("items.updatedAt < presentation.receivedAt"));
        assertTrue(service.contains("deferred delivery dropped stale key="));
        assertTrue(service.contains("staleStatus="));
        assertTrue(service.contains("stalePopup="));
    }

    @Test
    public void repeatedCameraCyclesFreezeTheCurrentRemainderEveryTime() throws Exception {
        String service = source("WidgetService.java");
        assertTrue(service.contains(
                "Math.max(1L, activePhoneNotificationExpiresAt - now)"));
        assertTrue(service.contains(
                "Math.max(1L, activePhonePopupNotificationExpiresAt - now)"));
        assertTrue(service.contains("activePhoneNotificationExpiresAt = now + statusRemaining"));
        assertTrue(service.contains("activePhonePopupNotificationExpiresAt = now + popupRemaining"));
        assertTrue(service.contains("if (shouldPause == phoneNotificationOverlayPaused) return;"));
        assertTrue(service.contains("if (shouldPause) pausePhoneNotificationForExternalOverlay()"));
        assertTrue(service.contains("else resumePhoneNotificationAfterExternalOverlay()"));
    }

    private static String source(String relative) throws Exception {
        Path path = Paths.get("src/main/java/dezz/status/widget").resolve(relative);
        if (!Files.exists(path)) {
            path = Paths.get("app/src/main/java/dezz/status/widget").resolve(relative);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
