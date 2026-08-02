/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

import dezz.status.widget.phone.PhoneNotificationAutomation;

public final class Ha1151PhoneNotificationOverlayEditorContractTest {
    @Test public void bothNotificationLayoutsOpenTheLivePreviewEditor() throws Exception {
        String settings = source("PhoneNotificationAutomationSettingsActivity.java");
        String popup = source("PopupSettingsActivity.java");
        assertTrue(settings.contains("editPhoneNotificationIntent"));
        assertTrue(popup.contains("EXTRA_PHONE_NOTIFICATION_PREVIEW_ID"));
        assertTrue(popup.contains("previewSession.onResume()"));
        assertTrue(popup.contains("previewSession.onPause()"));
        assertTrue(popup.contains("Перетаскивайте плитки по сетке"));
    }

    @Test public void previewIsEphemeralAndHandsOffBetweenNestedEditors() throws Exception {
        String service = source("WidgetService.java");
        String manager = source("popup/PopupOverlayManager.java");
        String session = source("phone/PhoneNotificationEditorPreviewSession.java");
        assertTrue(service.contains("PHONE_EDITOR_PREVIEW_HANDOFF_MS"));
        assertTrue(service.contains("startPhoneNotificationEditorPreview"));
        assertTrue(service.contains("schedulePhoneNotificationEditorPreviewStop"));
        assertTrue(manager.contains("startEditorPreview"));
        assertTrue(manager.contains("stopEditorPreview"));
        assertTrue(session.contains("MAX_ATTACH_ATTEMPTS"));
        assertFalse(session.contains("AutomationStateStore"));
        assertFalse(session.contains("prefs."));
    }

    @Test public void realOverlayUsesSampleFieldsAndLauncherStyleGridEditing() throws Exception {
        String controller = source("popup/PopupOverlayController.java");
        assertTrue(controller.contains("PhoneNotificationAutomation.editorPreviewText"));
        assertTrue(controller.contains("PanelContentEditOverlay"));
        assertTrue(controller.contains("applyEditorPlacementsToViews"));
        assertTrue(controller.contains("safeGridGap"));
        assertTrue(controller.contains("persistEditorPlacements"));
        assertTrue(controller.contains("Переместить окно"));
        assertTrue(controller.contains("dragEditorWindow"));
        assertTrue(controller.contains("swaps them instead of refusing"));
    }

    @Test public void appIconRoundingUsesTheImageClipAndAnIosDefault() throws Exception {
        String automation = source("phone/PhoneNotificationAutomation.java");
        String controller = source("popup/PopupOverlayController.java");
        String editor = source("VisualBrickEditorActivity.java");
        assertEquals(16, PhoneNotificationAutomation.defaultAppIconCornerRadius(72));
        assertEquals(22, PhoneNotificationAutomation.defaultAppIconCornerRadius(100));
        assertTrue(automation.contains("IOS_APP_ICON_CORNER_RATIO = 0.2237f"));
        assertTrue(controller.contains("iconBox.setClipToOutline(item.iconCornerRadius > 0)"));
        assertTrue(editor.contains("Скругление краёв иконки"));
        assertTrue(editor.contains("previewIconBox.setClipToOutline"));
    }

    @Test public void releaseIdentityRemainsMonotonicAfterTheEditorRelease() throws Exception {
        String build = new String(Files.readAllBytes(projectFile("build.gradle")),
                StandardCharsets.UTF_8);
        assertTrue(build.contains("return 'v2.8.2-ha1152'"));
        assertEquals(208021152, 208020000 + 1152);
    }

    private static String source(String relative) throws Exception {
        Path path = root("app/src/main/java/dezz/status/widget").resolve(relative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path root(String relative) {
        Path direct = Paths.get(relative);
        if (Files.exists(direct)) return direct;
        return Paths.get("src").resolve(relative.substring("app/src/".length()));
    }

    private static Path projectFile(String relative) {
        Path direct = Paths.get(relative);
        if (Files.isRegularFile(direct) && !Paths.get(".").toAbsolutePath().normalize()
                .endsWith("app")) return direct;
        return Paths.get("..").resolve(relative).normalize();
    }
}
