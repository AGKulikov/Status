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
        assertTrue(controller.contains("batteryChargingEstimated = false"));
    }

    @Test public void bas11PowerStateIsReadAndSubscribedBeforePercent() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        String prepare = between(transport, "private void prepareBatteryBootstrap",
                "private void resetBatteryBootstrap");
        String advance = between(transport, "private void advanceBatteryBootstrapIfIdle",
                "private boolean startOptionalBatteryRead");
        String subscribe = between(transport, "private boolean startOptionalBatterySubscription",
                "private void handleNotificationSource");

        assertTrue(prepare.contains("batteryStage = BatteryStage.READ_LEVEL_STATUS"));
        assertBefore(advance, "case READ_LEVEL_STATUS:", "case READ_POWER:");
        assertBefore(advance, "case READ_POWER:", "case READ_LEVEL:");
        assertTrue(subscribe.contains("PROPERTY_NOTIFY"));
        assertTrue(subscribe.contains("PROPERTY_INDICATE"));
        assertTrue(subscribe.contains("ENABLE_INDICATION_VALUE"));
        assertTrue(subscribe.contains("ENABLE_NOTIFICATION_VALUE"));
    }

    @Test public void diagnosticsIdentifyTheExactExplicitChargingCharacteristic()
            throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String store = source("phone/PhoneTelemetryStore.java");

        assertTrue(controller.contains("ble_bas_level_status"));
        assertTrue(controller.contains("ble_bas_power_state"));
        assertTrue(controller.contains("selectBasChargingState"));
        assertTrue(controller.contains("basLevelStatusChargingKnown = decoded.charging != null"));
        assertTrue(controller.contains("basLevelStatusBatteryCharging = decoded.charging"));
        assertTrue(controller.contains("android_metadata"));
        assertTrue(controller.contains("hfp_vendor"));
        assertTrue(store.contains("calculatedCharging"));
        assertTrue(store.contains("chargingSource.endsWith(\"_trend\")"));
    }

    private static void assertBefore(String source, String first, String second) {
        int firstIndex = source.indexOf(first);
        int secondIndex = source.indexOf(second);
        assertTrue("Missing first marker: " + first, firstIndex >= 0);
        assertTrue("Missing second marker: " + second, secondIndex >= 0);
        assertTrue(first + " must precede " + second, firstIndex < secondIndex);
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
