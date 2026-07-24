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
        assertTrue(source.contains("if (outdoorBrickSource != source) {"));
        assertTrue(source.contains("return Float.NaN;"));
        assertTrue(source.contains("private synchronized void "
                + "resetOutdoorTemperatureIfCurrentSource("));
        assertTrue(source.contains(
                "if (outdoorBrickSource == source) {"));
        assertTrue(source.contains("activateOutdoorTemperatureSource(s)"));
        assertTrue(source.contains("isOutdoorTemperatureSourceCurrent("));
    }

    @Test public void fullCarServiceInvalidationStartsAFreshOutdoorWindow() throws IOException {
        String source = source();
        int invalidationStart = source.indexOf(
                "private synchronized void invalidateCarServices()");
        int invalidationEnd = source.indexOf(
                "private synchronized void invalidateFunctionProxy", invalidationStart);
        String invalidation = source.substring(invalidationStart, invalidationEnd);

        assertTrue(invalidation.contains("sensors = null;"));
        assertTrue(invalidation.contains("outdoorTemperatureFilter.reset();"));
    }

    @Test public void fuelOnlyRecoveryDoesNotInvalidateLiveSensorSource() throws IOException {
        String source = source();
        int pollStart = source.indexOf("private void runAvailabilityPoll()");
        int pollEnd = source.indexOf("private void pruneRecoveryRequests()", pollStart);
        String poll = source.substring(pollStart, pollEnd);

        assertTrue(poll.contains("if (recoverySensorTypes.isEmpty())"));
        assertTrue(poll.contains("invalidateCarInfoProxy();"));
        assertTrue(poll.contains("else {\n                invalidateCarServices();"));
    }

    @Test public void synchronousRegistrationCallbackIsBufferedUntilSourceActivation()
            throws IOException {
        String source = source();
        int subscribeStart = source.indexOf(
                "public void subscribe(@NonNull BrickType type");
        int subscribeEnd = source.indexOf(
                "private void emitInitialBrickValue(", subscribeStart);
        String subscribe = source.substring(subscribeStart, subscribeEnd);

        int begin = subscribe.indexOf("beginOutdoorTemperatureSourceRegistration(s);");
        int register = subscribe.indexOf(
                "s.registerListener(subscription.sensorListener, sensorType)");
        int activate = subscribe.indexOf("activateOutdoorTemperatureSource(s)");
        assertTrue(begin >= 0);
        assertTrue(register > begin);
        assertTrue(activate > register);
        assertTrue(subscribe.contains("cancelled.set(true);"));
        assertTrue(subscribe.contains("cancelOutdoorTemperatureSourceRegistration(s);"));

        assertTrue(source.contains("pendingOutdoorBrickSource == source"));
        assertTrue(source.contains("outdoorTemperatureFilter.offer(\n"
                + "                        pendingOutdoorSample"));
        assertTrue(subscribe.contains("outdoorActivation.replayedSynchronousSample"));
        assertTrue(subscribe.contains("outdoorTemperatureMedianIfCurrentSource(\n"
                + "                        s, outdoorActivation.epoch)"));
        assertTrue(subscribe.contains("listener.onValue(type, replayed);"));
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
