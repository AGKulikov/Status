/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import androidx.annotation.Nullable;

/** Pure interpretation of the KX11 external display/parking overlay signals. */
public final class EcarxExternalOverlayPolicy {
    /** {@code SwtDispOnAndOffStsResp}: 3 while the external panel owns the display. */
    public static final int PROPERTY_DISPLAY_SWITCH_STATUS = 29021;
    /** {@code VisnImgDispModResp}: 1/2 while a vehicle image mode is entering/visible. */
    public static final int PROPERTY_VISION_IMAGE_MODE = 29043;

    private EcarxExternalOverlayPolicy() {
    }

    public static boolean isProperty(int propertyId) {
        return propertyId == PROPERTY_DISPLAY_SWITCH_STATUS
                || propertyId == PROPERTY_VISION_IMAGE_MODE;
    }

    /**
     * The switch response is authoritative once observed: inactive values differ by firmware
     * ({@code 0} and {@code 8} both occur). Vision mode is only the startup fallback because its
     * closing transition can lag the switch response.
     */
    public static boolean isActive(@Nullable Integer displaySwitchStatus,
                                   @Nullable Integer visionImageMode) {
        if (displaySwitchStatus != null) return displaySwitchStatus == 3;
        return visionImageMode != null
                && (visionImageMode == 1 || visionImageMode == 2);
    }
}
