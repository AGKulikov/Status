/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DriverButtonHeightPolicyTest {
    @Test public void explicitHeightIsExactAndDoesNotConsumeAutomaticSpacing() {
        assertTrue(DriverButtonHeightPolicy.isExplicit(33));
        assertEquals(33, DriverButtonHeightPolicy.resolvedHeight(120, 33, 1f));
        assertTrue(DriverButtonHeightPolicy.isFixedAutoSpacingRequest(
                DriverButtonHeightPolicy.spacingRequest(33, -1)));
        assertEquals(0, DriverButtonHeightPolicy.spacingRequest(33, 24));
    }

    @Test public void untouchedButtonKeepsNaturalHeightAndReceivesRemainingSpace() {
        assertFalse(DriverButtonHeightPolicy.isExplicit(0));
        assertEquals(72, DriverButtonHeightPolicy.resolvedHeight(72, 0, 1f));
        assertEquals(-1, DriverButtonHeightPolicy.spacingRequest(0, -1));
        assertEquals(19, DriverButtonHeightPolicy.internalPadding(
                0, -1, 19, 1f, 72));
    }

    @Test public void fixedButtonPaddingStaysInsideItsOwnHeight() {
        assertEquals(0, DriverButtonHeightPolicy.internalPadding(
                32, -1, 100, 1f, 32));
        assertEquals(16, DriverButtonHeightPolicy.internalPadding(
                32, 80, 0, 1f, 32));
        assertEquals(8, DriverButtonHeightPolicy.resolvedHeight(32, 16, .5f));
    }
}
