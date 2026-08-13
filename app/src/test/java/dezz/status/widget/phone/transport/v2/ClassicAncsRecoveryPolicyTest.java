/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static dezz.status.widget.phone.transport.v2.ClassicAncsRecoveryPolicy.EffectType.CANCEL_WAKEUP;
import static dezz.status.widget.phone.transport.v2.ClassicAncsRecoveryPolicy.EffectType.ENSURE_ROUTE;
import static dezz.status.widget.phone.transport.v2.ClassicAncsRecoveryPolicy.EffectType.REQUEST_SAME_ROUTE_RECOVERY;
import static dezz.status.widget.phone.transport.v2.ClassicAncsRecoveryPolicy.EffectType.SCHEDULE_WAKEUP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ClassicAncsRecoveryPolicyTest {
    @Test public void initialNoOwnerWhileClassicUpStartsRouteImmediately() {
        ClassicAncsRecoveryPolicy.Transition transition = observe(
                ClassicAncsRecoveryPolicy.State.initial(), true,
                IphoneTransportRecoveryStateV2.NO_OWNER, 1_000L);

        assertEquals(1, transition.state.recoveryCommands);
        assertEquals(ENSURE_ROUTE, transition.effects.get(0).type);
        assertEquals(SCHEDULE_WAKEUP, transition.effects.get(1).type);
    }

    @Test public void secondOwnerDownIsImmediateThenThirdWaitsForBackoff() {
        ClassicAncsRecoveryPolicy.State state = observe(
                ClassicAncsRecoveryPolicy.State.initial(), true,
                IphoneTransportRecoveryStateV2.NO_OWNER, 1_000L).state;
        state = observe(state, true, IphoneTransportRecoveryStateV2.PROGRESSING, 1_001L).state;

        ClassicAncsRecoveryPolicy.Transition second = observe(
                state, true, IphoneTransportRecoveryStateV2.OWNER_DOWN, 2_000L);
        assertEquals(2, second.state.recoveryCommands);
        assertEquals(REQUEST_SAME_ROUTE_RECOVERY, second.effects.get(0).type);

        state = observe(second.state, true,
                IphoneTransportRecoveryStateV2.PROGRESSING, 2_001L).state;
        ClassicAncsRecoveryPolicy.Transition third = observe(
                state, true, IphoneTransportRecoveryStateV2.OWNER_DOWN, 3_000L);
        assertEquals(2, third.state.recoveryCommands);
        assertEquals(1, third.effects.size());
        assertEquals(SCHEDULE_WAKEUP, third.effects.get(0).type);
        assertEquals(3_000L + ClassicAncsRecoveryPolicy.FIRST_BACKOFF_MS,
                third.state.deadlineMillis);
    }

    @Test public void dueWakeupsContinueIndefinitelyWithCappedBackoff() {
        ClassicAncsRecoveryPolicy.State state = twoCommandsThenDown();
        long now = state.deadlineMillis;
        for (int i = 0; i < 12; i++) {
            ClassicAncsRecoveryPolicy.Transition wake =
                    ClassicAncsRecoveryPolicy.wakeup(state, state.timerGeneration, now);
            assertTrue(wake.effects.stream()
                    .anyMatch(effect -> effect.type == REQUEST_SAME_ROUTE_RECOVERY));
            assertTrue(wake.effects.stream()
                    .anyMatch(effect -> effect.type == SCHEDULE_WAKEUP));
            state = wake.state;
            assertTrue(state.deadlineMillis > now);
            assertTrue(state.deadlineMillis - now <= ClassicAncsRecoveryPolicy.MAX_BACKOFF_MS);
            now = state.deadlineMillis;
        }
        assertEquals(14, state.recoveryCommands);
    }

    @Test public void readyResetsCounterAndCancelsOutstandingWakeup() {
        ClassicAncsRecoveryPolicy.State state = twoCommandsThenDown();
        ClassicAncsRecoveryPolicy.Transition ready = observe(
                state, true, IphoneTransportRecoveryStateV2.READY, 4_000L);

        assertEquals(ClassicAncsRecoveryPolicy.Phase.READY, ready.state.phase);
        assertEquals(0, ready.state.recoveryCommands);
        assertFalse(ready.state.hasWakeup());
        assertEquals(CANCEL_WAKEUP, ready.effects.get(0).type);
    }

    @Test public void retainedOwnerWaitsNeverRequestRecovery() {
        for (IphoneTransportRecoveryStateV2 wait : new IphoneTransportRecoveryStateV2[]{
                IphoneTransportRecoveryStateV2.WAIT_SERVICE_CHANGED,
                IphoneTransportRecoveryStateV2.WAIT_AUTHORIZATION
        }) {
            ClassicAncsRecoveryPolicy.State armed = observe(
                    ClassicAncsRecoveryPolicy.State.initial(), true,
                    IphoneTransportRecoveryStateV2.NO_OWNER, 1_000L).state;
            ClassicAncsRecoveryPolicy.Transition transition = observe(
                    armed, true, wait, 1_001L);
            assertFalse(transition.state.hasWakeup());
            assertFalse(transition.effects.stream().anyMatch(effect ->
                    effect.type == ENSURE_ROUTE
                            || effect.type == REQUEST_SAME_ROUTE_RECOVERY
                            || effect.type == SCHEDULE_WAKEUP));
        }
    }

    @Test public void staleOrEarlyTimerIsIgnored() {
        ClassicAncsRecoveryPolicy.State state = twoCommandsThenDown();
        assertSame(state, ClassicAncsRecoveryPolicy.wakeup(
                state, state.timerGeneration - 1L, state.deadlineMillis).state);
        assertSame(state, ClassicAncsRecoveryPolicy.wakeup(
                state, state.timerGeneration, state.deadlineMillis - 1L).state);
    }

    @Test public void classicLossCancelsAndFutureStaleWakeupDoesNothing() {
        ClassicAncsRecoveryPolicy.State state = twoCommandsThenDown();
        long staleGeneration = state.timerGeneration;
        long staleDeadline = state.deadlineMillis;

        ClassicAncsRecoveryPolicy.Transition lost = observe(
                state, false, IphoneTransportRecoveryStateV2.OWNER_DOWN, 4_000L);
        assertEquals(ClassicAncsRecoveryPolicy.Phase.NO_CLASSIC, lost.state.phase);
        assertEquals(0, lost.state.recoveryCommands);
        assertFalse(lost.state.hasWakeup());
        assertEquals(CANCEL_WAKEUP, lost.effects.get(0).type);
        assertTrue(ClassicAncsRecoveryPolicy.wakeup(
                lost.state, staleGeneration, staleDeadline).effects.isEmpty());
    }

    private static ClassicAncsRecoveryPolicy.State twoCommandsThenDown() {
        ClassicAncsRecoveryPolicy.State state = observe(
                ClassicAncsRecoveryPolicy.State.initial(), true,
                IphoneTransportRecoveryStateV2.NO_OWNER, 1_000L).state;
        state = observe(state, true,
                IphoneTransportRecoveryStateV2.PROGRESSING, 1_001L).state;
        state = observe(state, true,
                IphoneTransportRecoveryStateV2.OWNER_DOWN, 2_000L).state;
        state = observe(state, true,
                IphoneTransportRecoveryStateV2.PROGRESSING, 2_001L).state;
        return observe(state, true,
                IphoneTransportRecoveryStateV2.OWNER_DOWN, 3_000L).state;
    }

    private static ClassicAncsRecoveryPolicy.Transition observe(
            ClassicAncsRecoveryPolicy.State state, boolean classic,
            IphoneTransportRecoveryStateV2 route, long now) {
        return ClassicAncsRecoveryPolicy.observe(state, classic, route, now);
    }
}
