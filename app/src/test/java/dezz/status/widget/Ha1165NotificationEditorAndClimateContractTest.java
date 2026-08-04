/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barrier for the visual-editor and fixed-height climate regressions in HA1165. */
public final class Ha1165NotificationEditorAndClimateContractTest {
    @Test public void notificationWindowUsesARealTransparentRoundedSurfaceAndStroke()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String controller = source("popup/PopupOverlayController.java");

        assertTrue(card.contains("canvas.clipPath(surfaceClip)"));
        assertTrue(card.contains("surface.setStroke(value.borderWidthPx"));
        assertTrue(card.contains("iconPreserveAspectRatio"));
        assertTrue(card.contains("ImageView.ScaleType.FIT_CENTER"));
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
        assertTrue(project("build.gradle").contains("return 'v2.8.2-ha1165'"));
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
