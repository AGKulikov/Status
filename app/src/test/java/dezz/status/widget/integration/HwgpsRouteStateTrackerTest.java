/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HwgpsRouteStateTrackerTest {
    @Test public void initialNoFixWithoutAnArmedRouteStaysUnavailable() {
        HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();

        HwgpsRouteStateTracker.Update update = tracker.accept("notFixed", 1_000L);

        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE, update.state);
        assertFalse(update.requestSnapshot);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, update.deadlineAtMs);
    }

    @Test public void transientLossIsCancelledByFreshAvailableSnapshot() {
        HwgpsRouteStateTracker tracker = armedTracker();
        HwgpsRouteStateTracker.Update candidate = tracker.accept("notFixed", 1_000L);
        assertTrue(candidate.requestSnapshot);
        assertEquals(9_000L, candidate.deadlineAtMs);

        HwgpsRouteStateTracker.Update recovered = tracker.accept("fix_ok", 1_100L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, recovered.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, recovered.deadlineAtMs);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.evaluate(30_000L).state);
    }

    @Test public void confirmedLossUsesOemEightSecondMinimum() {
        HwgpsRouteStateTracker tracker = armedTracker();
        tracker.accept("notFixed", 1_000L);
        HwgpsRouteStateTracker.Update corroborated = tracker.accept("notFixed", 1_100L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, corroborated.state);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.evaluate(8_999L).state);
        HwgpsRouteStateTracker.Update finalProbe = tracker.evaluate(9_000L);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, finalProbe.state);
        assertTrue(finalProbe.requestSnapshot);
        assertEquals(21_000L, finalProbe.deadlineAtMs);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.accept("notFixed", 9_001L).state);
    }

    @Test public void singleUncorroboratedLossExpiresInsteadOfFiring() {
        HwgpsRouteStateTracker tracker = armedTracker();
        tracker.accept("notFixed", 1_000L);

        HwgpsRouteStateTracker.Update update = tracker.evaluate(9_000L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, update.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, update.deadlineAtMs);
    }

    @Test public void lateStaleLossAfterRecoveryIsCancelledByRequery() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        assertTrue(tracker.accept("fix_ok", 9_100L).requestSnapshot);
        tracker.accept("fix_ok", 9_200L);
        assertTrue(tracker.evaluate(19_100L).requestSnapshot);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.accept("fix_ok", 19_101L).state);

        HwgpsRouteStateTracker.Update stale = tracker.accept("notFixed", 20_000L);
        assertTrue(stale.requestSnapshot);
        HwgpsRouteStateTracker.Update fresh = tracker.accept("fix_ok", 20_100L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, fresh.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, fresh.deadlineAtMs);
    }

    @Test public void finalLossProbeCanStillCancelQueuedStaleSamples() {
        HwgpsRouteStateTracker tracker = armedTracker();
        tracker.accept("notFixed", 1_000L);
        tracker.accept("notFixed", 1_100L);
        assertTrue(tracker.evaluate(9_000L).requestSnapshot);

        HwgpsRouteStateTracker.Update fresh = tracker.accept("fix_ok", 9_001L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE, fresh.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, fresh.deadlineAtMs);
    }

    @Test public void confirmedLossNeedsTenSecondHealthyRecovery() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();

        HwgpsRouteStateTracker.Update candidate = tracker.accept("fix_ok", 9_100L);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST, candidate.state);
        assertTrue(candidate.requestSnapshot);
        tracker.accept("fix_ok", 9_200L);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.evaluate(19_099L).state);
        HwgpsRouteStateTracker.Update finalProbe = tracker.evaluate(19_100L);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST, finalProbe.state);
        assertTrue(finalProbe.requestSnapshot);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.accept("fix_ok", 19_101L).state);
    }

    @Test public void repeatedLostAndHealthySamplesDoNotRestartHysteresis() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.accept("notFixed", 9_100L).state);

        assertTrue(tracker.accept("fix_ok", 10_000L).requestSnapshot);
        tracker.accept("fix_dr", 15_000L);
        assertTrue(tracker.evaluate(20_000L).requestSnapshot);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.accept("fix_ok", 20_001L).state);
    }

    @Test public void staleHealthyRecoveryIsRejectedByCurrentLostSnapshot() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        assertTrue(tracker.accept("fix_ok", 10_000L).requestSnapshot);

        HwgpsRouteStateTracker.Update current = tracker.accept("notFixed", 10_001L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST, current.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, current.deadlineAtMs);
    }

    @Test public void noFixAlsoCancelsTheFinalRecoveryProbe() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        tracker.accept("fix_ok", 10_000L);
        tracker.accept("fix_ok", 10_100L);
        assertTrue(tracker.evaluate(20_000L).requestSnapshot);

        HwgpsRouteStateTracker.Update current = tracker.accept("notFixed", 20_001L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST, current.state);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, current.deadlineAtMs);
    }

    @Test public void unansweredFinalRecoveryProbeFailsUnavailableAtBound() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        tracker.accept("fix_ok", 10_000L);
        tracker.accept("fix_ok", 10_100L);
        assertTrue(tracker.evaluate(20_000L).requestSnapshot);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.evaluate(29_999L).state);
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                tracker.evaluate(30_000L).state);
    }

    @Test public void spoofingAndGeofilterFireImmediatelyEvenWithoutBaseline() {
        for (String adverse : new String[] {"spoofing", "filtered"}) {
            HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
            HwgpsRouteStateTracker.Update update = tracker.accept(adverse, 0L);
            assertEquals(adverse, HwgpsRouteStatePolicy.State.ROUTE_LOST, update.state);
            assertFalse(update.requestSnapshot);
        }
    }

    @Test public void activeNavigationRouteArmsMidLossSubscription() {
        HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
        assertTrue(tracker.setNavigationRouteActive(true).requestSnapshot);
        HwgpsRouteStateTracker.Update first = tracker.accept("notFixed", 1_000L);
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE, first.state);
        assertTrue(first.requestSnapshot);
        tracker.accept("notFixed", 1_100L);
        assertTrue(tracker.evaluate(9_000L).requestSnapshot);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.accept("notFixed", 9_001L).state);
    }

    @Test public void idleNotFixedRemainsUnavailableWithoutRouteOrHealthyFix() {
        HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
        tracker.setNavigationRouteActive(false);
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                tracker.accept("notFixed", 1_000L).state);
    }

    @Test public void explicitAdverseDoesNotEraseConfirmedNoFixRecoveryDwell() {
        HwgpsRouteStateTracker tracker = confirmedLostTracker();
        tracker.accept("filtered", 10_000L);

        HwgpsRouteStateTracker.Update candidate = tracker.accept("fix_ok", 11_000L);

        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST, candidate.state);
        assertTrue(candidate.requestSnapshot);
        tracker.accept("fix_ok", 11_100L);
        assertTrue(tracker.evaluate(21_000L).requestSnapshot);
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_AVAILABLE,
                tracker.accept("fix_ok", 21_001L).state);
    }

    @Test public void initialExplicitAdverseDoesNotArmLaterPlainNoFix() {
        HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
        assertEquals(HwgpsRouteStatePolicy.State.ROUTE_LOST,
                tracker.accept("spoofing", 1_000L).state);

        HwgpsRouteStateTracker.Update plainNoFix = tracker.accept("notFixed", 1_100L);

        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE, plainNoFix.state);
        assertFalse(plainNoFix.requestSnapshot);
        assertEquals(HwgpsRouteStateTracker.NO_DEADLINE, plainNoFix.deadlineAtMs);
    }

    @Test public void malformedStateFailsUnavailableAndDisarmsOldBaseline() {
        HwgpsRouteStateTracker tracker = armedTracker();
        assertEquals(HwgpsRouteStatePolicy.State.UNAVAILABLE,
                tracker.accept("future_state", 100L).state);
        assertFalse(tracker.accept("notFixed", 200L).requestSnapshot);
    }

    private static HwgpsRouteStateTracker armedTracker() {
        HwgpsRouteStateTracker tracker = new HwgpsRouteStateTracker();
        tracker.accept("fix_ok", 0L);
        return tracker;
    }

    private static HwgpsRouteStateTracker confirmedLostTracker() {
        HwgpsRouteStateTracker tracker = armedTracker();
        tracker.accept("notFixed", 1_000L);
        tracker.accept("notFixed", 1_100L);
        tracker.evaluate(9_000L);
        tracker.accept("notFixed", 9_001L);
        return tracker;
    }
}
