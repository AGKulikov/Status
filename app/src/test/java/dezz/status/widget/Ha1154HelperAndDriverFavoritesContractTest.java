/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for atomic Helper telemetry and driver-favorite climate controls. */
public final class Ha1154HelperAndDriverFavoritesContractTest {
    @Test public void helperTelemetryHasDedicatedNotifyAndReadRecovery() throws Exception {
        String transport = source("phone/transport/IphoneAncsTransport.java");
        assertTrue(transport.contains("d2d9e4b4-47f1-4e44-a8bb-a932fd5a2f02"));
        assertTrue(transport.contains("PROPERTY_NOTIFY"));
        assertTrue(transport.contains("PROPERTY_READ"));
        assertTrue(transport.contains("startHelperTelemetryRead"));
        assertTrue(transport.contains("scheduleHelperTelemetryRecovery"));
        assertTrue(transport.contains("Helper B4 atomic read accepted"));
    }

    @Test public void atomicSnapshotUpdatesPowerAndNetworkTogether() throws Exception {
        String parser = source("phone/IphoneHelperTelemetry.java");
        String controller = source("phone/PhoneConnectorController.java");
        assertTrue(parser.contains("TEL3;60;1;C;6;44"));
        assertTrue(parser.contains("Kind.SNAPSHOT"));
        assertTrue(controller.contains("telemetry.kind == IphoneHelperTelemetry.Kind.SNAPSHOT"));
        assertTrue(controller.contains("helperBatteryLevel = telemetry.batteryLevel"));
        assertTrue(controller.contains("helperNetworkType = telemetry.networkType"));
    }

    @Test public void driverFavoritesExposeBothThreeLevelOrders() throws Exception {
        String picker = source("launcher/ShortcutActionPicker.java");
        assertTrue(picker.contains("Цикл уровней 1 → 2 → 3"));
        assertTrue(picker.contains("Цикл уровней 3 → 2 → 1"));
        assertTrue(picker.contains("saveCarCycle(existing, control, ascending)"));
        assertTrue(picker.contains("saveCarCycle(existing, control, descending)"));
    }

    @Test public void passengerSeatArtworkIsResolvedByCarTarget() throws Exception {
        String resolver = source("launcher/LauncherIconResolver.java");
        assertTrue(resolver.contains("shortcut.target.endsWith(\"_passenger\")"));
        assertTrue(resolver.contains("ic_car_seat_heat_passenger"));
        assertTrue(resolver.contains("ic_car_seat_vent_passenger"));
        String heat = resource("drawable/ic_car_seat_heat_passenger.xml");
        String vent = resource("drawable/ic_car_seat_vent_passenger.xml");
        assertTrue(heat.contains("android:scaleX=\"-1\""));
        assertTrue(vent.contains("android:scaleX=\"-1\""));
    }

    @Test public void explicitDriverButtonSpacingUsesNaturalHeightAndInternalInsets()
            throws Exception {
        String store = source("launcher/LauncherShortcutStore.java");
        String settings = source("DriverPanelSettingsActivity.java");
        String overlay = source("driver/DriverPanelOverlayController.java");
        assertTrue(store.contains("public int gapBeforePx = -1"));
        assertTrue(store.contains(".put(\"gapBeforePx\", value.gapBeforePx)"));
        assertTrue(settings.contains("buttonGapSlider(body, shortcut, true)"));
        assertTrue(settings.contains("buttonGapSlider(body, shortcut, false)"));
        assertFalse(overlay.contains("compactSpacing"));
        assertTrue(overlay.contains("DriverControlSpacingPolicy.resolve("));
        assertTrue(overlay.contains("ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertTrue(overlay.contains("button.setPadding(button.getPaddingLeft(), internalTop"));
        assertTrue(overlay.contains("itemParams.setMargins(4, 0, 4, 0)"));
    }

    private static String source(String relative) throws Exception {
        return read(Paths.get("app", "src", "main", "java", "dezz", "status", "widget")
                .resolve(relative), Paths.get("src", "main", "java", "dezz", "status", "widget")
                .resolve(relative));
    }

    private static String resource(String relative) throws Exception {
        return read(Paths.get("app", "src", "main", "res").resolve(relative),
                Paths.get("src", "main", "res").resolve(relative));
    }

    private static String read(Path root, Path app) throws Exception {
        Path file = Files.isRegularFile(root) ? root : app;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
