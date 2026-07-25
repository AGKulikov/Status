/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Device-reported regressions fixed after the first HA1117 test build. */
public final class DriverPanelPost1117ContractTest {
    @Test
    public void climateUsesOnlyTemperatureScaleAndAirflowPictogram() throws Exception {
        String climate = read("driver/DriverClimateShortcutView.java");

        assertTrue(climate.contains("drawTemperature("));
        assertTrue(climate.contains("drawBars(canvas, width * .12f"));
        assertTrue(climate.contains("drawAirflow("));
        assertFalse(climate.contains("drawFanGlyph"));
        assertTrue(climate.contains("drawText(\"AUTO\""));
        assertTrue(climate.contains("AIRFLOW_WINDSHIELD"));
        assertTrue(climate.contains("AIRFLOW_FACE"));
        assertTrue(climate.contains("AIRFLOW_LEGS"));
    }

    @Test
    public void applicationDrawerIsOnScreenModalAndIncludesSystemPhone() throws Exception {
        String overlay = read("driver/DriverPanelOverlayController.java");
        String catalog = read("launcher/LauncherAppCatalog.java");
        String launcher = read("LauncherActivity.java");
        String settings = read("AllAppsSettingsActivity.java");

        assertTrue(overlay.contains("root.setOnClickListener(view -> dismissAllApps())"));
        assertTrue(overlay.contains("allAppsOverlayParams("));
        assertTrue(overlay.contains("drawerParams.leftMargin = drawerLeft"));
        assertTrue(overlay.contains("screenWidth, screenWidth, false"));
        assertTrue(catalog.contains("if (hidden.isEmpty()) return loadIncludingSystem(context)"));
        assertTrue(catalog.contains("for (App app : loadIncludingSystem(context))"));
        assertFalse(launcher.contains("if (!app.systemApp"));
        assertTrue(settings.contains("LauncherAppCatalog.loadIncludingSystem(this)"));
    }

    @Test
    public void informationAppearanceAndFavoriteLifetimeAreExplicit() throws Exception {
        String information = read("launcher/InformationShortcutView.java");
        String informationPanel = read(
                "launcher/information/InformationPanelView.java");
        String driverSettings = read("DriverPanelSettingsActivity.java");
        String favoriteSettings = read("DriverFavoritesSettingsActivity.java");
        String shortcuts = read("launcher/LauncherShortcutStore.java");
        String overlay = read("driver/DriverPanelOverlayController.java");

        assertTrue(information.contains(
                "setFixedCellBackgroundColor(shortcut.backgroundColor)"));
        assertTrue(informationPanel.contains("fixedCellBackgroundColor"));
        assertTrue(driverSettings.contains("Показывать значок слева"));
        assertTrue(driverSettings.contains(
                "Текст, шрифт, выравнивание и отступы"));
        assertTrue(shortcuts.contains("informationHorizontalAlignment"));
        assertTrue(shortcuts.contains("informationPaddingLeftPx"));
        assertTrue(information.contains("item.fontFamily"));
        assertTrue(favoriteSettings.contains("Закрывать панель после нажатия"));
        assertTrue(shortcuts.contains("closeFavoritePanelAfterAction = false"));
        assertTrue(shortcuts.contains(
                "json.optBoolean(\"closeFavoritePanelAfterAction\", false)"));
        assertTrue(overlay.contains("if (shortcut.closeFavoritePanelAfterAction)"));
        assertTrue(overlay.contains(
                "panelOnRight ? panelX - width : panelX + physicalWidth"));
        assertTrue(overlay.contains("FLAG_WATCH_OUTSIDE_TOUCH"));
        assertTrue(overlay.contains("MotionEvent.ACTION_OUTSIDE"));
        assertTrue(overlay.contains("background.setCornerRadii(panelOnRight"));
    }

    @Test
    public void yandexWindowButtonsDoNotAddASecondClickSound() throws Exception {
        String overlay = read("driver/DriverPanelOverlayController.java");
        String launcher = read("LauncherActivity.java");

        assertTrue(overlay.contains("stockClimate || opensWindowedYandex(shortcut)"));
        assertTrue(overlay.contains("button.setSoundEffectsEnabled(false)"));
        assertTrue(launcher.contains("if (!addButton && opensWindowedYandex(shortcut))"));
        assertTrue(launcher.contains("card.setSoundEffectsEnabled(false)"));
    }

    private static String read(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }
}
