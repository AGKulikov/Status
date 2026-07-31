/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guard rails for the two driver-panel regressions reported from HA1138. */
public final class Ha1139RegressionContractTest {
    @Test public void acceptedClimateGestureNeverFallsBackToASecondTap() throws Exception {
        String controller = source("driver/DriverPanelOverlayController.java");
        String accessibility = source("WidgetAccessibilityService.java");

        String gesture = between(controller,
                "if (WidgetAccessibilityService.performTap(target.x, target.y",
                "}, PROXY_TAP_SETTLE_MS);");
        assertTrue(gesture.contains("restore.run();"));
        assertFalse(gesture.contains("else fallbackStockClimateTap"));
        assertTrue(accessibility.contains("return dispatchTap(current, main, x, y, callback)"));
        assertTrue(accessibility.contains("return current.dispatchGesture(gesture"));
    }

    @Test public void favoriteRailTapWinsOverTheSameOutsideTouch() throws Exception {
        String controller = source("driver/DriverPanelOverlayController.java");

        assertTrue(controller.contains("FAVORITES_OUTSIDE_DISMISS_DELAY_MS = 120L"));
        assertTrue(controller.contains("scheduleFavoriteOutsideDismiss(panelId)"));
        assertTrue(controller.contains("cancelPendingFavoriteOutsideDismiss(panelId);"));
        assertTrue(controller.contains(
                "mainHandler.postDelayed(pending, FAVORITES_OUTSIDE_DISMISS_DELAY_MS)"));
    }

    @Test public void driverAllAppsWindowNeverCoversTheRail() throws Exception {
        String controller = source("driver/DriverPanelOverlayController.java");
        String method = between(controller, "public void showAllApps()",
                "public void showFavorites(");

        assertTrue(method.contains("metrics.widthPixels - physicalWidth"));
        assertTrue(method.contains("attachedType, drawerWidth, metrics.heightPixels, drawerWindowX"));
        assertTrue(method.contains(
                "metrics.widthPixels, metrics.widthPixels, false) + drawerLeft"));
        assertTrue(method.contains(
                "ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT"));
        assertFalse(method.contains("drawerParams.leftMargin = drawerLeft"));
        assertTrue(controller.contains("FLAG_NOT_TOUCH_MODAL"));
        assertTrue(controller.contains("params.x = Math.max(0, x)"));
    }

    @Test public void iphoneIndicatorsAreIndependentBricksAndReusableInformationTiles()
            throws Exception {
        String widget = source("WidgetService.java");
        String catalog = source("launcher/information/StatusBarInformationCatalog.java");
        String layout = resource("layout/overlay_status_widget.xml");

        assertTrue(widget.contains("phonePercent(\"network.signal\")"));
        assertTrue(widget.contains("phonePercent(\"battery.level\")"));
        assertTrue(widget.contains("battery != null && battery <= 10"));
        assertTrue(widget.contains("battery != null && battery <= 20"));
        assertTrue(layout.contains("@+id/phoneCellularStatusIcon"));
        assertTrue(layout.contains("@+id/phoneBatteryStatusIcon"));
        assertTrue(catalog.contains("BrickType.PHONE_CELLULAR"));
        assertTrue(catalog.contains("BrickType.PHONE_BATTERY"));
    }

    @Test public void iphoneWifiUsesLiveRssiAndProgressiveGreenLevels() throws Exception {
        String widget = source("WidgetService.java");
        String levels = resource("drawable/ic_status_iphone_wifi_level.xml");
        String full = resource("drawable/ic_status_iphone_wifi_4.xml");

        assertTrue(widget.contains("WifiManager.calculateSignalLevel(info.getRssi(), 4)"));
        assertTrue(widget.contains("icon.setImageLevel(wifiSignalLevel * 2500)"));
        assertTrue(levels.contains("android:maxLevel=\"10000\""));
        assertTrue(full.contains("@color/iphone_signal_green"));
    }

    @Test public void bluetoothFillRequiresTheCurrentlyReadableAncsFeed() throws Exception {
        String widget = source("WidgetService.java");
        assertTrue(widget.contains("isPhoneNotificationPathAvailable()"));
        assertTrue(widget.contains("phoneStatusValues.get(\"notifications.items\")"));
        assertTrue(widget.contains("return phoneAncsReady && profileActive && feedActive"));
        assertTrue(widget.contains("Math.min(1, Math.max(0, prefs.bluetooth.outlineWidth.get()))"));
    }

    @Test public void mediaWidthSliderAllowsTheWholePhysicalRow() throws Exception {
        String adapter = source("BrickListAdapter.java");
        assertTrue(adapter.contains("float upper = Math.max("));
        assertTrue(adapter.contains(", screenW);"));
        assertFalse(adapter.contains("screenW * 0.8F"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget");
        if (!Files.isDirectory(root)) {
            root = Paths.get("src", "main", "java", "dezz", "status", "widget");
        }
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String resource(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "res");
        if (!Files.isDirectory(root)) root = Paths.get("src", "main", "res");
        return new String(Files.readAllBytes(root.resolve(relative)),
                StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) {
            throw new AssertionError("Missing range: " + start + " -> " + end);
        }
        return source.substring(from, to);
    }
}
