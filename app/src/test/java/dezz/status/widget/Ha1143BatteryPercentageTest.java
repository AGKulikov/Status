/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract for the live percentage drawn inside the iPhone battery icon. */
public final class Ha1143BatteryPercentageTest {
    @Test public void statusBarUsesLiveNumberWithoutPercentSign() throws Exception {
        String widget = source("WidgetService.java");
        String icon = source("OutlineImageView.java");

        assertTrue(widget.contains("batteryIcon.setBatteryPercent(battery, batteryColor)"));
        assertTrue(icon.contains("String text = String.valueOf(batteryPercent)"));
        assertTrue(icon.contains("batteryPercent / 100f"));
        assertTrue(icon.contains("canvas.clipRect(innerLeft, innerTop, fillRight, innerBottom)"));
        assertTrue(icon.contains("contrastColor(batteryFillColor)"));
    }

    @Test public void driverAndLauncherTilesReuseTheSameLivePercentage() throws Exception {
        String widget = source("WidgetService.java");
        String panel = source("launcher/information/InformationPanelView.java");
        String snapshot = source("StatusBrickSnapshot.java");

        assertTrue(widget.contains("batteryPercent = battery"));
        assertTrue(snapshot.contains("@Nullable public final Integer batteryPercent"));
        assertTrue(panel.contains("setBatteryPercent(status.batteryPercent"));
        assertTrue(panel.contains("setBatteryPercent(null, Color.WHITE)"));
    }

    @Test public void percentageScalesInsideBatteryBodyAndKeepsDynamicFill() throws Exception {
        String icon = source("OutlineImageView.java");
        String widget = source("WidgetService.java");

        assertTrue(icon.contains("4f / 32f"));
        assertTrue(icon.contains("25f / 32f"));
        assertTrue(icon.contains("14.5f / 32f"));
        assertTrue(icon.contains("Math.max(0, Math.min(100, percent))"));
        assertTrue(widget.contains("batteryIcon.setImageLevel(batteryDrawableLevel(battery))"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
