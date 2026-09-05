/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Retained driver-favorite climate and layout coverage. */
public final class Ha1154HelperAndDriverFavoritesContractTest {
    @Test public void driverFavoritesExposeBothThreeLevelOrders() throws Exception {
        String picker = source("launcher/ShortcutActionPicker.java");
        assertTrue(picker.contains("Цикл уровней 0 → 1 → 2 → 3"));
        assertTrue(picker.contains("Цикл уровней 3 → 2 → 1 → 0"));
        assertTrue(picker.contains("saveCarCycle(existing, control, ascending)"));
        assertTrue(picker.contains("saveCarCycle(existing, control, descending)"));
    }

    @Test public void passengerSeatArtworkUsesOfficialRightHandGlyphs() throws Exception {
        String resolver = source("launcher/LauncherIconResolver.java");
        assertTrue(resolver.contains("shortcut.target.endsWith(\"_passenger\")"));
        assertTrue(resolver.contains("ic_car_seat_heat_passenger"));
        assertTrue(resolver.contains("ic_car_seat_vent_passenger"));
        String heat = resource("drawable/ic_car_seat_heat_passenger.xml");
        String vent = resource("drawable/ic_car_seat_vent_passenger.xml");
        assertTrue(heat.contains("Google Material Symbols Rounded @ 50f0603"));
        assertTrue(heat.contains("(seat_heat_right, 24px, wght400, fill0)"));
        assertTrue(vent.contains("Google Material Symbols Rounded @ 50f0603"));
        assertTrue(vent.contains("(seat_vent_right, 24px, wght400, fill0)"));
        assertFalse(heat.contains("android:scaleX=\"-1\""));
        assertFalse(vent.contains("android:scaleX=\"-1\""));
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
