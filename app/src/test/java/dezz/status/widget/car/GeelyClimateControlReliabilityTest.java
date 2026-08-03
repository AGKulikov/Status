/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.ecarx.xui.adaptapi.car.hvac.IHvac;

/** Pure policy checks for the slow ECARX Android 9 climate command path. */
public final class GeelyClimateControlReliabilityTest {
    @Test public void confirmationPollingDoesNotFloodBinderWithEveryRead() {
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(0));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(1));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(2));
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(3));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(4));
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(6));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(8));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(9));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(-1));
    }

    @Test public void identicalRequestsShareAStableDeduplicationKey() {
        CarControlCommand first = new CarControlCommand("climate.seat_heat_driver",
                CarControlCommand.Operation.SET, 2d);
        CarControlCommand duplicate = new CarControlCommand("climate.seat_heat_driver",
                CarControlCommand.Operation.SET, 2d);
        assertEquals(GeelyCarIntegration.controlCommandKey(first),
                GeelyCarIntegration.controlCommandKey(duplicate));
    }

    @Test public void confirmationDelaysDoNotOccupyTheSerialVendorWorker() {
        assertEquals(140L, GeelyCarIntegration.controlConfirmDelayMillis(0));
        assertEquals(170L, GeelyCarIntegration.controlConfirmDelayMillis(3));
        assertEquals(210L, GeelyCarIntegration.controlConfirmDelayMillis(7));
    }

    @Test public void onlyLatestPerControlGenerationMayComplete() {
        assertTrue(GeelyCarIntegration.isLatestControlCommand(7L, 7L));
        assertFalse(GeelyCarIntegration.isLatestControlCommand(7L, 6L));
        assertFalse(GeelyCarIntegration.isLatestControlCommand(0L, 0L));
    }

    @Test public void differentTargetsAndOperationsHaveDifferentCoalescingKeys() {
        CarControlCommand levelOne = new CarControlCommand("climate.wheel_heat",
                CarControlCommand.Operation.SET, 1d);
        CarControlCommand levelTwo = new CarControlCommand("climate.wheel_heat",
                CarControlCommand.Operation.SET, 2d);
        CarControlCommand cycle = new CarControlCommand("climate.wheel_heat",
                CarControlCommand.Operation.CYCLE, 1d);
        assertNotEquals(GeelyCarIntegration.controlCommandKey(levelOne),
                GeelyCarIntegration.controlCommandKey(levelTwo));
        assertNotEquals(GeelyCarIntegration.controlCommandKey(levelOne),
                GeelyCarIntegration.controlCommandKey(cycle));
    }

    @Test public void selectedCycleKeepsUserOrderAndFiltersUnsupportedModes() {
        List<CarControlDescriptor.Option> available = Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(1, "Комфорт"),
                new CarControlDescriptor.Option(2, "Спорт"),
                new CarControlDescriptor.Option(3, "Эко"));
        List<Double> selected = Arrays.asList(3d, 99d, 1d);

        assertEquals(Double.valueOf(1d),
                GeelyCarIntegration.cycleTarget(available, selected, 3d));
        assertEquals(Double.valueOf(3d),
                GeelyCarIntegration.cycleTarget(available, selected, 1d));
        assertEquals(Double.valueOf(3d),
                GeelyCarIntegration.cycleTarget(available, selected, 2d));
        assertNull(GeelyCarIntegration.cycleTarget(
                available, Collections.singletonList(99d), 1d));
    }

    @Test public void cycleCommandDeduplicationIncludesTheSelectedModes() {
        CarControlCommand first = new CarControlCommand("drive.mode",
                CarControlCommand.Operation.CYCLE, 0,
                Arrays.asList(1d, 2d, 1d, null, Double.NaN));
        CarControlCommand otherOrder = new CarControlCommand("drive.mode",
                CarControlCommand.Operation.CYCLE, 0, Arrays.asList(2d, 1d));

        assertEquals(Arrays.asList(1d, 2d), first.cycleValues);
        assertNotEquals(GeelyCarIntegration.controlCommandKey(first),
                GeelyCarIntegration.controlCommandKey(otherOrder));
        try {
            first.cycleValues.add(3d);
            throw new AssertionError("Cycle values must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test public void successRequiresActualReadBackToMatchTarget() {
        assertTrue(GeelyCarIntegration.isControlCommandConfirmed(2d, 2d));
        assertTrue(GeelyCarIntegration.isControlCommandConfirmed(22.5001d, 22.5d));
        assertFalse(GeelyCarIntegration.isControlCommandConfirmed(null, 2d));
        assertFalse(GeelyCarIntegration.isControlCommandConfirmed(1d, 2d));
    }

    @Test public void passiveReadBackContinuesUntilTheFiveSecondDeadline()
            throws IOException {
        String source = geelySource();
        assertTrue(source.contains("CONTROL_COMMAND_TIMEOUT_MS = 5_000L"));
        assertTrue(source.contains("CONTROL_WRITE_WINDOW_POLLS = 8"));
        assertFalse(source.contains("if (nextPoll >= CONTROL_CONFIRM_POLLS)"));
        assertTrue(source.contains("beginControlConfirmationPoll(active, nextPoll)"));
    }

    @Test public void fanCommandsUseTheVendorFunctionForTheCurrentClimateMode() {
        assertEquals(IHvac.HVAC_FUNC_FAN_SPEED,
                GeelyCarIntegration.fanFunctionIdForMode(false));
        assertEquals(IHvac.HVAC_FUNC_AUTO_FAN_SETTING,
                GeelyCarIntegration.fanFunctionIdForMode(true));
    }

    @Test public void manualFanPresentsAllNineEcarxPositions() {
        int[] rawValues = {
                IHvac.FAN_SPEED_LEVEL_1, IHvac.FAN_SPEED_LEVEL_2,
                IHvac.FAN_SPEED_LEVEL_3, IHvac.FAN_SPEED_LEVEL_4,
                IHvac.FAN_SPEED_LEVEL_5, IHvac.FAN_SPEED_LEVEL_6,
                IHvac.FAN_SPEED_LEVEL_7, IHvac.FAN_SPEED_LEVEL_8,
                IHvac.FAN_SPEED_LEVEL_9
        };
        int[] visible = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        for (int index = 0; index < rawValues.length; index++) {
            assertEquals(visible[index],
                    GeelyCarIntegration.manualFanDisplayLevel(rawValues[index]));
        }
        assertEquals(IHvac.FAN_SPEED_LEVEL_1,
                GeelyCarIntegration.manualFanValueForDisplayLevel(1));
        assertEquals(IHvac.FAN_SPEED_LEVEL_2,
                GeelyCarIntegration.manualFanValueForDisplayLevel(2));
        assertEquals(IHvac.FAN_SPEED_LEVEL_3,
                GeelyCarIntegration.manualFanValueForDisplayLevel(3));
        assertEquals(IHvac.FAN_SPEED_LEVEL_4,
                GeelyCarIntegration.manualFanValueForDisplayLevel(4));
        assertEquals(IHvac.FAN_SPEED_LEVEL_5,
                GeelyCarIntegration.manualFanValueForDisplayLevel(5));
        assertEquals(IHvac.FAN_SPEED_LEVEL_6,
                GeelyCarIntegration.manualFanValueForDisplayLevel(6));
        assertEquals(IHvac.FAN_SPEED_LEVEL_7,
                GeelyCarIntegration.manualFanValueForDisplayLevel(7));
        assertEquals(IHvac.FAN_SPEED_LEVEL_8,
                GeelyCarIntegration.manualFanValueForDisplayLevel(8));
        assertEquals(IHvac.FAN_SPEED_LEVEL_9,
                GeelyCarIntegration.manualFanValueForDisplayLevel(9));
    }

    @Test public void airflowUsesExactSevenAdaptApiValuesAndFrontAggregateZone()
            throws IOException {
        assertEquals(Arrays.asList(
                        IHvac.BLOWING_MODE_FACE,
                        IHvac.BLOWING_MODE_LEG,
                        IHvac.BLOWING_MODE_FACE_AND_LEG,
                        IHvac.BLOWING_MODE_FRONT_WINDOW,
                        IHvac.BLOWING_MODE_FACE_AND_FRONT_WINDOW,
                        IHvac.BLOWING_MODE_LEG_AND_FRONT_WINDOW,
                        IHvac.BLOWING_ALL),
                values(GeelyCarIntegration.airflowDirectionOptions()));
        assertEquals(Arrays.asList(
                        "Лицо", "Ноги", "Лицо + ноги", "Стекло",
                        "Лицо + стекло", "Ноги + стекло",
                        "Лицо + ноги + стекло"),
                labels(GeelyCarIntegration.airflowDirectionOptions()));

        String source = geelySource();
        assertTrue(source.contains(
                "FRONT_FAN_ZONE = VehicleZone.ZONE_ROW_1_ALL"));
        assertTrue(source.contains(
                "IHvac.HVAC_FUNC_BLOWING_MODE, FRONT_FAN_ZONE"));
        assertTrue(source.contains(
                "source.registerFunctionValueWatcher(id, watcher)"));
        assertTrue(source.contains(
                "onFunctionValueChanged(int functionId, int zone, int value)"));
        assertTrue(source.contains("readDemandedFunction(functionId)"));
    }

    @Test public void transientAutoReadNeverSilentlyFallsBackToManualRouting()
            throws IOException {
        assertEquals(Boolean.TRUE,
                GeelyCarIntegration.conservativeFanAutoMode(null, true, 1_000L));
        assertEquals(Boolean.FALSE,
                GeelyCarIntegration.conservativeFanAutoMode(null, false, 1_000L));
        assertEquals(Boolean.TRUE,
                GeelyCarIntegration.conservativeFanAutoMode(true, false, Long.MAX_VALUE));
        assertNull(GeelyCarIntegration.conservativeFanAutoMode(null, null, 0L));
        assertNull(GeelyCarIntegration.conservativeFanAutoMode(null, true, 75_001L));

        String source = geelySource();
        String routing = source.substring(source.indexOf("private ControlDefinition "
                        + "effectiveControlDefinition"),
                source.indexOf("private static Set<String> selectKnownControlIds"));
        assertTrue(routing.contains("if (autoActive == null) return null;"));
        assertFalse(routing.contains("controlAvailability(source, AUTO_FAN_DEFINITION)"));
        assertTrue(source.contains(
                "\"Не удалось подтвердить режим AUTO. Команда не отправлена\""));
    }

    @Test public void autoFanProfilesStayInOneCanonicalFivePositionDomain() {
        List<CarControlDescriptor.Option> advertisedSuperset = autoFanOptions(
                IHvac.AUTO_FAN_SETTING_HIGH,
                IHvac.AUTO_FAN_SETTING_SILENT,
                IHvac.AUTO_FAN_SETTING_HIGHER,
                IHvac.AUTO_FAN_SETTING_NORMAL,
                IHvac.AUTO_FAN_SETTING_QUIETER);

        List<Integer> expectedValues = Arrays.asList(
                        IHvac.AUTO_FAN_SETTING_QUIETER,
                        IHvac.AUTO_FAN_SETTING_SILENT,
                        IHvac.AUTO_FAN_SETTING_NORMAL,
                        IHvac.AUTO_FAN_SETTING_HIGH,
                        IHvac.AUTO_FAN_SETTING_HIGHER);
        List<String> expectedLabels = Arrays.asList(
                "AUTO · тише", "AUTO · тихо", "AUTO · обычно",
                "AUTO · интенсивно", "AUTO · выше");

        List<CarControlDescriptor.Option> withoutConfirmed =
                GeelyCarIntegration.safeAutoFanOptions(advertisedSuperset,
                        null, Collections.emptyList());
        assertEquals(expectedValues, values(withoutConfirmed));
        assertEquals(expectedLabels, labels(withoutConfirmed));
        assertEquals(expectedValues,
                values(GeelyCarIntegration.safeAutoFanOptions(advertisedSuperset,
                        (double) IHvac.AUTO_FAN_SETTING_NORMAL, Collections.emptyList())));
        assertEquals(expectedValues,
                values(GeelyCarIntegration.safeAutoFanOptions(advertisedSuperset,
                        (double) IHvac.AUTO_FAN_SETTING_HIGHER, Collections.emptyList())));
    }

    @Test public void autoFanDiscoveryFailureUsesConfirmedDomainOrExactRuntimeCache() {
        assertEquals(Arrays.asList(
                        IHvac.AUTO_FAN_SETTING_QUIETER,
                        IHvac.AUTO_FAN_SETTING_SILENT,
                        IHvac.AUTO_FAN_SETTING_NORMAL,
                        IHvac.AUTO_FAN_SETTING_HIGH,
                        IHvac.AUTO_FAN_SETTING_HIGHER),
                values(GeelyCarIntegration.safeAutoFanOptions(Collections.emptyList(),
                        (double) IHvac.AUTO_FAN_SETTING_HIGH, Collections.emptyList())));

        List<CarControlDescriptor.Option> cachedSubset = autoFanOptions(
                IHvac.AUTO_FAN_SETTING_HIGHER, IHvac.AUTO_FAN_SETTING_SILENT);
        assertEquals(Arrays.asList(
                        IHvac.AUTO_FAN_SETTING_SILENT,
                        IHvac.AUTO_FAN_SETTING_HIGHER),
                values(GeelyCarIntegration.safeAutoFanOptions(Collections.emptyList(),
                        null, cachedSubset)));
        assertTrue(GeelyCarIntegration.safeAutoFanOptions(Collections.emptyList(),
                null, Collections.emptyList()).isEmpty());
    }

    @Test public void controlsHaveDedicatedWorkerAndTransientFailuresKeepConfirmedState()
            throws IOException {
        String source = geelySource();
        assertTrue(source.contains("new Thread(runnable, \"ecarx-controls\")"));
        assertTrue(source.contains(
                "executeControlTask(() -> enqueueControlCommandOnWorker"));
        assertTrue(source.contains("mainHandler.postDelayed(() -> executeControlTask(() ->"));
        assertTrue(source.contains("activeControlCommands"));
        assertFalse(source.contains("pauseControlWorker"));
        assertFalse(source.contains("Thread.sleep("));
        assertFalse(source.contains("deliverUnavailableControls(demanded"));
    }

    @Test public void replacementAndWatcherPathsCannotConfirmAnObsoleteTarget()
            throws IOException {
        String source = geelySource();
        assertTrue(source.contains(
                "cancelActiveControlCommand(previous, \"Команда заменена более новой\")"));
        assertTrue(source.contains("reserveControlCommandSubmission(command.controlId, key)"));
        assertTrue(source.contains("isLatestControlCommandSubmission"));
        assertTrue(source.contains("previous.listeners.add(listener)"));
        assertTrue(source.contains("if (!isCurrentControlCommand(active)) return;"));
        assertTrue(source.contains("confirmActiveControlCommandFromState(definition, value)"));
        assertTrue(source.contains("current == candidate"));
    }

    @Test public void panelWatchdogReleasesPendingAndRequestsAConfirmedRefresh()
            throws IOException {
        String panel = climatePanelSource();
        assertTrue(panel.contains("COMMAND_WATCHDOG_MS = 7_000L"));
        assertTrue(panel.contains("pendingTimeouts.put(bindingId, timeout)"));
        assertTrue(panel.contains("postDelayed(timeout, COMMAND_WATCHDOG_MS)"));
        assertTrue(panel.contains("pending.remove(bindingId)"));
        assertTrue(panel.contains("if (started) subscribeVisibleControls()"));
        assertTrue(panel.contains("Нет ответа от автомобиля. Состояние обновляется"));
    }

    @Test public void compactFanTileHasNoCaptionAndClimateStateAlwaysIncludesHvacPower()
            throws IOException {
        String panel = climatePanelSource();
        assertTrue(panel.contains(
                "if (!ClimatePanelConfig.FAN.equals(id)) center.addView(title)"));
        assertTrue(panel.contains("if (!ids.isEmpty())"));
        assertTrue(panel.contains(
                "executeBound(id, ClimatePanelConfig.POWER,"));
    }

    @Test public void finiteUnknownVendorLevelHasAnExplicitNonDashFallback()
            throws IOException {
        String geely = geelySource();
        String panel = climatePanelSource();
        assertTrue(geely.contains("isReadableUnknownControlValue(definition, value)"));
        assertTrue(geely.contains("\"Неизвестно\", false"));
        assertTrue(panel.contains("if (!state.known) return \"Неизвестно\""));
        assertTrue(panel.contains("\"—\".equals(value)"));
    }

    @Test public void autoFanUsesItsSeparateVendorValueDomainEndToEnd()
            throws IOException {
        String source = geelySource();
        String panel = climatePanelSource();
        assertTrue(source.contains(
                "CarControlDescriptor.Kind.LEVELS, IHvac.HVAC_FUNC_AUTO_FAN_SETTING"));
        assertTrue(source.contains(
                "ControlDefinition effective = effectiveControlDefinition(source, definition)"));
        assertTrue(source.contains("active.definition = definition"));
        assertTrue(source.contains(
                "writeControlValue(source, definition, active.target)"));
        assertTrue(source.contains("functionIds.add(IHvac.HVAC_FUNC_AUTO_FAN_SETTING)"));
        assertTrue(source.contains("functionIds.add(IHvac.HVAC_FUNC_AUTO)"));
        assertFalse(fanOptionsMethod(source).contains("FAN_SPEED_LEVEL_AUTO"));
        assertTrue(panel.contains(
                "boolean includeAuto = ClimatePanelConfig.FAN.equals(id)"));
        assertTrue(panel.contains("boolean fanProfileMissing = ClimatePanelConfig.FAN.equals"));
        assertTrue(panel.contains("newlySupported || fanProfileMissing"));
    }

    @Test public void defrostersAreReadBackControlsNotAcceptedOnlyPulses() throws IOException {
        String source = geelySource();
        assertTrue(source.contains("\"defrost_front\", CarControlDescriptor.Kind.TOGGLE"));
        assertTrue(source.contains("\"defrost_rear\", CarControlDescriptor.Kind.TOGGLE"));
    }

    @Test public void catalogIsSingleShotAndInitialStateSubscriptionDoesNotWaitForIt()
            throws IOException {
        String geely = geelySource();
        int methodStart = geely.indexOf("public void requestControlCatalog");
        int methodEnd = geely.indexOf("private CarControlDescriptor.Availability", methodStart);
        String method = geely.substring(methodStart, methodEnd);
        assertEquals(1, occurrences(method, "listener.onCatalog("));

        String panel = climatePanelSource();
        String expectedOrder = "rebuildControls();\n        subscribeVisibleControls();\n"
                + "        requestCatalog(false);";
        assertTrue(panel.contains(expectedOrder));
    }

    private static int occurrences(String source, String value) {
        int result = 0;
        int index = 0;
        while ((index = source.indexOf(value, index)) >= 0) {
            result++;
            index += value.length();
        }
        return result;
    }

    private static List<CarControlDescriptor.Option> autoFanOptions(int... values) {
        List<CarControlDescriptor.Option> result = new ArrayList<>();
        for (int value : values) {
            result.add(new CarControlDescriptor.Option(value, Integer.toString(value)));
        }
        return result;
    }

    private static List<Integer> values(List<CarControlDescriptor.Option> options) {
        List<Integer> result = new ArrayList<>();
        for (CarControlDescriptor.Option option : options) {
            result.add((int) Math.round(option.value));
        }
        return result;
    }

    private static List<String> labels(List<CarControlDescriptor.Option> options) {
        List<String> result = new ArrayList<>();
        for (CarControlDescriptor.Option option : options) {
            result.add(option.label);
        }
        return result;
    }

    private static String fanOptionsMethod(String source) {
        int start = source.indexOf("private static List<CarControlDescriptor.Option> fanOptions()");
        int end = source.indexOf("\n    }\n\n    /**", start) + "\n    }".length();
        return source.substring(start, end);
    }

    private static String geelySource() throws IOException {
        Path fromRoot = Paths.get("app", "src", "geely", "java", "dezz", "status", "widget",
                "car", "GeelyCarIntegration.java");
        Path fromApp = Paths.get("src", "geely", "java", "dezz", "status", "widget", "car",
                "GeelyCarIntegration.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String climatePanelSource() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "launcher", "climate", "ClimatePanelView.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget", "launcher",
                "climate", "ClimatePanelView.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
