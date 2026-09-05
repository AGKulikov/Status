/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.Nullable;

/** Camera display signals. Parking-system status is diagnostic, not proof of a visible window. */
public final class EcarxExternalOverlayPolicy {
    /** {@code SwtDispOnAndOffStsResp}: 3 while the external panel owns the display. */
    public static final int PROPERTY_DISPLAY_SWITCH_STATUS = 29021;
    /** {@code VisnImgDispModResp}: 1/2 while a vehicle image mode is entering/visible. */
    public static final int PROPERTY_VISION_IMAGE_MODE = 29043;
    /**
     * {@code PrkgDstCtrlSts}: operating status of the parking distance system. Values 2/3 can
     * outlive its on-screen graphics; retain the subscription for diagnostics only.
     */
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
     * Only camera display responses can create this hold. The parking UI has its own independently
     * observed window state, combined with this result by WidgetService. A parked/enabled sensor
     * system must never keep a notification's paused expiry at Long.MAX_VALUE after its UI closes.
     */
    public static boolean isActive(@Nullable Integer displaySwitchStatus,
                                   @Nullable Integer visionImageMode,
                                   @Nullable Integer parkingDistanceStatus) {
        if (displaySwitchStatus != null) return displaySwitchStatus == 3;
        return visionImageMode != null
                && (visionImageMode == 1 || visionImageMode == 2);
    }
}
