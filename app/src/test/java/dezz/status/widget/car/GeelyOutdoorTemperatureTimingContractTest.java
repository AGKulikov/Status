/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.car;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards arrival-time sampling against a congested main-thread queue on the head unit. */
public final class GeelyOutdoorTemperatureTimingContractTest {
    @Test public void sensorArrivalTimeIsCapturedBeforePostingToMainThread() throws IOException {
        String source = source();
        int callbackStart = source.indexOf("public void onSensorValueChanged(");
        int callbackEnd = source.indexOf("new ISensor.ISensorListener()", callbackStart + 1);
        if (callbackEnd < 0) callbackEnd = source.indexOf("}, cancelled);", callbackStart);
        String callback = source.substring(callbackStart, callbackEnd);

        int timestamp = callback.indexOf(
                "long observedElapsedMillis = SystemClock.elapsedRealtime()");
        int filter = callback.indexOf("filterBrickTemperature(", timestamp);
        int post = callback.indexOf("mainHandler.post(");
        assertTrue(callback.contains("outdoorTemperatureFilter.epoch()"));
        assertTrue(timestamp >= 0);
        assertTrue(filter > timestamp);
        assertTrue(post > filter);
        assertTrue(callback.substring(filter, post).contains("observedElapsedMillis"));
        assertTrue(callback.substring(filter, post).contains("expectedOutdoorEpoch"));
        assertTrue(callback.substring(filter, post).contains(", s)"));
        assertTrue(!callback.substring(filter, post).contains(
                "expectedOutdoorEpoch, cancelled"));
        assertTrue(callback.substring(filter, post).contains(
                "if (!Float.isFinite(filteredValue)) return"));
        assertTrue(callback.substring(post).contains("listener.onValue(type, filteredValue)"));
    }

    @Test public void cachedOutdoorValueNeverSeedsAColdWindow() throws IOException {
        String source = source();
        int initialStart = source.indexOf("private void emitInitialBrickValue(");
        int initialEnd = source.indexOf(
                "private boolean isSensorCurrentlySupported(", initialStart);
        String initial = source.substring(initialStart, initialEnd);

        assertTrue(initial.contains("if (type == BrickType.OUTDOOR_TEMP)"));
        assertTrue(initial.contains("initialValue = outdoorTemperatureMedianIfCurrentSource("));
        assertTrue(initial.contains("if (!Float.isFinite(initialValue))"));
        assertTrue(!initial.contains("outdoorTemperatureFilter.offer("));
        assertTrue(!initial.contains("seedIfEmpty("));
        assertTrue(initial.contains("status == FunctionStatus.notavailable"));
        assertTrue(initial.contains("resetOutdoorTemperatureIfCurrentSource("));
    }

    @Test public void staleVendorProxyCannotMutateANewerOutdoorWindow() throws IOException {
        String source = source();
        assertTrue(source.contains("private synchronized float "
                + "offerOutdoorTemperatureIfCurrentSource("));
        assertTrue(source.contains("if (sensors != source) return Float.NaN;"));
        assertTrue(source.contains("private synchronized void "
                + "resetOutdoorTemperatureIfCurrentSource("));
        assertTrue(source.contains(
                "if (sensors == source) outdoorTemperatureFilter.resetIfEpoch(expectedEpoch);"));
        assertTrue(source.contains("isOutdoorTemperatureSourceCurrent("));
    }

    private static String source() throws IOException {
        Path fromRoot = Paths.get("app", "src", "geely", "java", "dezz", "status", "widget",
                "car", "GeelyCarIntegration.java");
        Path fromApp = Paths.get("src", "geely", "java", "dezz", "status", "widget",
                "car", "GeelyCarIntegration.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
