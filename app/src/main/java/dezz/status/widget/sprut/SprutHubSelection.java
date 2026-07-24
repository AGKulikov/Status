/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/** Chooses the live relay row when hub.list contains stale duplicates for one serial. */
final class SprutHubSelection {
    enum Presence { ONLINE, UNKNOWN, OFFLINE }

    private SprutHubSelection() {}

    /** Returns the best matching row, or {@code null} when a configured serial is absent. */
    @Nullable
    static JSONObject select(@Nullable JSONArray hubs, @Nullable String configuredSerial) {
        if (hubs == null) return null;
        String configured = configuredSerial == null ? "" : configuredSerial.trim();
        JSONObject selected = null;
        for (int i = 0; i < hubs.length(); i++) {
            JSONObject candidate = hubs.optJSONObject(i);
            if (candidate == null) continue;
            if (!configured.isEmpty()
                    && !configured.equalsIgnoreCase(serialOf(candidate))) continue;
            if (selected == null || isBetter(candidate, selected)) selected = candidate;
        }
        return selected;
    }

    @NonNull
    static String serialOf(@NonNull JSONObject hub) {
        String serial = hub.optString("serial", "").trim();
        if (!serial.isEmpty()) return serial;
        serial = hub.optString("serialNumber", "").trim();
        if (!serial.isEmpty()) return serial;
        return hub.optString("id", "").trim();
    }

    @NonNull
    static Presence presenceOf(@NonNull JSONObject hub) {
        if (!hub.has("online") || hub.isNull("online")) return Presence.UNKNOWN;
        Object value = hub.opt("online");
        if (value instanceof Boolean) {
            return (Boolean) value ? Presence.ONLINE : Presence.OFFLINE;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0 ? Presence.ONLINE : Presence.OFFLINE;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text) || "online".equalsIgnoreCase(text)) {
                return Presence.ONLINE;
            }
            if ("false".equalsIgnoreCase(text) || "offline".equalsIgnoreCase(text)) {
                return Presence.OFFLINE;
            }
        }
        return Presence.UNKNOWN;
    }

    private static boolean isBetter(JSONObject candidate, JSONObject selected) {
        int candidateRank = presenceRank(presenceOf(candidate));
        int selectedRank = presenceRank(presenceOf(selected));
        if (candidateRank != selectedRank) return candidateRank > selectedRank;
        return numericLastSeen(candidate) > numericLastSeen(selected);
    }

    private static int presenceRank(Presence presence) {
        switch (presence) {
            case ONLINE: return 2;
            case UNKNOWN: return 1;
            case OFFLINE:
            default: return 0;
        }
    }

    private static long numericLastSeen(JSONObject hub) {
        Object value = hub.opt("lastSeen");
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }
}
