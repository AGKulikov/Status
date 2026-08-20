/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CarTelemetryExporterTest {
    @Test public void retryBackoffUsesOneTwoFiveTenThirtySecondsAndCaps() {
        assertEquals(1_000L, CarTelemetryExporter.retryDelayMillis(1));
        assertEquals(2_000L, CarTelemetryExporter.retryDelayMillis(2));
        assertEquals(5_000L, CarTelemetryExporter.retryDelayMillis(3));
        assertEquals(10_000L, CarTelemetryExporter.retryDelayMillis(4));
        assertEquals(30_000L, CarTelemetryExporter.retryDelayMillis(5));
        assertEquals(30_000L, CarTelemetryExporter.retryDelayMillis(100));
    }

    @Test public void authoritativeMismatchDuringSuccessfulRpcForcesReconcile() {
        assertEquals(CarTelemetryExporter.CompletionDisposition.RECONCILE_AUTHORITATIVE,
                CarTelemetryExporter.completionDisposition(false, true, 7L, 7L));
    }

    @Test public void unchangedSuccessfulRpcCanBeAccepted() {
        assertEquals(CarTelemetryExporter.CompletionDisposition.ACCEPT,
                CarTelemetryExporter.completionDisposition(false, false, 7L, 7L));
    }

    @Test public void newerVehicleSampleStillWinsWithoutAuthoritativeMismatch() {
        assertEquals(CarTelemetryExporter.CompletionDisposition.SEND_LATEST,
                CarTelemetryExporter.completionDisposition(false, false, 8L, 7L));
    }

    @Test public void failedRpcAlwaysRetriesLatestEvenWhenVersionsMatch() {
        assertEquals(CarTelemetryExporter.CompletionDisposition.RETRY_LATEST,
                CarTelemetryExporter.completionDisposition(true, false, 7L, 7L));
    }

    @Test public void booleanReconciliationAcceptsLegacyZeroOneWrappers() {
        assertTrue(CarTelemetryExporter.valuesEqual(Boolean.TRUE, 1));
        assertTrue(CarTelemetryExporter.valuesEqual(Boolean.TRUE, -5L));
        assertTrue(CarTelemetryExporter.valuesEqual(Boolean.FALSE, 0d));
        assertTrue(CarTelemetryExporter.valuesEqual(Boolean.TRUE, "on"));
        assertTrue(CarTelemetryExporter.valuesEqual(Boolean.FALSE, "0"));
        assertFalse(CarTelemetryExporter.valuesEqual(Boolean.FALSE, 1));
        assertFalse(CarTelemetryExporter.valuesEqual(Boolean.TRUE, "unknown"));
    }
}
