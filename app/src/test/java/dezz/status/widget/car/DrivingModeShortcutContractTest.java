/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.ecarx.xui.adaptapi.car.vehicle.IDriveMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Contract for fixed and user-selected driving-mode shortcuts on every launcher surface. */
public final class DrivingModeShortcutContractTest {
    private static CarControlDescriptor.Option option(double value, String label) {
        return new CarControlDescriptor.Option(value, label);
    }

    @Test public void selectedCycleUsesUserOrderAndActualCurrentMode() {
        List<CarControlDescriptor.Option> available = Arrays.asList(
                option(IDriveMode.DRIVE_MODE_SELECTION_ECO, "Eco"),
                option(IDriveMode.DRIVE_MODE_SELECTION_COMFORT, "Comfort"),
                option(IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC, "Sport"));
        List<Double> selected = Arrays.asList(
                (double) IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
                (double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT);

        assertEquals((double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT,
                GeelyCarIntegration.nextCycleTarget(available, selected,
                        IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC), .01d);
        assertEquals((double) IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
                GeelyCarIntegration.nextCycleTarget(available, selected,
                        IDriveMode.DRIVE_MODE_SELECTION_COMFORT), .01d);
        assertEquals((double) IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
                GeelyCarIntegration.nextCycleTarget(available, selected,
                        IDriveMode.DRIVE_MODE_SELECTION_ECO), .01d);
    }

    @Test public void unsupportedSelectedModesAreSkippedWithoutChangingOrder() {
        List<CarControlDescriptor.Option> available = Arrays.asList(
                option(IDriveMode.DRIVE_MODE_SELECTION_ECO, "Eco"),
                option(IDriveMode.DRIVE_MODE_SELECTION_COMFORT, "Comfort"));
        List<Double> selected = Arrays.asList(
                (double) IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
                (double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT);

        assertEquals((double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT,
                GeelyCarIntegration.nextCycleTarget(available, selected,
                        IDriveMode.DRIVE_MODE_SELECTION_ECO), .01d);
    }

    @Test public void differentSelectedCyclesDoNotCoalesceAsOneCommand() {
        CarControlCommand sportComfort = new CarControlCommand("vehicle.drive_mode",
                CarControlCommand.Operation.CYCLE, 0,
                Arrays.asList((double) IDriveMode.DRIVE_MODE_SELECTION_DYNAMIC,
                        (double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT));
        CarControlCommand ecoComfort = new CarControlCommand("vehicle.drive_mode",
                CarControlCommand.Operation.CYCLE, 0,
                Arrays.asList((double) IDriveMode.DRIVE_MODE_SELECTION_ECO,
                        (double) IDriveMode.DRIVE_MODE_SELECTION_COMFORT));
        assertNotEquals(GeelyCarIntegration.controlCommandKey(sportComfort),
                GeelyCarIntegration.controlCommandKey(ecoComfort));
    }

    @Test public void sharedPickerAndBothExecutorsCarryTheSelectedSubset() throws IOException {
        String picker = source("dezz/status/widget/launcher/"
                + "ShortcutActionPicker.java");
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String driver = source("dezz/status/widget/driver/"
                + "DriverPanelActionExecutor.java");
        assertTrue(picker.contains("Переключать только выбранные режимы"));
        assertTrue(picker.contains("value.cycleValues = new ArrayList<>(cycleValues)"));
        assertTrue(launcher.contains("shortcut.commandValue, shortcut.cycleValues"));
        assertTrue(driver.contains("shortcut.cycleValues"));
    }

    private static String source(String relative) throws IOException {
        java.nio.file.Path fromRoot = Paths.get("app", "src", "main", "java")
                .resolve(relative);
        java.nio.file.Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        java.nio.file.Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
