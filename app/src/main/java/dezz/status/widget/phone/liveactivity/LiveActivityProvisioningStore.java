/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.liveactivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Private persistence for non-secret ActivityKit tokens learned on the authenticated C5 route. */
public final class LiveActivityProvisioningStore {
    private static final String PREFS = "live_activity_push_provisioning_v1";
    private static final String KEY_PUSH_TO_START = "pushToStart";
    private static final String KEY_CONFIGURATION = "configuration";
    private static final String KEY_ACTIVITIES = "activities";

    public static final class Configuration {
        public final boolean automaticStart;
        public final boolean showVehicle;
        @NonNull public final int[] climateControlIds;
        @NonNull public final int[] functionControlIds;
        @NonNull public final String vehicleName;

        Configuration(boolean automaticStart, boolean showVehicle,
                      @NonNull int[] climateControlIds, @NonNull int[] functionControlIds,
                      @NonNull String vehicleName) {
            this.automaticStart = automaticStart;
            this.showVehicle = showVehicle;
            this.climateControlIds = climateControlIds;
            this.functionControlIds = functionControlIds;
            this.vehicleName = vehicleName;
        }

        @Nullable
        static Configuration decode(@Nullable byte[] payload) {
            if (payload == null || payload.length < 11 || payload[0] != 1) return null;
            int nameLength = payload[10] & 0xff;
            if (nameLength > 48 || payload.length != 11 + nameLength) return null;
            int[] climate = new int[4];
            int[] functions = new int[4];
            for (int index = 0; index < 4; index++) {
                climate[index] = payload[2 + index] & 0xff;
                functions[index] = payload[6 + index] & 0xff;
                if (climate[index] == 0 || functions[index] == 0) return null;
            }
            String name = new String(payload, 11, nameLength, StandardCharsets.UTF_8).trim();
            if (name.isEmpty()) name = "GEELY MONJARO";
            int flags = payload[1] & 0xff;
            return new Configuration((flags & 1) != 0, (flags & 2) != 0,
                    climate, functions, name);
        }
    }

    public static final class ActivityToken {
        public final int panel;
        @NonNull public final String activityId;
        @NonNull public final byte[] token;

        ActivityToken(int panel, @NonNull String activityId, @NonNull byte[] token) {
            this.panel = panel;
            this.activityId = activityId;
            this.token = token;
        }

        @Nullable
        static ActivityToken decode(@Nullable byte[] payload, boolean requiresToken) {
            if (payload == null || payload.length < 4 || payload[0] != 1) return null;
            int panel = payload[1] & 0xff;
            int idLength = payload[2] & 0xff;
            if (panel > 1 || idLength < 1 || idLength > 96
                    || payload.length < 3 + idLength + (requiresToken ? 2 : 0)) return null;
            String id = new String(payload, 3, idLength, StandardCharsets.UTF_8).trim();
            if (id.isEmpty()) return null;
            if (!requiresToken) {
                return payload.length == 3 + idLength
                        ? new ActivityToken(panel, id, new byte[0]) : null;
            }
            int tokenLengthOffset = 3 + idLength;
            int tokenLength = payload[tokenLengthOffset] & 0xff;
            if (tokenLength < 16 || tokenLength > 64
                    || payload.length != tokenLengthOffset + 1 + tokenLength) return null;
            byte[] token = new byte[tokenLength];
            System.arraycopy(payload, tokenLengthOffset + 1, token, 0, tokenLength);
            return new ActivityToken(panel, id, token);
        }
    }

    private final SharedPreferences prefs;

    public LiveActivityProvisioningStore(@NonNull Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized boolean accept(@NonNull LiveActivityPushProtocolV1.Message message) {
        switch (message.type) {
            case LiveActivityPushProtocolV1.TYPE_PUSH_TO_START_TOKEN:
                if (message.payload.length < 16 || message.payload.length > 64) return false;
                return prefs.edit().putString(KEY_PUSH_TO_START, encode(message.payload)).commit();
            case LiveActivityPushProtocolV1.TYPE_CONFIGURATION:
                if (Configuration.decode(message.payload) == null) return false;
                return prefs.edit().putString(KEY_CONFIGURATION, encode(message.payload)).commit();
            case LiveActivityPushProtocolV1.TYPE_ACTIVITY_PUSH_TOKEN:
                ActivityToken activity = ActivityToken.decode(message.payload, true);
                return activity != null && putActivity(activity);
            case LiveActivityPushProtocolV1.TYPE_ACTIVITY_ENDED:
                ActivityToken ended = ActivityToken.decode(message.payload, false);
                return ended != null && removeActivity(ended.activityId);
            default:
                return false;
        }
    }

    @Nullable public synchronized byte[] pushToStartToken() {
        return decode(prefs.getString(KEY_PUSH_TO_START, ""));
    }

    @Nullable public synchronized Configuration configuration() {
        return Configuration.decode(decode(prefs.getString(KEY_CONFIGURATION, "")));
    }

    @NonNull public synchronized List<ActivityToken> activityTokens() {
        String raw = prefs.getString(KEY_ACTIVITIES, "[]");
        try {
            JSONArray array = new JSONArray(raw);
            List<ActivityToken> result = new ArrayList<>();
            for (int index = 0; index < array.length(); index++) {
                JSONObject item = array.optJSONObject(index);
                if (item == null) continue;
                byte[] token = decode(item.optString("token", ""));
                String id = item.optString("id", "").trim();
                int panel = item.optInt("panel", -1);
                if (token != null && token.length >= 16 && !id.isEmpty()
                        && panel >= 0 && panel <= 1) {
                    result.add(new ActivityToken(panel, id, token));
                }
            }
            return Collections.unmodifiableList(result);
        } catch (JSONException invalid) {
            return Collections.emptyList();
        }
    }

    public synchronized boolean readyForStart() {
        Configuration configuration = configuration();
        return configuration != null && configuration.automaticStart
                && pushToStartToken() != null;
    }

    private boolean putActivity(@NonNull ActivityToken token) {
        JSONArray array = activityArray();
        JSONArray replacement = new JSONArray();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.optJSONObject(index);
            if (item != null && !token.activityId.equals(item.optString("id"))) {
                replacement.put(item);
            }
        }
        try {
            replacement.put(new JSONObject()
                    .put("id", token.activityId)
                    .put("panel", token.panel)
                    .put("token", encode(token.token)));
        } catch (JSONException impossible) {
            return false;
        }
        return prefs.edit().putString(KEY_ACTIVITIES, replacement.toString()).commit();
    }

    private boolean removeActivity(@NonNull String activityId) {
        JSONArray source = activityArray();
        JSONArray replacement = new JSONArray();
        boolean removed = false;
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item == null) continue;
            if (activityId.equals(item.optString("id"))) removed = true;
            else replacement.put(item);
        }
        return !removed || prefs.edit().putString(KEY_ACTIVITIES, replacement.toString()).commit();
    }

    public synchronized void clearActivities() {
        prefs.edit().remove(KEY_ACTIVITIES).apply();
    }

    private JSONArray activityArray() {
        try {
            return new JSONArray(prefs.getString(KEY_ACTIVITIES, "[]"));
        } catch (JSONException invalid) {
            return new JSONArray();
        }
    }

    @NonNull private static String encode(@NonNull byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    @Nullable private static byte[] decode(@Nullable String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Base64.decode(value, Base64.DEFAULT);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }
}
