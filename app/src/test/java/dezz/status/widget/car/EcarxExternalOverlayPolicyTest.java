/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EcarxExternalOverlayPolicyTest {
    @Test public void switchResponseMatchesBothRecordedInactiveBaselines() {
        assertFalse(EcarxExternalOverlayPolicy.isActive(0, 0));
        assertFalse(EcarxExternalOverlayPolicy.isActive(8, 0));
        assertTrue(EcarxExternalOverlayPolicy.isActive(3, 2));
    }

    @Test public void knownSwitchWinsOverLaggingVisionClose() {
        assertFalse(EcarxExternalOverlayPolicy.isActive(8, 1));
        assertFalse(EcarxExternalOverlayPolicy.isActive(0, 2));
    }

    @Test public void visionModeCoversStartupBeforeFirstSwitchSample() {
        assertFalse(EcarxExternalOverlayPolicy.isActive(null, 0));
        assertTrue(EcarxExternalOverlayPolicy.isActive(null, 1));
        assertTrue(EcarxExternalOverlayPolicy.isActive(null, 2));
    }

    @Test public void enabledParkingSystemCannotHoldNotificationsAfterGraphicsClose() {
        for (int parking = 0; parking <= 3; parking++) {
            assertFalse(EcarxExternalOverlayPolicy.isActive(8, 0, parking));
            assertFalse(EcarxExternalOverlayPolicy.isActive(0, 0, parking));
            assertFalse(EcarxExternalOverlayPolicy.isActive(null, null, parking));
        }
    }

    @Test public void parkingStatusCannotCancelAnIndependentlyVisibleCamera() {
        for (Integer parking : new Integer[]{null, 0, 1, 2, 3}) {
            assertTrue(EcarxExternalOverlayPolicy.isActive(3, 0, parking));
            assertTrue(EcarxExternalOverlayPolicy.isActive(null, 1, parking));
        }
    }
}
