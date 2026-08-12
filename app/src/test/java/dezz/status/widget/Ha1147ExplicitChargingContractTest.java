/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Regression barriers for explicit-only iPhone charger state introduced in HA1147. */
public final class Ha1147ExplicitChargingContractTest {
    @Test public void percentageChangesCanNeverBecomeChargerState() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String policy = source("phone/PhoneConnectorPolicy.java");
        String statusPolicy = source("phone/PhoneStatusBarPolicy.java");

        assertFalse(controller.contains("BATTERY_TREND"));
        assertFalse(controller.contains("batteryTrend"));
        assertFalse(controller.contains("_trend"));
        assertFalse(policy.contains("inferChargingFromLevelTrend"));
        assertFalse(statusPolicy.contains("charging_source:bas_trend"));
        assertTrue(controller.contains(
                "batteryChargingEstimated = batteryCharging == null ? null : false"));
    }

    @Test public void helperIsTheOnlyPublishedChargingAuthority()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");

        assertTrue(controller.contains("batteryChargingSource = \"iphone_helper\""));
        assertTrue(controller.contains("Cable/charging state remains Helper-only"));
        assertFalse(controller.contains("selectBasChargingState"));
        assertFalse(controller.contains("basLevelStatusBatteryCharging"));
        assertFalse(controller.contains("basPowerStateBatteryCharging"));
        assertFalse(controller.contains("batteryChargingSource = \"android_metadata\""));
        assertFalse(controller.contains("batteryChargingSource = \"hfp_vendor\""));
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
