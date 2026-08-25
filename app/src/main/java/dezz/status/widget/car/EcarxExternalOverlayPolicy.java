/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.Nullable;

/** Pure interpretation of the KX11 external display/parking overlay signals. */
public final class EcarxExternalOverlayPolicy {
    /** {@code SwtDispOnAndOffStsResp}: 3 while the external panel owns the display. */
    public static final int PROPERTY_DISPLAY_SWITCH_STATUS = 29021;
    /** {@code VisnImgDispModResp}: 1/2 while a vehicle image mode is entering/visible. */
    public static final int PROPERTY_VISION_IMAGE_MODE = 29043;
    /** {@code PrkgDstCtrlSts}: 2 while the park-distance overlay owns the display; 3 is idle. */
    public static final int PROPERTY_PARKING_DISTANCE_CONTROL_STATUS = 28995;

    private EcarxExternalOverlayPolicy() {
    }

    public static boolean isProperty(int propertyId) {
        return propertyId == PROPERTY_DISPLAY_SWITCH_STATUS
                || propertyId == PROPERTY_VISION_IMAGE_MODE
                || propertyId == PROPERTY_PARKING_DISTANCE_CONTROL_STATUS;
    }

    /**
     * The switch response is authoritative once observed: inactive values differ by firmware
     * ({@code 0} and {@code 8} both occur). Vision mode is only the startup fallback because its
     * closing transition can lag the switch response.
     */
    public static boolean isActive(@Nullable Integer displaySwitchStatus,
                                   @Nullable Integer visionImageMode) {
        return isActive(displaySwitchStatus, visionImageMode, null);
    }

    /**
     * Parking distance control is independent from the 360-camera switch. The exact KX11 capture
     * starts at idle {@code 3}, changes to {@code 2} for the parktronic window, then returns to
     * {@code 3} when switching to 360. Camera activity remains covered by switch {@code 3} and
     * vision modes {@code 1/2}.
     */
    public static boolean isActive(@Nullable Integer displaySwitchStatus,
                                   @Nullable Integer visionImageMode,
                                   @Nullable Integer parkingDistanceStatus) {
        if (parkingDistanceStatus != null && parkingDistanceStatus == 2) return true;
        if (displaySwitchStatus != null) return displaySwitchStatus == 3;
        return visionImageMode != null
                && (visionImageMode == 1 || visionImageMode == 2);
    }
}
