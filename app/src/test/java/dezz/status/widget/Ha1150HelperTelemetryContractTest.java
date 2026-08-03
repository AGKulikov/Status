/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression contract: charging and network generation are live helper-only fields. */
public final class Ha1150HelperTelemetryContractTest {
    @Test public void controllerNeverPublishesRetainedOrHfpNetworkTypeAndCharging() throws Exception {
        String source = source("phone/PhoneConnectorController.java");
        assertTrue(source.contains("batteryChargingSource = \"iphone_helper\""));
        assertTrue(source.contains("String effectiveNetworkType = helperNetworkUpdatedAtElapsed"));
        assertTrue(source.contains("Boolean effectiveCharging = batteryCharging;"));
        assertTrue(source.contains("String savedNetworkType = \"\";"));
        assertTrue(source.contains("Boolean savedCharging = null;"));
        assertFalse(source.contains("batteryChargingSource = \"hfp_vendor\""));
        assertFalse(source.contains("batteryChargingSource = \"android_metadata\""));
        assertFalse(source.contains("retainedCharging ? retained.batteryCharging"));
        assertFalse(source.contains("retainedNetworkType ? retained.networkType"));
        assertTrue(source.contains("boolean transportNeeded()"));
        assertTrue(source.contains("return enabled;"));
        assertTrue(source.contains("boolean ancsNeeded()"));
    }

    @Test public void bothNetworkPresentationsAndChargingBoltUseFreshResources() throws Exception {
        String widget = source("WidgetService.java");
        String prefs = source("Preferences.java");
        String layout = resource("layout/overlay_status_widget.xml");
        String icon = source("OutlineImageView.java");
        assertTrue(layout.contains("@+id/phoneCellularNetworkTypeText"));
        assertTrue(layout.contains("@+id/phoneNetworkTypeText"));
        assertTrue(prefs.contains("phoneCellularShowNetworkType"));
        assertTrue(widget.contains("PhoneNetworkTypePolicy.display(phoneText(\"network.type\"))"));
        assertTrue(widget.contains("batteryIcon.setBatteryCharging(phoneChargingNow())"));
        assertTrue(icon.contains("drawBatteryCharging"));
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
