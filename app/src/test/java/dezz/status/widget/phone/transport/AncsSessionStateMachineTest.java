/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AncsSessionStateMachineTest {
    @Test public void staleConnectAndWatchdogCannotAdvanceReplacementSession() {
        AncsSessionStateMachine state = new AncsSessionStateMachine();
        long first = state.begin(AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        long replacement = state.begin(AncsSessionStateMachine.Phase.SCANNING);

        assertFalse(state.move(first, AncsSessionStateMachine.Phase.READY));
        assertTrue(state.move(replacement, AncsSessionStateMachine.Phase.DIRECT_CONNECT));
        assertEquals(AncsSessionStateMachine.Phase.DIRECT_CONNECT, state.phase());
    }

    @Test public void closeInvalidatesEveryOutstandingOperation() {
        AncsSessionStateMachine state = new AncsSessionStateMachine();
        long session = state.begin(AncsSessionStateMachine.Phase.DISCOVERING);
        state.close();

        assertFalse(state.isCurrent(session));
        assertFalse(state.move(session, AncsSessionStateMachine.Phase.READY));
        assertEquals(AncsSessionStateMachine.Phase.CLOSED, state.phase());
    }

    @Test public void readyLinkCanEnterNonDestructiveLivenessProbe() {
        AncsSessionStateMachine state = new AncsSessionStateMachine();
        long session = state.begin(AncsSessionStateMachine.Phase.BACKGROUND_CONNECT);
        assertTrue(state.move(session, AncsSessionStateMachine.Phase.DISCOVERING));
        assertTrue(state.move(session, AncsSessionStateMachine.Phase.SUBSCRIBING));
        assertTrue(state.move(session, AncsSessionStateMachine.Phase.READY));
        assertTrue(state.move(session, AncsSessionStateMachine.Phase.VERIFYING_LINK));
        assertTrue(state.move(session, AncsSessionStateMachine.Phase.READY));
    }
}
