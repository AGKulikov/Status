/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

/** Route-shape payload sent only when the active route epoch changes or after an explicit request. */
public final class NavigationRouteGeometryV2 {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_JSON_CHARS = 384 * 1024;
    private static final int MAX_GEOMETRY_CHARS = 196_608;

    public final long routeEpoch;
    @NonNull public final String encodedPolyline;
    @NonNull public final String trafficSegmentsJson;

    public NavigationRouteGeometryV2(long routeEpoch, @NonNull String encodedPolyline,
            @NonNull String trafficSegmentsJson) {
        this.routeEpoch = Math.max(0L, routeEpoch);
        this.encodedPolyline = bounded(encodedPolyline, MAX_GEOMETRY_CHARS);
        this.trafficSegmentsJson = bounded(trafficSegmentsJson, MAX_GEOMETRY_CHARS);
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("schema", SCHEMA_VERSION)
                .put("routeEpoch", routeEpoch)
                .put("encodedPolyline", encodedPolyline)
                .put("trafficSegmentsJson", trafficSegmentsJson);
    }

    @NonNull
    public static NavigationRouteGeometryV2 fromJson(@NonNull String raw) {
        if (raw.length() > MAX_JSON_CHARS || raw.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Route geometry is too large");
        }
        try {
            JSONObject source = new JSONObject(raw);
            int schema = source.optInt("schema", SCHEMA_VERSION);
            if (schema != SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported route geometry schema " + schema);
            }
            return new NavigationRouteGeometryV2(
                    source.optLong("routeEpoch", 0L),
                    source.optString("encodedPolyline", ""),
                    source.optString("trafficSegmentsJson", ""));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid route geometry", error);
        }
    }

    @NonNull
    private static String bounded(String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        return value.length() <= maximum && value.indexOf('\u0000') < 0 ? value : "";
    }
}
