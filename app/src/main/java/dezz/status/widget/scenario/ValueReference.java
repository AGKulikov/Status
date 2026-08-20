/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Provider-neutral address of one value exposed by a configured connector. */
public final class ValueReference {
    private static final int MAX_NAME_CHARS = 256;
    private static final int MAX_RESOURCE_CHARS = 512;
    private static final int MAX_PATH_CHARS = 512;
    private static final Pattern SAFE_CONNECTOR_TYPE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

    /** Stable provider kind, for example {@code HOME_ASSISTANT}, {@code MQTT},
     * {@code SPRUTHUB}, or read-only {@code PHONE}. */
    public final String connectorType;
    /** Optional user-facing profile name. It is metadata and is not used as a registry key. */
    public final String connectorName;
    /** Stable configured connection/profile id. */
    public final String connectorId;
    /** Provider resource id (entity id, MQTT topic, Sprut characteristic id, and so on). */
    public final String resourceId;
    /** Optional provider-defined sub-value path. It is passed literally to the resolver. */
    public final String valuePath;

    public ValueReference(String connectorType, String connectorId, String resourceId,
                          String valuePath) {
        this(connectorType, null, connectorId, resourceId, valuePath);
    }

    public ValueReference(String connectorType, String connectorName, String connectorId,
                          String resourceId, String valuePath) {
        String rawType = required(connectorType, "connectorType", 64);
        if (!SAFE_CONNECTOR_TYPE.matcher(rawType).matches()) {
            throw new IllegalArgumentException("Invalid scenario connectorType");
        }
        this.connectorType = rawType.toUpperCase(Locale.ROOT);
        this.connectorName = optional(connectorName, "connectorName", MAX_NAME_CHARS);
        this.connectorId = required(connectorId, "connectorId", MAX_NAME_CHARS);
        this.resourceId = required(resourceId, "resourceId", MAX_RESOURCE_CHARS);
        this.valuePath = optional(valuePath, "valuePath", MAX_PATH_CHARS);
    }

    public static ValueReference fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Scenario value reference is missing");
        return new ValueReference(object.optString("connectorType", ""),
                optionalString(object, "connectorName"), object.optString("connectorId", ""),
                object.optString("resourceId", ""), optionalString(object, "valuePath"));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("connectorType", connectorType);
        if (connectorName != null) object.put("connectorName", connectorName);
        object.put("connectorId", connectorId);
        object.put("resourceId", resourceId);
        if (valuePath != null) object.put("valuePath", valuePath);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ValueReference)) return false;
        ValueReference reference = (ValueReference) other;
        return connectorType.equals(reference.connectorType)
                && Objects.equals(connectorName, reference.connectorName)
                && connectorId.equals(reference.connectorId)
                && resourceId.equals(reference.resourceId)
                && Objects.equals(valuePath, reference.valuePath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectorType, connectorName, connectorId, resourceId, valuePath);
    }

    @Override
    public String toString() {
        return connectorType + ":" + connectorId + ":" + resourceId
                + (valuePath == null ? "" : ":" + valuePath);
    }

    private static String optionalString(JSONObject object, String key) {
        return !object.has(key) || object.isNull(key) ? null : object.optString(key, null);
    }

    private static String required(String raw, String field, int maxLength) {
        String value = optional(raw, field, maxLength);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing scenario " + field);
        }
        return value;
    }

    private static String optional(String raw, String field, int maxLength) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.length() > maxLength || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid scenario " + field);
        }
        return value.isEmpty() ? null : value;
    }
}
