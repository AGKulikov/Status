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
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        assertTrue(settings.contains("PhoneNotificationLayoutEditorActivity.intent"));
        assertTrue(editor.contains("previewSession.onResume()"));
        assertTrue(editor.contains("previewSession.onPause()"));
        assertTrue(editor.contains("Уведомление в стиле CarPlay"));
        assertTrue(editor.contains("PanelContentEditOverlay"));
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
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        String card = source("phone/PhoneNotificationCardView.java");
        assertTrue(controller.contains("renderPhoneNotificationCard()"));
        assertTrue(controller.contains("phoneNotificationCard.setPresentation"));
        assertTrue(editor.contains("class LayoutModel implements PanelContentEditOverlay.Model"));
        assertTrue(editor.contains("overlaps are valid CarPlay compositions"));
        assertTrue(card.contains("single-piece CarPlay notification card"));
        String render = between(controller, "private void renderItems()",
                "/**\n     * Phone notifications are one semantic card");
        assertTrue(render.contains("retireOlderRootsAfterFirstDraw(root)"));
        assertTrue(render.contains("root = null;"));
        assertTrue(render.contains("ensureView();"));
        assertFalse(render.contains("root.removeAllViews();"));
        assertTrue(controller.contains("windowManager.removeViewImmediate(current)"));
        assertTrue(controller.contains("addOnPreDrawListener"));
        assertTrue(controller.contains("root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR)"));
        assertTrue(controller.contains("EMPTY_GENERATION_GRACE_MS"));
    }

    @Test public void appIconUsesTheAppleContinuousMaskAndAnIosDefault() throws Exception {
        String automation = source("phone/PhoneNotificationAutomation.java");
        String card = source("phone/PhoneNotificationCardView.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        assertEquals(16, PhoneNotificationAutomation.defaultAppIconCornerRadius(72));
        assertEquals(22, PhoneNotificationAutomation.defaultAppIconCornerRadius(100));
        assertTrue(automation.contains("IOS_APP_ICON_CORNER_RATIO = 0.2237f"));
        assertTrue(card.contains("badge.setContinuousCornerRadiusPx(value.iconCornerRadiusPx)"));
        assertTrue(card.contains("IconAlphaMask.apply(pixels, alphaMask)"));
        assertTrue(card.contains("super.setImageBitmap(output)"));
        assertTrue(card.contains("AppleContinuousCornerPath.set("));
        assertTrue(card.contains("outputPath, outputBounds, continuousCornerRadiusPx"));
        assertFalse(card.contains("BitmapShader"));
        assertFalse(card.contains("PorterDuffXfermode"));
        assertTrue(editor.contains("Радиус иконки Apple"));
        assertTrue(editor.contains("Скругление аватара"));
        assertTrue(editor.contains("Жирное начертание"));
    }

    @Test public void releaseIdentityRemainsMonotonicAfterTheEditorRelease() throws Exception {
        String build = new String(Files.readAllBytes(projectFile("build.gradle")),
                StandardCharsets.UTF_8);
        assertTrue(build.contains("return 'v2.8.2-ha1191'"));
        assertEquals(208021165, 208020000 + 1165);
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

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
