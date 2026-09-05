/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Retained presentation coverage for fresh phone network and charging values. */
public final class Ha1150HelperTelemetryContractTest {
    @Test public void bothNetworkPresentationsAndChargingBoltUseFreshResources() throws Exception {
        String widget = source("WidgetService.java");
        String prefs = source("Preferences.java");
        String layout = resource("layout/overlay_status_widget.xml");
        String icon = source("OutlineImageView.java");
        assertTrue(layout.contains("@+id/phoneCellularNetworkTypeText"));
        assertTrue(layout.contains("@+id/phoneNetworkTypeText"));
        assertTrue(prefs.contains("phoneCellularShowNetworkType"));
        assertTrue(widget.contains("PhoneNetworkTypePolicy.display(phoneText(\"network.type\"))"));
        assertTrue(widget.contains("boolean charging = phoneChargingNow()"));
        assertTrue(widget.contains("batteryIcon.setBatteryCharging(charging)"));
        assertTrue(icon.contains("drawBatteryCharging"));
    }

    @Test public void phoneInformationDoesNotDependOnTheStatusOverlayWindow()
            throws Exception {
        String widget = source("WidgetService.java");
        assertFalse(widget.contains(
                "if (destroyed || prefs == null || binding == null) return null;"));
        assertTrue(widget.contains("boolean headlessPhoneSnapshot"));
        assertTrue(widget.contains("type == BrickType.PHONE_CELLULAR"));
        assertTrue(widget.contains("type == BrickType.PHONE_BATTERY"));
        assertTrue(widget.contains("type == BrickType.PHONE_NETWORK_TYPE"));
        assertTrue(widget.contains(
                "if (binding == null && !headlessPhoneSnapshot) return null;"));
        assertTrue(widget.contains("currentPhoneValue(resourceId)"));
        assertTrue(widget.contains("current.get(ConnectorType.PHONE,"));
        assertTrue(widget.contains("SourceBinding.DEFAULT_CONNECTOR_ID"));
    }

    @Test public void phoneBatteryTurnsRedBelowTwentyBeforeChargingTintIsConsidered()
            throws Exception {
        String widget = source("WidgetService.java");
        String colors = resource("values/colors.xml");
        int helper = widget.indexOf("private int phoneBatteryColor(");
        int nextMethod = widget.indexOf("private void applyPhoneCellularInternalSpacing", helper);
        assertTrue(helper >= 0 && nextMethod > helper);
        String body = widget.substring(helper, nextMethod);
        assertTrue(body.contains("battery != null && battery < 20"));
        assertTrue(body.contains("R.color.iphone_battery_critical"));
        assertTrue(body.indexOf("battery < 20") < body.indexOf(": charging"));
        assertTrue(colors.contains("name=\"iphone_battery_critical\">#FF453A"));
        assertTrue(widget.contains("iconTint = phoneBatteryColor(battery, batteryCharging)"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        return read(Paths.get("res").resolve(relative));
    }

    private static String read(Path relative) throws Exception {
        Path app = Paths.get("app", "src", "main").resolve(relative);
        Path module = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(app) ? app : module;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
