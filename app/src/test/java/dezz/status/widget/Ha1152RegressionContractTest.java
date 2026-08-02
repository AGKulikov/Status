/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Contracts for the four user-visible HA1152 rendering regressions. */
public final class Ha1152RegressionContractTest {
    @Test public void batteryUsesLiveHelperLevelInBothSurfaces() throws Exception {
        String widget = source("WidgetService.java");
        String image = source("OutlineImageView.java");
        assertTrue(widget.contains(
                "batteryIcon.setImageLevel(battery == null ? 0 : battery * 100)"));
        assertTrue(widget.contains("iconLevel = battery == null ? 0 : battery * 100"));
        assertTrue(image.contains("float fraction = imageLevel / 10_000f"));
        assertTrue(image.contains("drawBatteryLevel(canvas)"));
    }

    @Test public void mediaTimelineIsOffsetUnderTheActualTitle() throws Exception {
        String widget = source("WidgetService.java");
        assertTrue(widget.contains("binding.mediaTitleText.getLeft()"));
        assertTrue(widget.contains("params.setMarginStart(leadingMargin)"));
        assertTrue(widget.contains(
                "applyMediaChildAlignment(binding.mediaProgressBar, prefs.media.alignment.get())"));
    }

    @Test public void launcherDeviceNameUsesTheSameLiveStateColour() throws Exception {
        String launcher = source("LauncherActivity.java");
        assertTrue(launcher.contains("@Nullable final TextView titleLabel"));
        assertTrue(launcher.contains("SmartHomeTileColorPolicy.contentColor("));
        assertTrue(launcher.contains("binding.titleLabel.setTextColor(Color.parseColor(contentColor))"));
    }

    @Test public void DriverFavoritesSubscribeToVehicleStateAndRenderItsLevel() throws Exception {
        String driver = source("driver/DriverPanelOverlayController.java");
        assertTrue(driver.contains("drawerCarBindings"));
        assertTrue(driver.contains("carIntegration.subscribeControlStates(ids, carStateListener)"));
        assertTrue(driver.contains("shortcut.kind == LauncherShortcutStore.Kind.CAR && !liveClimate"));
        assertTrue(driver.contains("binding.stateLabel.setText(state == null ? \"…\" : state.valueLabel)"));
        assertTrue(driver.contains("applyCarState(binding, carControlStates.get(shortcut.target))"));
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
