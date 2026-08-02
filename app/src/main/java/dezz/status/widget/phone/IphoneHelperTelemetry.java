/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One authenticated telemetry frame written by KX11 iPhone Helper over the encrypted
 * application characteristic.
 *
 * <p>The wire format deliberately fits the default 20-byte ATT payload:</p>
 * <ul>
 *     <li>{@code TEL2;P;60;1;C;42} — 60%, external power present, charging;</li>
 *     <li>{@code TEL2;N;LTE;43} — current cellular radio technology;</li>
 *     <li>{@code TEL3;60;1;C;6;44} — one atomic v3 snapshot containing both groups.</li>
 * </ul>
 * TEL3 uses a compact network code so the complete snapshot fits a 20-byte ATT notification.
 * Unknown values are represented by {@code -}. No value is inferred on Android.</p>
 */
public final class IphoneHelperTelemetry {
    private static final String PREFIX_V2 = "TEL2";
    private static final String PREFIX_V3 = "TEL3";

    public enum Kind { POWER, NETWORK, SNAPSHOT }

    @NonNull public final Kind kind;
    @Nullable public final Integer batteryLevel;
    @Nullable public final Boolean externalPower;
    @NonNull public final String chargeState;
    @NonNull public final String networkType;
    public final int sequence;

    private IphoneHelperTelemetry(@NonNull Kind kind,
                                  @Nullable Integer batteryLevel,
                                  @Nullable Boolean externalPower,
                                  @NonNull String chargeState,
                                  @NonNull String networkType,
                                  int sequence) {
        this.kind = kind;
        this.batteryLevel = batteryLevel;
        this.externalPower = externalPower;
        this.chargeState = chargeState;
        this.networkType = networkType;
        this.sequence = sequence;
    }

    @Nullable
    public static IphoneHelperTelemetry parse(@Nullable byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > 64) return null;
        String frame = new String(payload, StandardCharsets.UTF_8).trim();
        if (frame.isEmpty()) return null;
        String[] fields = frame.split(";", -1);
        if (fields.length < 4) return null;
        String prefix = fields[0].trim().toUpperCase(Locale.US);
        try {
            if (PREFIX_V3.equals(prefix) && fields.length == 6) {
                Integer level = nullablePercent(fields[1]);
                Boolean power = nullableBoolean(fields[2]);
                String state = normalizeChargeState(fields[3]);
                String type = networkTypeFromCompactCode(fields[4]);
                int sequence = sequence(fields[5]);
                if (level == null && !"-".equals(fields[1].trim())) return null;
                if (power == null && !"-".equals(fields[2].trim())) return null;
                if (state.isEmpty() || type == null) return null;
                return new IphoneHelperTelemetry(
                        Kind.SNAPSHOT, level, power, state, type, sequence);
            }
            if (!PREFIX_V2.equals(prefix)) return null;
            String kind = fields[1].trim().toUpperCase(Locale.US);
            if ("P".equals(kind) && fields.length == 6) {
                Integer level = nullablePercent(fields[2]);
                Boolean power = nullableBoolean(fields[3]);
                String state = normalizeChargeState(fields[4]);
                int sequence = sequence(fields[5]);
                if (level == null && !"-".equals(fields[2].trim())) return null;
                if (power == null && !"-".equals(fields[3].trim())) return null;
                if (state.isEmpty()) return null;
                return new IphoneHelperTelemetry(
                        Kind.POWER, level, power, state, "", sequence);
            }
            if ("N".equals(kind) && fields.length == 4) {
                String type = normalizeNetworkType(fields[2]);
                int sequence = sequence(fields[3]);
                if (type == null) return null;
                return new IphoneHelperTelemetry(
                        Kind.NETWORK, null, null, "", type, sequence);
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }

    /** Compact TEL3 vocabulary. Values stay stable on the wire across iOS SDK spellings. */
    @Nullable
    private static String networkTypeFromCompactCode(@NonNull String raw) {
        switch (raw.trim().toUpperCase(Locale.US)) {
            case "-": return "";
            case "1": return "5G";
            case "2": return "5G_UC";
            case "3": return "5G_PLUS";
            case "4": return "5G_UW";
            case "5": return "5G_E";
            case "6": return "LTE";
            case "7": return "4G";
            case "8": return "3G";
            case "9": return "E";
            case "A": return "G";
            case "B": return "1X";
            case "C": return "SOS";
            case "D": return "SAT";
            default: return null;
        }
    }

    @Nullable
    private static Integer nullablePercent(@NonNull String raw) {
        String value = raw.trim();
        if ("-".equals(value)) return null;
        int parsed = Integer.parseInt(value);
        return parsed >= 0 && parsed <= 100 ? parsed : null;
    }

    @Nullable
    private static Boolean nullableBoolean(@NonNull String raw) {
        String value = raw.trim();
        if ("1".equals(value)) return Boolean.TRUE;
        if ("0".equals(value)) return Boolean.FALSE;
        return null;
    }

    @NonNull
    private static String normalizeChargeState(@NonNull String raw) {
        switch (raw.trim().toUpperCase(Locale.US)) {
            case "C": return "charging";
            case "F": return "full";
            case "U": return "unplugged";
            case "X": return "unknown";
            default: return "";
        }
    }

    /** Returns an empty string for a helper-reported unavailable/unknown cellular service. */
    @Nullable
    private static String normalizeNetworkType(@NonNull String raw) {
        String value = raw.trim().toUpperCase(Locale.US)
                .replace('-', '_').replace(' ', '_');
        if ("-".equals(raw.trim()) || "NONE".equals(value) || "UNKNOWN".equals(value)) {
            return "";
        }
        switch (value) {
            case "5G":
            case "5G_UC":
            case "5G_PLUS":
            case "5G_UW":
            case "5G_E":
            case "LTE":
            case "4G":
            case "3G":
            case "E":
            case "G":
            case "1X":
            case "SOS":
            case "SAT":
                return value;
            default:
                return null;
        }
    }

    private static int sequence(@NonNull String raw) {
        int parsed = Integer.parseInt(raw.trim());
        if (parsed < 0 || parsed > 9_999) throw new NumberFormatException("sequence");
        return parsed;
    }
}
