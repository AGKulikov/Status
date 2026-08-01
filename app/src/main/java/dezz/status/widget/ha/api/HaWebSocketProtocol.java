/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

/** Stateless encoder/parser for the documented Home Assistant WebSocket protocol. */
public final class HaWebSocketProtocol {
    public static final String TYPE_AUTH_REQUIRED = "auth_required";
    public static final String TYPE_AUTH_OK = "auth_ok";
    public static final String TYPE_AUTH_INVALID = "auth_invalid";
    public static final String TYPE_RESULT = "result";
    public static final String TYPE_EVENT = "event";
    public static final String EVENT_STATE_CHANGED = "state_changed";

    private HaWebSocketProtocol() {}

    public static JSONObject buildAuth(String accessToken) {
        String token = Objects.requireNonNull(accessToken, "accessToken");
        if (token.trim().isEmpty()) throw new IllegalArgumentException("Access token is empty");
        return object("type", "auth", "access_token", token);
    }

    public static JSONObject buildStateChangedSubscription(int id) {
        return object("id", requireId(id), "type", "subscribe_events",
                "event_type", EVENT_STATE_CHANGED);
    }

    public static JSONObject buildGetStates(int id) {
        return object("id", requireId(id), "type", "get_states");
    }

    public static String type(JSONObject frame) {
        return frame == null ? "" : frame.optString("type", "");
    }

    public static boolean isSuccessfulResult(JSONObject frame, int id) {
        return frame != null && TYPE_RESULT.equals(type(frame)) && frame.optInt("id", -1) == id
                && frame.optBoolean("success", false);
    }

    public static JSONArray resultStates(JSONObject frame, int id) {
        if (!isSuccessfulResult(frame, id)) return null;
        return frame.optJSONArray("result");
    }

    public static String resultError(JSONObject frame) {
        if (frame == null) return "Empty Home Assistant response";
        JSONObject error = frame.optJSONObject("error");
        if (error == null) return "Home Assistant command failed";
        String code = error.optString("code", "");
        String message = error.optString("message", "Home Assistant command failed");
        return code.isEmpty() ? message : code + ": " + message;
    }

    /** Returns null for non-state events or malformed state_changed frames. */
    public static StateChange parseStateChange(JSONObject frame) {
        if (frame == null || !TYPE_EVENT.equals(type(frame))) return null;
        JSONObject event = frame.optJSONObject("event");
        if (event == null || !EVENT_STATE_CHANGED.equals(event.optString("event_type", ""))) {
            return null;
        }
        JSONObject data = event.optJSONObject("data");
        if (data == null) return null;
        String entityId = data.optString("entity_id", "").trim();
        if (entityId.isEmpty()) return null;
        HaEntity oldState = parseEntity(data.optJSONObject("old_state"), entityId);
        HaEntity newState = parseEntity(data.optJSONObject("new_state"), entityId);
        if ((oldState != null && !entityId.equals(oldState.entityId()))
                || (newState != null && !entityId.equals(newState.entityId()))) {
            return null;
        }
        return new StateChange(entityId, oldState, newState,
                event.optString("time_fired", ""));
    }

    private static HaEntity parseEntity(JSONObject state, String fallbackId) {
        if (state == null) return null;
        if (!state.optString("entity_id", "").trim().isEmpty()) return HaEntity.fromJson(state);
        try {
            JSONObject copy = new JSONObject(state.toString());
            copy.put("entity_id", fallbackId);
            return HaEntity.fromJson(copy);
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid Home Assistant state", error);
        }
    }

    private static int requireId(int id) {
        if (id <= 0) throw new IllegalArgumentException("Command id must be positive");
        return id;
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        try {
            for (int index = 0; index < pairs.length; index += 2) {
                result.put(String.valueOf(pairs[index]), pairs[index + 1]);
            }
            return result;
        } catch (JSONException error) {
            throw new IllegalArgumentException("Cannot encode Home Assistant frame", error);
        }
    }

    public static final class StateChange {
        private final String entityId;
        private final HaEntity oldState;
        private final HaEntity newState;
        private final String timeFired;

        public StateChange(String entityId, HaEntity oldState, HaEntity newState,
                           String timeFired) {
            this.entityId = Objects.requireNonNull(entityId, "entityId");
            this.oldState = oldState;
            this.newState = newState;
            this.timeFired = timeFired == null ? "" : timeFired;
        }

        public String entityId() { return entityId; }

        public HaEntity oldState() { return oldState; }

        /** Null means that Home Assistant removed the entity. */
        public HaEntity newState() { return newState; }

        public String timeFired() { return timeFired; }
    }
}
