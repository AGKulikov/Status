/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import org.json.JSONObject;
import java.util.function.Consumer;

/** The primary screen's hide is not an acknowledgement from either independent map. */
final class ManeuverDestinationVisibility {
    static String apply(String snapshot, String hudReplacement, String clusterReplacement) {
        return apply(snapshot, hudReplacement, clusterReplacement, null);
    }

    static String apply(String snapshot, String hudReplacement, String clusterReplacement,
                        Consumer<String> observe) {
        try {
            JSONObject root = new JSONObject(snapshot);
            JSONObject card = new JSONObject(root.optString("maneuverCardJson", "{}"));
            if (!card.optBoolean("visible") || !card.has("primaryVisible")) return snapshot;
            String identity = card.optString("maneuverIdentity", "");
            boolean current = !identity.isEmpty() && identity.equals(root.optString("maneuverIdentity", ""));
            boolean hudReady = current && identity.equals(hudReplacement);
            boolean clusterReady = current && identity.equals(clusterReplacement);
            if (observe != null) observe.accept("current=" + current + "; maneuver=" + identity
                    + "; source=" + card.optString("source", "fixed_command")
                    + "; hud_matches=" + hudReady + "; cluster_matches=" + clusterReady);
            if (!current) {
                card.put("visible", false).put("hudVisible", false).put("clusterVisible", false);
                root.put("maneuverCardJson", card.toString());
                return root.toString();
            }
            card.put("hudVisible", !hudReady)
                    .put("clusterVisible", !clusterReady);
            root.put("maneuverCardJson", card.toString());
            return root.toString();
        } catch (Exception invalid) {
            // Preserve the invalid/hidden frame so the receiving parser still rejects it.
            return snapshot;
        }
    }
    private ManeuverDestinationVisibility() {}
}
