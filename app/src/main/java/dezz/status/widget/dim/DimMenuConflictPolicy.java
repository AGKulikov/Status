/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.dim;

import androidx.annotation.NonNull;

/** Priority rules preventing the custom menu from covering stock safety surfaces. */
public final class DimMenuConflictPolicy {
    public enum Reason {
        NONE, DISABLED, DISPLAY_OFF, ENGINE_OFF, MNAVI, CONTROL_CENTER, OTHER_DIM_TAB
    }

    private DimMenuConflictPolicy() {}

    @NonNull
    public static Reason reason(boolean enabled, boolean displayInteractive,
                                boolean engineOn, boolean mnavActive, int dimTab,
                                int controlCenterState,
                                @NonNull DimMenuPanelConfig config) {
        if (!enabled) return Reason.DISABLED;
        if (config.hideWhenDisplayOff && !displayInteractive) return Reason.DISPLAY_OFF;
        if (!engineOn) return Reason.ENGINE_OFF;
        if (config.hideForMnav && mnavActive) return Reason.MNAVI;
        if (config.hideForControlCenter && controlCenterState != 0) {
            return Reason.CONTROL_CENTER;
        }
        if (config.navigationTabOnly && dimTab >= 0
                && dimTab != DimMenuPanelConfig.STOCK_NAVIGATION_TAB) {
            return Reason.OTHER_DIM_TAB;
        }
        return Reason.NONE;
    }
}
