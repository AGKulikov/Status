/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.WindowManager;

import org.junit.Test;

public final class ClimateReservationWindowPolicyTest {
    @Test public void topUsesStatusBarAndEveryOtherEdgeUsesNavigationBar() {
        assertEquals("STATUS_BAR", ClimateReservationWindowPolicy.codeForEdge(
                ClimateReservationWindowPolicy.EDGE_TOP));
        assertEquals("NAVIGATION_BAR", ClimateReservationWindowPolicy.codeForEdge(
                ClimateReservationWindowPolicy.EDGE_BOTTOM));
        assertEquals("NAVIGATION_BAR", ClimateReservationWindowPolicy.codeForEdge(
                ClimateReservationWindowPolicy.EDGE_LEFT));
        assertEquals("NAVIGATION_BAR", ClimateReservationWindowPolicy.codeForEdge(
                ClimateReservationWindowPolicy.EDGE_RIGHT));
    }

    @Test public void onlyDedicatedSystemWindowTypeIsAccepted() {
        assertEquals(2069, ClimateReservationWindowPolicy.sanitizeType(2069));
        assertTrue(ClimateReservationWindowPolicy.isVendorType(2069));

        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                ClimateReservationWindowPolicy.sanitizeType(-1));
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                ClimateReservationWindowPolicy.sanitizeType(
                        WindowManager.LayoutParams.TYPE_APPLICATION));
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                ClimateReservationWindowPolicy.sanitizeType(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY));
        assertFalse(ClimateReservationWindowPolicy.isVendorType(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY));
    }

    @Test public void verifierRequiresTheRequestedWorkAreaReductionOnCorrectAxis() {
        assertTrue(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_BOTTOM, 180,
                1920, 1080, 1920, 900));
        assertTrue(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_TOP, 180,
                1920, 1080, 1920, 904)); // four-pixel policy rounding is allowed
        assertTrue(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_RIGHT, 180,
                1920, 1080, 1740, 1080));
        assertFalse(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_LEFT, 180,
                1920, 1080, 1920, 900));
        assertFalse(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_BOTTOM, 180,
                1920, 1080, 1920, 1079));
        assertFalse(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_BOTTOM, 180,
                1920, 1080, 1920, 800)); // unrelated larger resize
        assertFalse(ClimateReservationAreaPolicy.isReserved(
                ClimateReservationWindowPolicy.EDGE_BOTTOM, 180,
                1920, 1080, 1080, 1740)); // orientation/display geometry changed
    }
}
