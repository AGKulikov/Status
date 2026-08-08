/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Locale;

/** Read-only KX11 ADAS/steering signals confirmed in the supplied vehicle dumps. */
public final class EcarxAdasSignalCatalog {
    static final int ADJUSTABLE_SPEED_LIMITER_STATE = 31354;
    static final int CRUISE_CONTROLLER_STATE = 31363;
    static final int TIME_GAP_LONGITUDINAL_CONTROL = 29024;
    static final int BUTTON_L3_REQUEST = 30861;
    static final int LEFT_STEERING_SPECIAL_FUNCTION = 30891;
    static final int ASY_A_LAT_INDICATOR = 28916;
    static final int ASY_A_LGT_INDICATOR = 28917;
    static final int ASY_A_LGT_STATE = 29044;
    static final int IHU_SET_DISPLAY_AD = 28965;
    static final int STEERING_WHEEL_BUTTON_PRESSED = 30469;
    static final int BUTTON_CONFIRM_REQUEST = 30860;

    private static final Definition[] DEFINITIONS = {
            new Definition(ASY_A_LAT_INDICATOR, "AsyALatIndcr", "getAsyALatIndcr", false),
            new Definition(ASY_A_LGT_INDICATOR, "AsyALgtIndcr", "getAsyALgtIndcr", false),
            new Definition(IHU_SET_DISPLAY_AD, "IHUSetDispAD", "getIHUSetDispAD", false),
            new Definition(TIME_GAP_LONGITUDINAL_CONTROL, "TiGapSetForLgtCtrl",
                    "getTiGapSetForLgtCtrl", false),
            new Definition(ASY_A_LGT_STATE, "AsyALgtSts", "getAsyALgtStsAsyALgtSts", false),
            new Definition(STEERING_WHEEL_BUTTON_PRESSED, "SteerWhlBtnPsd",
                    "getSteerWhlBtnPsd", true),
            new Definition(BUTTON_CONFIRM_REQUEST, "BtnConfiReq", "getBtnConfiReq", true),
            new Definition(BUTTON_L3_REQUEST, "BtnL3Req", "getBtnL3Req", true),
            new Definition(LEFT_STEERING_SPECIAL_FUNCTION, "LeSteerWhlTouchSpecialFunction",
                    "getLeSteerWhlTouchSpecialFunction", true),
            new Definition(ADJUSTABLE_SPEED_LIMITER_STATE, "AdjSpdLimnSts",
                    "getAdjSpdLimnSts", false),
            new Definition(CRUISE_CONTROLLER_STATE, "CrsCtrlrSts", "getCrsCtrlrSts", false)
    };

    /*
     * Read-only discovery candidates used only while ActionRecorder is active.  The original
     * eleven IDs were too narrow on the tested KX11 firmware: a complete limiter cycle and set
     * speed changes between 30 and 37 km/h produced no matching event.  Keep a small fallback
     * list so capture still works while PropertyIdString is being populated, then augment it at
     * runtime by name via isDiscoveryPropertyName().
     */
    private static final int[] DISCOVERY_FALLBACK_PROPERTY_IDS = {
            28691, // OffsForSpdWarnSetgReq
            28707, // SpdAlrmActvForRoadSgnInfoSts
            28986, // OffsForSpdWarnSetgSts
            29018, // SpdAlrmActvSts
            29045, 29046, // AsyALgtSts checksum/counter
            30800, 30856, // SpeedWarnOnOffReq / SpeedWarnSetReq
            30862, 30863, 30864, 30865, 30866, 30867, 30868, // BtnR1Req..BtnR7Req
            30912, 30913, 30914, 30915, // steering-wheel touch requests
            30943, 30944, 30945, 30946, 30947, // right touch-board state/data
            32768, 32773, // cbasyaccandtsr / cbasyspeedcompensation
            32907, // cbsyssetspdunit
            33044, 33046, // cbspeedwarnset / cbspeedwarnonoff
            33287, 33292, // paasyaccandtsr / paasyspeedcompensation
            33462, // pasyssetspdunit
            33655, 33657, 33658 // speed warning state/on-off/threshold
    };

    private EcarxAdasSignalCatalog() { }

    @NonNull static int[] propertyIds() {
        int[] result = new int[DEFINITIONS.length];
        for (int index = 0; index < DEFINITIONS.length; index++) {
            result[index] = DEFINITIONS[index].propertyId;
        }
        return result;
    }

    @NonNull static int[] discoveryFallbackPropertyIds() {
        return DISCOVERY_FALLBACK_PROPERTY_IDS.clone();
    }

    /** Matches vendor names that can carry limiter/cruise selection, set speed or input state. */
    static boolean isDiscoveryPropertyName(@Nullable String rawName) {
        if (rawName == null) return false;
        String name = rawName.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty()) return false;
        return name.contains("adjspdlimn")
                || name.contains("crsctrl")
                || name.contains("drvrcrs")
                || name.contains("spdlim")
                || name.contains("speedlim")
                || name.contains("spdwarn")
                || name.contains("speedwarn")
                || name.contains("asyacc")
                || name.contains("asyspeedcompensation")
                || name.contains("asyalgt")
                || name.contains("tigapsetforlgtctrl")
                || name.contains("steerwhlbtn")
                || name.contains("steerwhltouch")
                || name.matches(".*btnr[1-7]req.*")
                || name.contains("btnconfireq")
                || name.contains("btnl3req");
    }

    static boolean contains(int propertyId) { return find(propertyId) != null; }

    @NonNull static String signalName(int propertyId) {
        Definition definition = find(propertyId);
        return definition == null ? "property_" + propertyId : definition.signalName;
    }

    @Nullable static String getterName(int propertyId) {
        Definition definition = find(propertyId);
        return definition == null ? null : definition.getterName;
    }

    @NonNull static String signalKind(int propertyId) {
        Definition definition = find(propertyId);
        if (definition == null) return "vehicle_control_discovery";
        return definition.steeringInput ? "steering_input" : "adas_state";
    }

    @NonNull static String decode(int propertyId, int value) {
        if (propertyId == ASY_A_LAT_INDICATOR || propertyId == ASY_A_LGT_INDICATOR) {
            return indicatorState(value);
        }
        if (propertyId == TIME_GAP_LONGITUDINAL_CONTROL) {
            if (value == 0) return "None";
            return value >= 1 && value <= 3 ? "TimeGap_" + value : invalid(value);
        }
        if (propertyId == ASY_A_LGT_STATE) return longitudinalState(value);
        if (propertyId == ADJUSTABLE_SPEED_LIMITER_STATE) {
            switch (value) {
                case 1: return "Off";
                case 2: return "Standby";
                case 3: return "Active";
                case 4: return "Override";
                default: return invalid(value);
            }
        }
        if (propertyId == CRUISE_CONTROLLER_STATE) {
            switch (value) {
                case 1: return "Off";
                case 2: return "Standby";
                case 3: return "Active";
                default: return invalid(value);
            }
        }
        return "raw=" + value;
    }

    @NonNull static String idSummary() { return Arrays.toString(propertyIds()); }

    @NonNull static String discoveryFallbackIdSummary() {
        return Arrays.toString(discoveryFallbackPropertyIds());
    }

    @Nullable private static Definition find(int propertyId) {
        for (Definition definition : DEFINITIONS) {
            if (definition.propertyId == propertyId) return definition;
        }
        return null;
    }

    @NonNull private static String indicatorState(int value) {
        switch (value) {
            case 0: return "NoDisplay";
            case 1: return "Off";
            case 2: return "Standby";
            case 3: return "Active";
            case 4: return "Override";
            case 5: return "Failure";
            default: return invalid(value);
        }
    }

    @NonNull private static String longitudinalState(int value) {
        switch (value) {
            case 0: return "Reserved0";
            case 1: return "Standby";
            case 2: return "Active";
            case 3: return "Reserved3";
            case 4: return "Override";
            case 5: return "StandActive";
            case 6: return "StandWait";
            case 7: return "TemporaryFailure";
            case 8: return "PermanentFailure";
            case 9: return "Reserved9";
            default: return invalid(value);
        }
    }

    @NonNull private static String invalid(int value) { return "Invalid(" + value + ")"; }

    private static final class Definition {
        final int propertyId;
        @NonNull final String signalName;
        @NonNull final String getterName;
        final boolean steeringInput;

        Definition(int propertyId, @NonNull String signalName,
                   @NonNull String getterName, boolean steeringInput) {
            this.propertyId = propertyId;
            this.signalName = signalName;
            this.getterName = getterName;
            this.steeringInput = steeringInput;
        }
    }
}
