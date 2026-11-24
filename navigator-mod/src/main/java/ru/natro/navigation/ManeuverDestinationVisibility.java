/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import org.json.JSONObject;

/** The primary screen's hide is not an acknowledgement from either independent map. */
final class ManeuverDestinationVisibility {
    static String apply(String snapshot, String hudReplacement, String clusterReplacement) {
        try {
            JSONObject root = new JSONObject(snapshot);
            JSONObject card = new JSONObject(root.optString("maneuverCardJson", "{}"));
            if (!card.optBoolean("visible") || !card.has("primaryVisible")) return snapshot;
            boolean primaryVisible = card.getBoolean("primaryVisible");
            String identity = card.optString("maneuverIdentity", "");
            boolean current = !identity.isEmpty() && identity.equals(root.optString("maneuverIdentity", ""));
            if (!current) {
                card.put("visible", false).put("hudVisible", false).put("clusterVisible", false);
                root.put("maneuverCardJson", card.toString());
                return root.toString();
            }
            boolean hudReady = current && identity.equals(hudReplacement);
            boolean clusterReady = current && identity.equals(clusterReplacement);
            card.put("hudVisible", primaryVisible || !hudReady)
                    .put("clusterVisible", primaryVisible || !clusterReady);
            root.put("maneuverCardJson", card.toString());
            return root.toString();
        } catch (Exception invalid) {
            // Preserve the invalid/hidden frame so the receiving parser still rejects it.
            return snapshot;
        }
    }
    private ManeuverDestinationVisibility() {}
}
