/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DriverControlSpacingPolicyTest {
    @Test public void explicitBackInsetsOwnBothAdjacentBoundaries() {
        DriverControlSpacingPolicy.Layout layout = DriverControlSpacingPolicy.resolve(
                300,
                new int[]{50, 50, 30},
                new int[]{-1, -1, 0},
                new int[]{-1, -1, 0});

        // Explicit Back/top=0 suppresses the previous untouched auto side. Back/bottom=0 is exact.
        assertArrayEquals(new int[]{57, 56, 0}, layout.topPadding);
        assertArrayEquals(new int[]{57, 0, 0}, layout.bottomPadding);
    }

    @Test public void allZeroInsetsAreTrueZeroAndDoNotStretchButtons() {
        DriverControlSpacingPolicy.Layout layout = DriverControlSpacingPolicy.resolve(
                720,
                new int[]{54, 33},
                new int[]{0, 0},
                new int[]{0, 0});
        assertArrayEquals(new int[]{0, 0}, layout.topPadding);
        assertArrayEquals(new int[]{0, 0}, layout.bottomPadding);
    }

    @Test public void fixedBackHeightDoesNotUncentreUntouchedHomeButton() {
        int fixedAuto = DriverButtonHeightPolicy.spacingRequest(30, -1);
        DriverControlSpacingPolicy.Layout layout = DriverControlSpacingPolicy.resolve(
                300,
                new int[]{50, 50, 30},
                new int[]{-1, -1, fixedAuto},
                new int[]{-1, -1, fixedAuto});

        assertEquals(layout.topPadding[1], layout.bottomPadding[1]);
        assertEquals(0, layout.topPadding[2]);
        assertEquals(0, layout.bottomPadding[2]);
        int occupied = 0;
        int[] natural = {50, 50, 30};
        for (int index = 0; index < natural.length; index++) {
            occupied += natural[index]
                    + layout.topPadding[index] + layout.bottomPadding[index];
        }
        assertEquals(300, occupied);
    }

    @Test public void untouchedControlsStillFillTheAvailableRailEvenly() {
        DriverControlSpacingPolicy.Layout layout = DriverControlSpacingPolicy.resolve(
                300,
                new int[]{50, 50},
                new int[]{-1, -1},
                new int[]{-1, -1});
        assertArrayEquals(new int[]{50, 50}, layout.topPadding);
        assertArrayEquals(new int[]{50, 50}, layout.bottomPadding);
    }

    @Test public void explicitLastButtonEndsAtTheBottomWithoutCollapsingEarlierControls() {
        int[] natural = {60, 70, 55, 60, 33};
        DriverControlSpacingPolicy.Layout layout = DriverControlSpacingPolicy.resolve(
                720, natural,
                new int[]{-1, -1, -1, -1, 0},
                new int[]{-1, -1, -1, -1, 0});

        assertEquals(0, layout.topPadding[4]);
        assertEquals(0, layout.bottomPadding[4]);
        assertEquals(0, layout.bottomPadding[3]);
        int occupied = 0;
        for (int index = 0; index < natural.length; index++) {
            occupied += natural[index]
                    + layout.topPadding[index] + layout.bottomPadding[index];
        }
        assertEquals(720, occupied);
    }
}
