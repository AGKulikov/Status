/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** iPhone-style display labels for helper-provided cellular radio generations. */
public final class PhoneNetworkTypePolicy {
    private PhoneNetworkTypePolicy() {}

    @NonNull
    public static String display(@Nullable String raw) {
        if (raw == null) return "";
        String value = raw.trim().toUpperCase(Locale.US)
                .replace('-', '_').replace(' ', '_');
        switch (value) {
            case "5G": return "5G";
            case "5G_UC": return "5G UC";
            case "5G_PLUS": return "5G+";
            case "5G_UW": return "5G UW";
            case "5G_E": return "5G E";
            case "LTE": return "LTE";
            case "4G": return "4G";
            case "3G": return "3G";
            case "2G": return "2G";
            case "EDGE":
            case "E": return "E";
            case "GPRS":
            case "G": return "G";
            case "1X": return "1x";
            case "SOS": return "SOS";
            case "SAT": return "SAT";
            default: return "";
        }
    }
}
