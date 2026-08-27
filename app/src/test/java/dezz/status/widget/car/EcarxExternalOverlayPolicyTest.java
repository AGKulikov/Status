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

    @Test public void parkingDistanceSignalIndependentlyBracketsParktronicOverlay() {
        assertFalse(EcarxExternalOverlayPolicy.isActive(8, 0, 1));
        assertTrue(EcarxExternalOverlayPolicy.isActive(8, 0, 2));
        assertTrue(EcarxExternalOverlayPolicy.isActive(8, 0, 3));
        assertTrue(EcarxExternalOverlayPolicy.isActive(3, 1, 3));
    }

    @Test public void parkingDistanceThreeDoesNotNeedCameraSideSignals() {
        assertTrue(EcarxExternalOverlayPolicy.isActive(8, 0, 3));
        assertTrue(EcarxExternalOverlayPolicy.isActive(0, 0, 3));
        assertTrue(EcarxExternalOverlayPolicy.isActive(null, null, 3));
    }
}
