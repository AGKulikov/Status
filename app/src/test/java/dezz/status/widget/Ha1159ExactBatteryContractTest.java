/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for direct one-percent iPhone battery updates. */
public final class Ha1159ExactBatteryContractTest {
    @Test public void selectedIphoneDirectSourcesAreLiveAndPrimary() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        assertTrue(controller.contains("filter.addAction(ACTION_DEVICE_BATTERY_LEVEL_CHANGED)"));
        assertTrue(controller.contains("ACTION_DEVICE_BATTERY_LEVEL_CHANGED.equals(action)"));
        assertTrue(controller.contains("readInitialDeviceBattery(selected)"));
        assertTrue(controller.contains("BluetoothDevice.class.getMethod(\"getBatteryLevel\")"));
        assertTrue(controller.contains("PhoneBatteryLevelPolicy.resolve("));
        assertTrue(controller.contains("Integer effectiveBatteryLevel = batteryLevel;"));
    }

    @Test public void helperStillOwnsPowerStateButNotDisplayedPercentage() throws Exception {
        String controller = source("phone/PhoneConnectorController.java");
        assertTrue(controller.contains("batteryChargingSource = \"iphone_helper\""));
        assertTrue(controller.contains(
                "helperPowerUpdatedAtElapsed > 0L ? helperBatteryLevel : null"));
        assertTrue(controller.contains("batteryLevelSource = reading.source"));
    }

    @Test public void releaseIdentityIsHa1160() throws Exception {
        String build = project("build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1170'"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("app/src/main/java/dezz/status/widget").resolve(relative),
                Paths.get("src/main/java/dezz/status/widget").resolve(relative));
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        if (Files.isRegularFile(Paths.get("..", "settings.gradle"))
                && Files.isRegularFile(parent)) return text(parent);
        return text(Files.isRegularFile(direct) ? direct : parent);
    }

    private static String read(Path root, Path app) throws Exception {
        return text(Files.isRegularFile(root) ? root : app);
    }

    private static String text(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
