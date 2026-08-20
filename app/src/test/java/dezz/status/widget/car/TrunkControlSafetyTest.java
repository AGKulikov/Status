/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class TrunkControlSafetyTest {
    private static CarControlState state(boolean open) {
        return new CarControlState(TrunkControlSafety.CONTROL_ID, true, true,
                open ? 1d : 0d, open ? "Открыт" : "Закрыт", open,
                open ? 1 : 0, null, 1L);
    }

    @Test public void genericTapBecomesAnExactOpenOrCloseTarget() {
        CarControlCommand toggle = new CarControlCommand(TrunkControlSafety.CONTROL_ID,
                CarControlCommand.Operation.TOGGLE, 0d);
        CarControlCommand opening = TrunkControlSafety.resolve(toggle, state(false));
        CarControlCommand closing = TrunkControlSafety.resolve(toggle, state(true));

        assertEquals(CarControlCommand.Operation.SET, opening.operation);
        assertEquals(1d, opening.value, 0d);
        assertEquals(CarControlCommand.Operation.SET, closing.operation);
        assertEquals(0d, closing.value, 0d);
    }

    @Test public void unknownStateFailsSafeToConfirmedOpeningAndIconsFollowReadback() {
        CarControlCommand toggle = new CarControlCommand(TrunkControlSafety.CONTROL_ID,
                CarControlCommand.Operation.TOGGLE, 0d);
        assertEquals(1d, TrunkControlSafety.resolve(toggle, null).value, 0d);
        assertEquals(TrunkControlSafety.ICON_CLOSED,
                TrunkControlSafety.iconKey("custom", state(false)));
        assertEquals(TrunkControlSafety.ICON_OPEN,
                TrunkControlSafety.iconKey("custom", state(true)));
    }
}
