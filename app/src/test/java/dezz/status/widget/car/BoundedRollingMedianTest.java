package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BoundedRollingMedianTest {

    @Test
    public void firstValidSampleIsImmediatelyAvailable() {
        BoundedRollingMedian filter = outdoorFilter();

        assertTrue(filter.offer(12.5f, 100L));
        assertEquals(1, filter.size());
        assertEquals(12.5f, filter.median(), 0f);
    }

    @Test
    public void rejectsNonFiniteSentinelsAndValuesOutsidePhysicalRange() {
        BoundedRollingMedian filter = outdoorFilter();

        assertFalse(filter.offer(Float.NaN, 0L));
        assertFalse(filter.offer(Float.POSITIVE_INFINITY, 0L));
        assertFalse(filter.offer(Float.NEGATIVE_INFINITY, 0L));
        assertFalse(filter.offer(Float.MIN_VALUE, 0L));
        assertFalse(filter.offer(-Float.MIN_VALUE, 0L));
        assertFalse(filter.offer(Float.MAX_VALUE, 0L));
        assertFalse(filter.offer(-Float.MAX_VALUE, 0L));
        assertFalse(filter.offer(-40.1f, 0L));
        assertFalse(filter.offer(85.1f, 0L));
        assertEquals(0, filter.size());
        assertTrue(Float.isNaN(filter.median()));

        assertTrue(filter.offer(-40f, 0L));
        assertTrue(filter.offer(85f, 1_000L));
        assertEquals(-40f, filter.median(), 0f); // reference lower-median warm-up rule
    }

    @Test
    public void acceptsAtMostOneSamplePerSecondWithoutConsumingInvalidTimestamps() {
        BoundedRollingMedian filter = outdoorFilter();

        assertTrue(filter.offer(10f, 10_000L));
        assertFalse(filter.offer(20f, 10_999L));
        assertEquals(1, filter.size());
        assertEquals(10f, filter.median(), 0f);

        assertTrue(filter.offer(20f, 11_000L));
        assertFalse(filter.offer(Float.NaN, 15_000L));
        // The invalid value above must not move the throttle timestamp.
        assertTrue(filter.offer(30f, 15_000L));
        assertEquals(3, filter.size());
        assertEquals(20f, filter.median(), 0f);
    }

    @Test
    public void retainsOnlyTheLatestFifteenAcceptedSamples() {
        BoundedRollingMedian filter = outdoorFilter();
        for (int value = 0; value < 15; value++) {
            assertTrue(filter.offer(value, value * 1_000L));
        }
        assertEquals(15, filter.size());
        assertEquals(7f, filter.median(), 0f);

        assertTrue(filter.offer(15f, 15_000L));
        assertEquals(15, filter.size());
        assertEquals(8f, filter.median(), 0f);
    }

    @Test
    public void medianRejectsASinglePlausibleSpike() {
        BoundedRollingMedian filter = outdoorFilter();
        for (int index = 0; index < 14; index++) {
            assertTrue(filter.offer(10f, index * 1_000L));
        }
        assertTrue(filter.offer(85f, 14_000L));

        assertEquals(15, filter.size());
        assertEquals(10f, filter.median(), 0f);
    }

    @Test
    public void resetStartsANewIndependentSensorSession() {
        BoundedRollingMedian filter = outdoorFilter();
        assertTrue(filter.offer(-20f, 5_000L));
        assertTrue(filter.offer(-10f, 6_000L));

        filter.reset();

        assertEquals(0, filter.size());
        assertTrue(Float.isNaN(filter.median()));
        // Reset clears throttle state too, so a new source can publish immediately.
        assertTrue(filter.offer(30f, 6_001L));
        assertEquals(30f, filter.median(), 0f);
    }

    @Test
    public void monotonicClockRollbackCreatesANewSessionInsteadOfFreezing() {
        BoundedRollingMedian filter = outdoorFilter();
        assertTrue(filter.offer(-5f, 50_000L));

        assertTrue(filter.offer(25f, 100L));

        assertEquals(1, filter.size());
        assertEquals(25f, filter.median(), 0f);
    }

    @Test
    public void resetEpochRejectsCallbackCapturedByPreviousSession() {
        BoundedRollingMedian filter = outdoorFilter();
        long previousEpoch = filter.epoch();
        filter.reset();

        float result = filter.offerIfEpoch(25f, 1_000L, previousEpoch);

        assertTrue(Float.isNaN(result));
        assertEquals(0, filter.size());
    }

    @Test
    public void staleResetCannotClearANewerSession() {
        BoundedRollingMedian filter = outdoorFilter();
        long previousEpoch = filter.epoch();
        filter.reset();
        assertTrue(filter.offer(25f, 1_000L));

        assertFalse(filter.resetIfEpoch(previousEpoch));
        assertEquals(1, filter.size());
        assertEquals(25f, filter.median(), 0f);
    }

    @Test
    public void currentResetAdvancesEpochAndClearsWindow() {
        BoundedRollingMedian filter = outdoorFilter();
        assertTrue(filter.offer(25f, 1_000L));
        long currentEpoch = filter.epoch();

        assertTrue(filter.resetIfEpoch(currentEpoch));
        assertEquals(currentEpoch + 1L, filter.epoch());
        assertEquals(0, filter.size());
    }

    @Test
    public void harmlessListenerReplacementKeepsCurrentSessionSample() {
        BoundedRollingMedian filter = outdoorFilter();
        long currentEpoch = filter.epoch();

        // Replacing a listener cancels only its queued UI delivery; it deliberately does not
        // reset the process-wide live sensor session or advance this epoch.
        float result = filter.offerIfEpoch(25f, 1_000L, currentEpoch);

        assertEquals(currentEpoch, filter.epoch());
        assertEquals(25f, result, 0f);
        assertEquals(1, filter.size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyCapacity() {
        new BoundedRollingMedian(0, -40f, 85f, 1_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidBounds() {
        new BoundedRollingMedian(15, 85f, -40f, 1_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeThrottle() {
        new BoundedRollingMedian(15, -40f, 85f, -1L);
    }

    private static BoundedRollingMedian outdoorFilter() {
        return new BoundedRollingMedian(15, -40f, 85f, 1_000L);
    }
}
