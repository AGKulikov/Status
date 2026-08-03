/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** KX11 regression barriers for HA1153. */
public final class Ha1153Kx11RegressionContractTest {
    @Test public void helperTelemetryUsesTheAlreadyWorkingAndroidCentralRoute()
            throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        assertTrue(transport.contains("HELPER_TELEMETRY"));
        assertTrue(transport.contains("BluetoothGattCharacteristic.PROPERTY_NOTIFY"));
        assertTrue(transport.contains("startOptionalHelperTelemetrySubscription"));
        assertTrue(transport.contains("Helper telemetry notification accepted"));
        assertTrue(transport.contains("listener.onHelperTelemetry(telemetry)"));
    }

    @Test public void directPercentageWinsWhileChargeStateRemainsHelperOnly()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String refresh = between(controller, "private void refreshBatteryValues()",
                "private void clearBasData()");
        assertTrue(refresh.contains("PhoneBatteryLevelPolicy.resolve("));
        assertTrue(refresh.contains("genericBatteryKnown, genericBatteryLevel"));
        assertTrue(refresh.contains("basBatteryKnown, basBatteryLevel"));
        assertTrue(refresh.contains("helperPowerUpdatedAtElapsed > 0L ? helperBatteryLevel"));
        assertTrue(refresh.contains("batteryChargingSource = \"iphone_helper\""));
        assertTrue(controller.contains("Integer effectiveBatteryLevel = batteryLevel;"));
        assertTrue(controller.contains("Integer savedBatteryLevel = null"));
    }

    @Test public void allAppsIsClosedBeforeEveryDifferentDriverAction()
            throws Exception {
        String controller = source("driver/DriverPanelOverlayController.java");
        String execute = between(controller, "private void executeShortcut(",
                "private boolean executeLongShortcut(");
        assertTrue(execute.contains("if (!isAllAppsAction(shortcut.kind, shortcut.target))"));
        assertTrue(execute.contains("dismissAllApps()"));
        String executeLong = between(controller, "private boolean executeLongShortcut(",
                "private static boolean isAllAppsAction(");
        assertTrue(executeLong.contains("shortcut.longKind, shortcut.longTarget"));
        assertTrue(executeLong.contains("dismissAllApps()"));
    }

    @Test public void driverInformationSpacingIsLiteralAndCompact() throws Exception {
        String controller = source("driver/DriverPanelOverlayController.java");
        String information = between(controller, "private InformationSection buildInformationSection(",
                "private static int groupGravity(");
        assertTrue(information.contains(
                "int internalGap = rowStyle.informationGroupGapPx"));
        assertFalse(information.contains(
                "dp(context, rowStyle.informationGroupGapPx)"));
        assertTrue(information.contains("int availableTilesWidth"));
        assertTrue(information.contains("requestedWidths"));
        String shortcut = source("launcher/InformationShortcutView.java");
        String panel = source("launcher/information/InformationPanelView.java");
        assertTrue(shortcut.contains("content.setPhysicalPixelMetrics(true)"));
        assertTrue(panel.contains("Padding is allowed to be exactly zero"));
    }

    @Test public void androidNinePopupNeverMutatesAnAttachedOrPreviouslyMeasuredRoot()
            throws Exception {
        String overlay = source("popup/PopupOverlayController.java");
        String render = between(overlay, "private void renderItems()",
                "/**\n     * Adds launcher-style edit chrome");
        assertTrue(render.contains("retireOlderRootsAfterFirstDraw(root)"));
        assertTrue(render.contains("root = null;"));
        assertTrue(render.contains("ensureView();"));
        assertFalse(render.contains(".removeAllViews("));
        assertTrue(overlay.contains("addOnPreDrawListener"));
        String editor = source("launcher/panels/PanelContentEditOverlay.java");
        assertTrue(editor.contains("onGestureStateChanged(boolean active)"));
    }

    @Test public void batteryResourceMarkerSurvivesAppCompatDrawableInstallation()
            throws Exception {
        String image = source("OutlineImageView.java");
        String setResource = between(image, "public void setImageResource(int resId)",
                "@Override\n    public void setImageLevel(int level)");
        int installDrawable = setResource.indexOf("super.setImageResource(resId);");
        int rememberResource = setResource.indexOf("currentImageResId = resId;",
                installDrawable);
        assertTrue("Battery resource must be remembered after AppCompat calls setImageDrawable",
                installDrawable >= 0 && rememberResource > installDrawable);
        assertTrue(image.contains(
                "currentImageResId == R.drawable.ic_status_iphone_battery"));
        assertTrue(image.contains("float fraction = imageLevel / 10_000f"));
    }

    private static String source(String relative) throws Exception {
        Path root = Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path app = Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + Math.max(1, start.length()));
        assertTrue("Missing section start: " + start, from >= 0);
        assertTrue("Missing section end: " + end, to > from);
        return source.substring(from, to);
    }
}
