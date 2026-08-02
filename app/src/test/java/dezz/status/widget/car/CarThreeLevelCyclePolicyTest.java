/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class CarThreeLevelCyclePolicyTest {
    @Test public void exposesBothUserRequestedOrdersAndExcludesOffAndAuto() {
        CarControlDescriptor control = descriptor(Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(11, "1"),
                new CarControlDescriptor.Option(22, "2"),
                new CarControlDescriptor.Option(33, "3"),
                new CarControlDescriptor.Option(99, "Auto")));
        assertEquals(Arrays.asList(11d, 22d, 33d),
                CarThreeLevelCyclePolicy.orderedValues(control, false));
        assertEquals(Arrays.asList(33d, 22d, 11d),
                CarThreeLevelCyclePolicy.orderedValues(control, true));
    }

    @Test public void doesNotOfferThreeLevelOrderForAnArbitraryOptionControl() {
        assertTrue(CarThreeLevelCyclePolicy.orderedValues(descriptor(Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(1, "Low"),
                new CarControlDescriptor.Option(2, "High"))), false).isEmpty());
    }

    private static CarControlDescriptor descriptor(
            java.util.List<CarControlDescriptor.Option> options) {
        return new CarControlDescriptor("climate.test", "Тест", "Климат", "seat_heat",
                CarControlDescriptor.Kind.LEVELS, CarControlDescriptor.Availability.SUPPORTED,
                options, 0, 3, 1, "", "#FFFFFFFF");
    }
}
