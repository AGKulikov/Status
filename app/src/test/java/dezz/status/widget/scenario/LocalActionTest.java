/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LocalActionTest {
    @Test public void acceptsTheAutomationContractMaximumId() {
        String id = "a" + "b".repeat(127);
        LocalAction action = new LocalAction(TargetScope.POPUP, id, LocalField.VISIBLE, true);
        assertEquals(id, action.targetId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsColonJustLikeAutomationStateStore() {
        new LocalAction(TargetScope.POPUP, "popup:gate", LocalField.VISIBLE, true);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIdsLongerThanAutomationContractLimit() {
        new LocalAction(TargetScope.POPUP, "a" + "b".repeat(128), LocalField.VISIBLE, true);
    }
}
