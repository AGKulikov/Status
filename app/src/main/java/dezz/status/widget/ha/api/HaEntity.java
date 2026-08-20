/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable Home Assistant state object detached from the mutable JSON frame that produced it. */
public final class HaEntity {
    private final String entityId;
    private final String state;
    private final Map<String, Object> attributes;
    private final String lastUpdated;

    public HaEntity(String entityId, String state, Map<String, Object> attributes,
                    String lastUpdated) {
        this.entityId = requireEntityId(entityId);
        this.state = state == null ? "" : state;
        this.attributes = freezeMap(attributes);
        this.lastUpdated = lastUpdated == null ? "" : lastUpdated;
    }

    public static HaEntity fromJson(JSONObject json) {
        Objects.requireNonNull(json, "json");
        JSONObject rawAttributes = json.optJSONObject("attributes");
        return new HaEntity(
                json.optString("entity_id", ""),
                json.optString("state", ""),
                rawAttributes == null ? Collections.emptyMap() : jsonObjectToMap(rawAttributes),
                json.optString("last_updated", json.optString("last_changed", "")));
    }

    public String entityId() { return entityId; }

    public String state() { return state; }

    public Map<String, Object> attributes() { return attributes; }

    public Object attribute(String name) { return attributes.get(name); }

    public String lastUpdated() { return lastUpdated; }

    public String domain() {
        int separator = entityId.indexOf('.');
        return separator < 0 ? "" : entityId.substring(0, separator);
    }

    /** Returns true when this state may safely replace {@code current}. */
    boolean isAtLeastAsRecentAs(HaEntity current) {
        if (current == null || lastUpdated.isEmpty() || current.lastUpdated.isEmpty()) return true;
        Instant candidate = parseInstant(lastUpdated);
        Instant existing = parseInstant(current.lastUpdated);
        if (candidate == null || existing == null) {
            // Home Assistant normally emits canonical ISO-8601 timestamps; lexical comparison is
            // a deterministic fallback for synthetic/custom producers.
            return lastUpdated.compareTo(current.lastUpdated) >= 0;
        }
        return !candidate.isBefore(existing);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof HaEntity)) return false;
        HaEntity entity = (HaEntity) other;
        return entityId.equals(entity.entityId) && state.equals(entity.state)
                && attributes.equals(entity.attributes) && lastUpdated.equals(entity.lastUpdated);
    }

    @Override public int hashCode() {
        return Objects.hash(entityId, state, attributes, lastUpdated);
    }

    @Override public String toString() {
        return "HaEntity{" + entityId + '=' + state + ", lastUpdated=" + lastUpdated + '}';
    }

    private static String requireEntityId(String raw) {
        String value = raw == null ? "" : raw.trim();
        int separator = value.indexOf('.');
        if (separator <= 0 || separator == value.length() - 1 || value.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("Invalid Home Assistant entity_id: " + raw);
        }
        return value;
    }

    private static Map<String, Object> jsonObjectToMap(JSONObject source) {
        Map<String, Object> result = new LinkedHashMap<>();
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            result.put(key, freezeJsonValue(source.opt(key)));
        }
        return result;
    }

    private static Object freezeJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return freezeMap(jsonObjectToMap((JSONObject) value));
        if (value instanceof JSONArray) {
            JSONArray source = (JSONArray) value;
            List<Object> result = new ArrayList<>(source.length());
            for (int index = 0; index < source.length(); index++) {
                result.add(freezeJsonValue(source.opt(index)));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private static Map<String, Object> freezeMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), freezeJavaValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    private static Object freezeJavaValue(Object value) {
        if (value == null) return null;
        if (value instanceof Map<?, ?>) {
            Map<String, Object> nested = new LinkedHashMap<>();
            for (Map.Entry<?, ?> item : ((Map<?, ?>) value).entrySet()) {
                nested.put(String.valueOf(item.getKey()), freezeJavaValue(item.getValue()));
            }
            return Collections.unmodifiableMap(nested);
        }
        if (value instanceof List<?>) {
            List<Object> nested = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) nested.add(freezeJavaValue(item));
            return Collections.unmodifiableList(nested);
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    private static Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }
}
