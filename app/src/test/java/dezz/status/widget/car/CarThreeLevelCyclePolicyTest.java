/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class CarThreeLevelCyclePolicyTest {
    @Test public void exposesBothUserRequestedOrdersWithOffAndExcludesAuto() {
        CarControlDescriptor control = descriptor(Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(11, "1"),
                new CarControlDescriptor.Option(22, "2"),
                new CarControlDescriptor.Option(33, "3"),
                new CarControlDescriptor.Option(99, "Auto")));
        assertEquals(Arrays.asList(0d, 11d, 22d, 33d),
                CarThreeLevelCyclePolicy.orderedValues(control, false));
        assertEquals(Arrays.asList(33d, 22d, 11d, 0d),
                CarThreeLevelCyclePolicy.orderedValues(control, true));
    }

    @Test public void usesTheRealVendorOffValueIncludingForSteeringWheelHeat() {
        CarControlDescriptor control = descriptor("climate.wheel_heat", "wheel_heat",
                Arrays.asList(new CarControlDescriptor.Option(700, "0"),
                        new CarControlDescriptor.Option(11, "1"),
                        new CarControlDescriptor.Option(22, "2"),
                        new CarControlDescriptor.Option(33, "3"),
                        new CarControlDescriptor.Option(99, "Auto")));
        assertEquals(Arrays.asList(700d, 11d, 22d, 33d),
                CarThreeLevelCyclePolicy.orderedValues(control, false));
        assertEquals(Arrays.asList(33d, 22d, 11d, 700d),
                CarThreeLevelCyclePolicy.orderedValues(control, true));
    }

    @Test public void customThreeLevelSubsetCannotDropOff() {
        CarControlDescriptor control = descriptor(Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(11, "1"),
                new CarControlDescriptor.Option(22, "2"),
                new CarControlDescriptor.Option(33, "3")));
        assertEquals(Arrays.asList(0d, 11d, 33d),
                CarThreeLevelCyclePolicy.withMandatoryOff(
                        control, Arrays.asList(11d, 33d)));
        assertEquals(Arrays.asList(33d, 22d, 11d, 0d),
                CarThreeLevelCyclePolicy.withMandatoryOff(
                        control, Arrays.asList(33d, 22d, 11d, 0d)));
    }

    @Test public void doesNotOfferThreeLevelOrderForAnArbitraryOptionControl() {
        CarControlDescriptor arbitrary = descriptor(Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(1, "Low"),
                new CarControlDescriptor.Option(2, "High")));
        assertTrue(CarThreeLevelCyclePolicy.orderedValues(arbitrary, false).isEmpty());
        assertEquals(Arrays.asList(1d, 2d), CarThreeLevelCyclePolicy.withMandatoryOff(
                arbitrary, Arrays.asList(1d, 2d)));
    }

    @Test public void fanWithAdditionalManualLevelsKeepsGenericAutoSemantics() {
        CarControlDescriptor fan = descriptor("climate.fan", "fan", Arrays.asList(
                new CarControlDescriptor.Option(0, "Выкл"),
                new CarControlDescriptor.Option(1, "1"),
                new CarControlDescriptor.Option(2, "2"),
                new CarControlDescriptor.Option(3, "3"),
                new CarControlDescriptor.Option(4, "4"),
                new CarControlDescriptor.Option(9, "9"),
                new CarControlDescriptor.Option(99, "Auto")));
        assertTrue(CarThreeLevelCyclePolicy.orderedValues(fan, false).isEmpty());
        assertEquals(Arrays.asList(3d, 1d), CarThreeLevelCyclePolicy.withMandatoryOff(
                fan, Arrays.asList(3d, 1d)));
    }

    private static CarControlDescriptor descriptor(
            java.util.List<CarControlDescriptor.Option> options) {
        return descriptor("climate.test", "seat_heat", options);
    }

    private static CarControlDescriptor descriptor(String id, String icon,
            java.util.List<CarControlDescriptor.Option> options) {
        return new CarControlDescriptor(id, "Тест", "Климат", icon,
                CarControlDescriptor.Kind.LEVELS, CarControlDescriptor.Availability.SUPPORTED,
                options, 0, 3, 1, "", "#FFFFFFFF");
    }
}
