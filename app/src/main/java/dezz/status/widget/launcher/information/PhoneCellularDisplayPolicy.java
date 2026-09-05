/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Selects the independently configurable pieces of the combined iPhone cellular tile. */
public final class PhoneCellularDisplayPolicy {
    private PhoneCellularDisplayPolicy() {
    }

    /**
     * Signal is represented by the live bar icon. Operator and radio generation form the value
     * text. Keeping this composition in one pure policy makes the settings preview, width
     * measurement and real driver rail agree exactly.
     */
    @NonNull
    public static Presentation resolve(@Nullable Integer signalPercent,
                                       @Nullable String operator,
                                       @Nullable String networkType,
                                       boolean showSignal,
                                       boolean showOperator,
                                       boolean showNetworkType) {
        String cleanOperator = clean(operator);
        String cleanNetworkType = clean(networkType);
        List<String> parts = new ArrayList<>(2);
        if (showNetworkType && !cleanNetworkType.isEmpty()) parts.add(cleanNetworkType);
        if (showOperator && !cleanOperator.isEmpty()) parts.add(cleanOperator);
        String text = join(parts);
        boolean signalKnown = showSignal && signalPercent != null;
        boolean known = signalKnown
                || showOperator && !cleanOperator.isEmpty()
                || showNetworkType && !cleanNetworkType.isEmpty();
        boolean active = showOperator && !cleanOperator.isEmpty()
                || showNetworkType && !cleanNetworkType.isEmpty()
                || signalKnown && signalPercent > 0;
        return new Presentation(text, known, active);
    }

    @NonNull
    private static String clean(@Nullable String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    @NonNull
    private static String join(@NonNull List<String> parts) {
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (result.length() > 0) result.append(" · ");
            result.append(part);
        }
        return result.toString();
    }

    /** Stable first-layout width before delayed Helper/HFP telemetry replaces the em dash. */
    @NonNull
    public static String measurementFallback(boolean showOperator,
                                             boolean showNetworkType) {
        if (showNetworkType && showOperator) return "LTE · оператор";
        if (showNetworkType) return "LTE";
        if (showOperator) return "оператор";
        return "";
    }

    public static final class Presentation {
        @NonNull public final String text;
        public final boolean known;
        public final boolean active;

        Presentation(@NonNull String text, boolean known, boolean active) {
            this.text = text;
            this.known = known;
            this.active = active;
        }
    }
}
