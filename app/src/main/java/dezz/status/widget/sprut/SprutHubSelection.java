/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

/** Chooses the live relay row when hub.list contains stale duplicates or a stale saved serial. */
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

    /**
     * Resolves the row used for this session without mutating the user's configured serial.
     *
     * <p>Older Status Widget builds silently persisted the serial discovered while the field was
     * left blank. A relay can later retain that row as an offline/stale session while exposing one
     * different live row for the same account. In that unambiguous case, follow the only online
     * route. Never guess when two different hubs are online: an explicit serial remains binding
     * and a missing explicit serial remains an error.</p>
     */
    @Nullable
    static JSONObject selectForSession(@Nullable JSONArray hubs,
                                       @Nullable String configuredSerial) {
        String configured = configuredSerial == null ? "" : configuredSerial.trim();
        if (configured.isEmpty()) return select(hubs, "");

        JSONObject configuredRow = select(hubs, configured);
        JSONObject onlyOnline = singleOnlineSerial(hubs);
        if (onlyOnline == null) return configuredRow;
        if (configuredRow == null || presenceOf(configuredRow) != Presence.ONLINE) {
            return onlyOnline;
        }
        return configuredRow;
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

    @Nullable
    private static JSONObject singleOnlineSerial(@Nullable JSONArray hubs) {
        if (hubs == null) return null;
        JSONObject selected = null;
        String selectedSerial = "";
        for (int i = 0; i < hubs.length(); i++) {
            JSONObject candidate = hubs.optJSONObject(i);
            if (candidate == null || presenceOf(candidate) != Presence.ONLINE) continue;
            String serial = serialOf(candidate);
            if (serial.isEmpty()) continue;
            if (selected == null) {
                selected = candidate;
                selectedSerial = serial;
                continue;
            }
            if (!selectedSerial.equalsIgnoreCase(serial)) return null;
            if (isBetter(candidate, selected)) selected = candidate;
        }
        return selected;
    }
}
