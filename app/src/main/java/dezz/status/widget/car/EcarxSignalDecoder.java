/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pure value/name decoding for the low-level ECARX signal fallback.
 *
 * <p>The property identifiers and encodings mirror the channels used by mHUD 6.1. Keeping the
 * decoder free of Android and proprietary ECARX types makes the risky reflection boundary small
 * and lets the mappings be covered by ordinary JVM tests.</p>
 */
public final class EcarxSignalDecoder {
    static final int PROPERTY_GEAR_ACTUAL = 31_414;
    static final int PROPERTY_GEAR_SELECTOR = 31_385;

    static final int ADAPT_GEAR_1 = 2_097_665;
    static final int ADAPT_GEAR_10 = 2_097_674;
    static final int ADAPT_GEAR_NEUTRAL = 2_097_680;
    static final int ADAPT_GEAR_DRIVE = 2_097_696;
    static final int ADAPT_GEAR_PARK = 2_097_712;
    static final int ADAPT_GEAR_REVERSE = 2_097_728;
    /** Internal-only encoding outside every observed AdaptAPI range; decoded before display. */
    static final int SYNTHETIC_MANUAL_GEAR_1 = -10_001;
    static final int SYNTHETIC_MANUAL_GEAR_10 = -10_010;

    private static final List<String> HIGH_BEAM_NAMES = Arrays.asList(
            "extrltgstshibeam", "hibeamsts", "hibeam_sts", "highbeamsts",
            "high_beam_sts", "hibeamstate", "highbeamstate", "hibeamsta",
            "highbeamsta", "hibeamlight", "highbeamlight", "extrltghibeam",
            "highbeamactv", "hibeamactv");

    private static final List<String> MANUAL_MODE_NAMES = Arrays.asList(
            "mnlmod", "manualmode", "manual_mode", "mnl_mod", "tiptronic",
            "tip_tronic", "trnsmshftmod", "trnsmshftmd", "trnsm_shft_md",
            "shftmod_st", "shftmd_st", "gearmode", "gear_mode", "ptmanual",
            "trnsmmnl", "manualshift", "mnl_shift", "sportmnl", "msportmod");

    private EcarxSignalDecoder() {}

    static Integer selectorToAdaptGear(int raw) {
        switch (raw) {
            case 0: return ADAPT_GEAR_PARK;
            case 1: return ADAPT_GEAR_REVERSE;
            case 2: return ADAPT_GEAR_NEUTRAL;
            case 3:
            case 4:
                return ADAPT_GEAR_DRIVE;
            default:
                return null;
        }
    }

    /** Return 1..10, accepting both the low-level and AdaptAPI encodings. */
    static int normalizeActualGear(int raw) {
        if (raw >= 1 && raw <= 10) return raw;
        if (raw >= ADAPT_GEAR_1 && raw <= ADAPT_GEAR_10) {
            return raw - (ADAPT_GEAR_1 - 1);
        }
        return 0;
    }

    /**
     * Compose the selector and active ratio into the existing ISensor.gear value domain.
     * P/R/N always win; D gains a numbered AdaptAPI gear when PtGearAct is valid.
     */
    static Integer composeAdaptGear(Integer selectorRaw,
                                    Integer actualGearRaw,
                                    boolean manualMode) {
        if (selectorRaw == null) return null;
        Integer selector = selectorToAdaptGear(selectorRaw);
        if (selector == null || selector != ADAPT_GEAR_DRIVE) return selector;
        int actual = actualGearRaw == null ? 0 : normalizeActualGear(actualGearRaw);
        if (actual == 0) return ADAPT_GEAR_DRIVE;
        return manualMode ? SYNTHETIC_MANUAL_GEAR_1 - actual + 1
                : ADAPT_GEAR_1 + actual - 1;
    }

    static int manualGearNumber(int value) {
        return value <= SYNTHETIC_MANUAL_GEAR_1 && value >= SYNTHETIC_MANUAL_GEAR_10
                ? SYNTHETIC_MANUAL_GEAR_1 - value + 1 : 0;
    }

    /** Human-readable mHUD-compatible gear text for the launcher panel. */
    public static String gearDisplayName(long raw) {
        if (raw == ADAPT_GEAR_NEUTRAL) return "N";
        if (raw == ADAPT_GEAR_DRIVE) return "D";
        if (raw == ADAPT_GEAR_PARK) return "P";
        if (raw == ADAPT_GEAR_REVERSE) return "R";
        if (raw >= ADAPT_GEAR_1 && raw <= ADAPT_GEAR_10) {
            return "D" + (raw - ADAPT_GEAR_1 + 1);
        }
        if (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE) {
            int manual = manualGearNumber((int) raw);
            if (manual > 0) return "M" + manual;
        }
        return null;
    }

    /** mHUD treats 1..252 as manual and the 253..255 status/sentinel range as not manual. */
    static boolean isManualModeValue(int raw) {
        return raw > 0 && raw < 253;
    }

    /** mHUD's steady high-beam signal uses 0=off and 1..253=on; negatives/254+ are unknown. */
    static int normalizeHighBeam(int raw) {
        if (raw == 0) return 0;
        if (raw > 0 && raw < 254) return 1;
        return -1;
    }

    static boolean isHighBeamPropertyName(String name) {
        String normalized = normalizeName(name);
        if (normalized.contains("flash") || normalized.contains("autohibeam")
                || normalized.contains("auto_hibeam")) {
            return false;
        }
        return containsAny(normalized, HIGH_BEAM_NAMES);
    }

    static boolean isManualModePropertyName(String name) {
        String normalized = normalizeName(name);
        if (normalized.contains("gearlvrposn") || normalized.contains("shiftlvrposn")
                || normalized.contains("paddle")) {
            return false;
        }
        return containsAny(normalized, MANUAL_MODE_NAMES);
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String value, List<String> needles) {
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    /**
     * Decode values returned by the different ECARX firmware wrappers. Reflection is deliberately
     * shallow and bounded: Number/Boolean/String, then getData/getStatus/getValue at most twice.
     */
    static Integer coerceInteger(Object value) {
        return coerceInteger(value, 0);
    }

    private static Integer coerceInteger(Object value, int depth) {
        if (value == null || depth > 2) return null;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof Boolean) return (Boolean) value ? 1 : 0;
        if (value instanceof String) {
            try {
                return Integer.valueOf(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        for (String methodName : new String[] { "getData", "getStatus", "getValue" }) {
            try {
                Method method = value.getClass().getMethod(methodName);
                if (method.getParameterTypes().length != 0) continue;
                Integer decoded = coerceInteger(method.invoke(value), depth + 1);
                if (decoded != null) return decoded;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next wrapper convention.
            }
        }
        return null;
    }
}
