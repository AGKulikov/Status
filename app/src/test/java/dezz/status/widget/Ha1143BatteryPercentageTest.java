/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract for the level-aware frameless iPhone battery and optional percentage. */
public final class Ha1143BatteryPercentageTest {
    @Test public void statusBarUsesOptionalLiveNumberWithoutPercentSign() throws Exception {
        String widget = source("WidgetService.java");
        String icon = source("OutlineImageView.java");
        String preferences = source("Preferences.java");

        assertTrue(widget.contains("prefs.phoneBattery.showPercentage.get() ? battery : null"));
        assertTrue(preferences.contains("phoneBatteryShowPercentage\", true"));
        assertTrue(icon.contains("String text = String.valueOf(batteryPercent)"));
        assertTrue(icon.contains("contrastColor(batteryFillColor)"));
        assertTrue(icon.contains("imageLevel / 10_000f"));
        assertTrue(icon.contains("canvas.clipRect(bodyLeft"));
    }

    @Test public void driverAndLauncherTilesReuseTheSameLivePercentage() throws Exception {
        String widget = source("WidgetService.java");
        String panel = source("launcher/information/InformationPanelView.java");
        String snapshot = source("StatusBrickSnapshot.java");

        assertTrue(widget.contains(
                "batteryPercent = prefs.phoneBattery.showPercentage.get() ? battery : null"));
        assertTrue(snapshot.contains("@Nullable public final Integer batteryPercent"));
        assertTrue(panel.contains("setBatteryPercent(status.batteryPercent"));
        assertTrue(panel.contains("setBatteryPercent(null, Color.WHITE)"));
    }

    @Test public void percentageAndFillScaleInsideOneBatteryBody() throws Exception {
        String icon = source("OutlineImageView.java");
        String widget = source("WidgetService.java");
        String vector = resource("drawable/ic_status_iphone_battery.xml");

        assertTrue(icon.contains("14.5f / 32f"));
        assertTrue(icon.contains("Math.max(0, Math.min(100, percent))"));
        assertTrue(widget.contains(
                "batteryIcon.setImageLevel(battery == null ? 0 : battery * 100)"));
        assertTrue(widget.contains("iconLevel = battery == null ? 0 : battery * 100"));
        assertTrue(icon.contains("drawBatteryLevel(canvas)"));
        assertTrue(icon.contains("terminal communicates the battery orientation"));
        assertTrue(vector.contains("<vector"));
        assertFalse(vector.contains("<layer-list"));
        assertFalse(vector.contains("<clip"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String resource(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "res").resolve(relative);
        Path app = Paths.get("src", "main", "res").resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
