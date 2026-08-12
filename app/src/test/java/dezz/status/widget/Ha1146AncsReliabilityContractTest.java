/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Retained phone-data persistence coverage from the HA1146 regression set. */
public final class Ha1146AncsReliabilityContractTest {
    @Test public void onlyNonPowerTelemetrySurvivesARecoveryWindow() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        String store = source("phone/PhoneTelemetryStore.java");
        String protocol = source("phone/transport/AncsProtocol.java");

        assertTrue(store.contains("createDeviceProtectedStorageContext()"));
        assertTrue(store.contains("battery_updated_at"));
        assertTrue(store.contains("network_updated_at"));
        assertTrue(controller.contains("persistCurrentTelemetry()"));
        assertTrue(controller.contains("telemetry.stale"));
        assertFalse(controller.contains("retainedBatteryFresh(now)"));
        assertTrue(controller.contains("Integer savedBatteryLevel = null"));
        assertTrue(controller.contains("helperPowerUpdatedAtElapsed > 0L"));
        assertTrue(controller.contains("retainedNetworkFresh(now)"));
        assertFalse(store.contains("notification_uid"));
        assertTrue(protocol.contains("EVENT_FLAG_PRE_EXISTING"));
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
