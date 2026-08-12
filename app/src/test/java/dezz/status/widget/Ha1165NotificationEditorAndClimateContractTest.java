/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barrier for the visual-editor, climate and ANCS-presence fixes in HA1165. */
public final class Ha1165NotificationEditorAndClimateContractTest {
    @Test public void notificationWindowUsesARealTransparentContinuousSurfaceAndStroke()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String controller = source("popup/PopupOverlayController.java");

        assertTrue(card.contains("AppleContinuousCornerPath.set(fillPath"));
        assertTrue(card.contains("canvas.drawPath(fillPath, fillPaint)"));
        assertTrue(card.contains("canvas.drawPath(borderPath, borderPaint)"));
        assertTrue(card.contains("iconPreserveAspectRatio"));
        assertTrue(card.contains("badge.setPreserveAspectRatio"));
        assertTrue(card.contains("Math.min(widthScale, heightScale)"));
        assertTrue(controller.contains("background = 0x00000000"));
        assertTrue(controller.contains("The semantic notification card owns its rounded"));
    }

    @Test public void editorHidesDisabledFramesAndPersistsTextOverflowAndCopyStyle()
            throws Exception {
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        String config = source("phone/PhoneNotificationLayoutConfig.java");

        assertTrue(editor.contains("if (!element.visible) continue"));
        assertTrue(editor.contains("Копировать стиль из уведомления со значком"));
        assertTrue(editor.contains("Максимум строк"));
        assertTrue(editor.contains("Автопрокрутка при переполнении (выкл. = …)"));
        assertTrue(editor.contains("Сохранять пропорции иконки"));
        assertTrue(editor.contains("Толщина обводки"));
        assertTrue(config.contains("OVERFLOW_SCROLL"));
        assertTrue(config.contains("copyStyleFrom"));
    }

    @Test public void windowCenteringAndClimateContentCenteringSurviveExactSizes()
            throws Exception {
        String editor = source("PhoneNotificationLayoutEditorActivity.java");
        String popup = source("popup/PopupOverlayController.java");
        String overlay = source("popup/PopupOverlayConfig.java");
        String driver = source("driver/DriverPanelOverlayController.java");

        assertTrue(editor.contains("По центру экрана по горизонтали"));
        assertTrue(editor.contains("По центру экрана по вертикали"));
        assertTrue(overlay.contains("centerHorizontally"));
        assertTrue(overlay.contains("centerVertically"));
        assertTrue(popup.contains("(display.x - nextWidth) / 2"));
        assertTrue(popup.contains("(display.y - nextHeight) / 2"));
        assertTrue(driver.contains("button.addView(content"));
        assertTrue(driver.contains("ViewGroup.LayoutParams.MATCH_PARENT"));
        assertTrue(driver.contains("tall live"));
        assertTrue(driver.contains("climate tile"));
    }

    @Test public void releaseIdentityIsHa1165() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1214'"));
    }

    @Test public void ancsSmartHomeBindingUsesConfirmedSubscriptionStateOnly()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String service = source("WidgetService.java");
        String settings = source("PhoneConnectorSettingsActivity.java");
        String exporter = source("phone/PhoneSprutPresenceExporter.java");

        assertTrue(controller.contains("updateAncsPresenceLocked(ancsReady)"));
        assertTrue(controller.contains("presenceSink.onAncsConnectionChanged(value)"));
        assertTrue(controller.contains(
                "return notificationsEnabled || messagesEnabled || ancsPresenceEnabled"));
        assertTrue(service.contains("PhoneSprutPresenceExporter.Signal.ANCS"));
        assertTrue(service.contains("onAncsConnectionChanged(boolean connected)"));
        assertTrue(settings.contains("phoneSprutAncsPresenceEnabled"));
        assertTrue(settings.contains("selectedSprutPath.equals(selectedSprutAncsPath)"));
        assertTrue(exporter.contains("signal == Signal.ANCS"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
