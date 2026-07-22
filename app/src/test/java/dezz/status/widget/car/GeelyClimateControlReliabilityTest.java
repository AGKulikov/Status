/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/** Pure policy checks for the slow ECARX Android 9 climate command path. */
public final class GeelyClimateControlReliabilityTest {
    @Test public void confirmationPollingDoesNotFloodBinderWithEveryRead() {
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(0));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(1));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(2));
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(3));
        assertFalse(GeelyCarIntegration.shouldSendControlWrite(4));
        assertTrue(GeelyCarIntegration.shouldSendControlWrite(6));
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

    @Test public void duplicateCommandsExecuteOnceAndFanOutOneConfirmedResult() {
        ControlCommandDeduplicator deduplicator = new ControlCommandDeduplicator();
        AtomicInteger callbacks = new AtomicInteger();
        CarIntegration.ControlCommandListener first = (success, message) ->
                callbacks.incrementAndGet();
        CarIntegration.ControlCommandListener second = (success, message) ->
                callbacks.incrementAndGet();

        assertTrue(deduplicator.add("same", first));
        assertFalse(deduplicator.add("same", second));
        List<CarIntegration.ControlCommandListener> listeners = deduplicator.take("same");
        assertEquals(2, listeners.size());
        for (CarIntegration.ControlCommandListener listener : listeners) {
            listener.onResult(true, null);
        }
        assertEquals(2, callbacks.get());
        assertTrue(deduplicator.take("same").isEmpty());
    }

    @Test public void differentTargetsAndOperationsAreNeverDeduplicated() {
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

    @Test public void successRequiresActualReadBackToMatchTarget() {
        assertTrue(GeelyCarIntegration.isControlCommandConfirmed(2d, 2d));
        assertTrue(GeelyCarIntegration.isControlCommandConfirmed(22.5001d, 22.5d));
        assertFalse(GeelyCarIntegration.isControlCommandConfirmed(null, 2d));
        assertFalse(GeelyCarIntegration.isControlCommandConfirmed(1d, 2d));
    }

    @Test public void controlsHaveDedicatedWorkerAndTransientFailuresKeepConfirmedState()
            throws IOException {
        String source = geelySource();
        assertTrue(source.contains("new Thread(runnable, \"ecarx-controls\")"));
        assertTrue(source.contains("executeControlTask(() -> executeControlOnWorker"));
        assertFalse(source.contains("executeTelemetryTask(() -> executeControlOnWorker"));
        assertFalse(source.contains("deliverUnavailableControls(demanded"));
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
