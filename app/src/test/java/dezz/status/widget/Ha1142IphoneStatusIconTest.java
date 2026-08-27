/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract for the iPhone-style status indicators added in HA1142. */
public final class Ha1142IphoneStatusIconTest {
    @Test public void chargingBoltNeverOverridesPercentageThresholdColors() throws Exception {
        String widget = source("WidgetService.java");
        String policy = source("phone/PhoneStatusBarPolicy.java");
        String icon = source("OutlineImageView.java");

        assertTrue(widget.contains("battery != null && battery < 20"));
        assertTrue(widget.contains("R.color.iphone_battery_critical"));
        assertTrue(count(widget, "phoneBatteryColor(battery, batteryCharging)") >= 1);
        assertTrue(widget.contains("phoneBatteryColor(battery, charging)"));
        assertTrue(widget.contains("phoneBoolean(\"battery.charging\")"));
        assertTrue(widget.contains("phoneBoolean(\"battery.external_power\")"));
        assertTrue(widget.contains("batteryIcon.setBatteryCharging(charging)"));
        assertTrue(icon.contains("drawBatteryCharging"));
        assertTrue(widget.contains("R.color.iphone_battery_charging"));
        assertTrue(policy.contains("public static Boolean booleanValue"));
    }

    @Test public void gpsAndBluetoothUseOneIosVectorFamilyEverywhere() throws Exception {
        String widget = source("WidgetService.java");
        String resolver = source("launcher/LauncherIconResolver.java");
        String gps = resource("drawable/ic_status_iphone_gps_active.xml");
        String bluetooth = resource("drawable/ic_status_iphone_bluetooth_solid.xml");

        assertTrue(count(widget, "R.drawable.ic_status_iphone_gps_active") >= 3);
        assertTrue(count(widget, "R.drawable.ic_status_iphone_bluetooth_solid") >= 5);
        assertTrue(resolver.contains("R.drawable.ic_status_iphone_gps_active"));
        assertTrue(resolver.contains("R.drawable.ic_status_iphone_bluetooth_solid"));
        assertTrue(gps.contains("Location fill"));
        assertTrue(bluetooth.contains("fillColor=\"@android:color/white\""));
    }

    @Test public void bluetoothUsesOneMonochromeGlyphForTheConnectedIphone() throws Exception {
        String widget = source("WidgetService.java");
        String policy = source("phone/PhoneBluetoothIndicatorPolicy.java");
        assertTrue(policy.contains("Appearance.PHONE_MONO"));
        assertTrue(widget.contains("R.drawable.ic_status_iphone_bluetooth_solid"));
        assertTrue(widget.contains("binding.bluetoothStatusIcon.setOutlineWidth(0)"));
        assertTrue(!widget.contains("PhoneBluetoothIndicatorPolicy.Appearance.PHONE_SOLID"));
    }

    private static int count(String value, String needle) {
        int result = 0;
        for (int offset = 0; (offset = value.indexOf(needle, offset)) >= 0;
             offset += needle.length()) result++;
        return result;
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("java", "dezz", "status", "widget").resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        return read(Paths.get("res").resolve(relative));
    }

    private static String read(Path relative) throws Exception {
        Path root = Paths.get("app", "src", "main").resolve(relative);
        Path app = Paths.get("src", "main").resolve(relative);
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}

