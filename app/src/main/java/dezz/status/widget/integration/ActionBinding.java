/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Connector-neutral command executed when a brick is pressed. */
public final class ActionBinding {
    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_CONNECTOR_ID = SourceBinding.DEFAULT_CONNECTOR_ID;

    public static final String OPERATION_PUBLISH = "PUBLISH";
    public static final String OPERATION_SET = "SET";
    public static final String OPERATION_TOGGLE = "TOGGLE";

    private static final int MAX_RESOURCE_ID_CHARS = 4_096;
    private static final int MAX_PAYLOAD_CHARS = 8_192;
    private static final Pattern CONNECTOR_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @NonNull public final ConnectorType connectorType;
    @NonNull public final String connectorId;
    /** HA entity id, MQTT topic/legacy action id, or Sprut.hub {@code aId/sId/cId}. */
    @NonNull public final String resourceId;
    @NonNull public final String operation;
    /** JSON object/array or a connector-specific primitive encoded as text. */
    @NonNull public final String payload;

    public ActionBinding(@NonNull ConnectorType connectorType, String connectorId,
                         String resourceId, String operation, String payload) {
        this.connectorType = Objects.requireNonNull(connectorType, "connectorType");
        this.connectorId = normalizeConnectorId(connectorId);
        this.resourceId = bounded(resourceId, "resourceId", MAX_RESOURCE_ID_CHARS);
        this.operation = normalizeOperation(operation);
        this.payload = boundedPayload(payload);
    }

    /** Legacy Status Widget command binding keyed by action id and published through MQTT. */
    @NonNull
    public static ActionBinding legacy(String actionId, String actionPayload) {
        String payload = actionPayload == null || actionPayload.trim().isEmpty()
                ? "{}" : actionPayload;
        return new ActionBinding(ConnectorType.MQTT, DEFAULT_CONNECTOR_ID, actionId,
                OPERATION_PUBLISH, payload);
    }

    @NonNull
    public static ActionBinding unbound() {
        return legacy("", "{}");
    }

    /** A binding is usable once it addresses a connector resource. */
    public boolean isBound() {
        return !resourceId.isEmpty();
    }

    @NonNull
    public static ActionBinding fromJson(@NonNull JSONObject object) {
        ConnectorType connector = ConnectorType.fromJsonName(
                object.optString("connectorType", object.optString("connector", "")),
                ConnectorType.MQTT);
        return new ActionBinding(connector,
                object.optString("connectorId", DEFAULT_CONNECTOR_ID),
                object.optString("resourceId", ""),
                object.optString("operation", OPERATION_PUBLISH),
                object.optString("payload", "{}"));
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schema", SCHEMA_VERSION);
        object.put("connectorType", connectorType.jsonName());
        object.put("connectorId", connectorId);
        object.put("resourceId", resourceId);
        object.put("operation", operation);
        object.put("payload", payload);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ActionBinding)) return false;
        ActionBinding binding = (ActionBinding) other;
        return connectorType == binding.connectorType
                && connectorId.equals(binding.connectorId)
                && resourceId.equals(binding.resourceId)
                && operation.equals(binding.operation)
                && payload.equals(binding.payload);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectorType, connectorId, resourceId, operation, payload);
    }

    @Override
    public String toString() {
        return connectorType.jsonName() + ":" + connectorId + ":" + resourceId + ":"
                + operation;
    }

    @NonNull
    private static String normalizeConnectorId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = DEFAULT_CONNECTOR_ID;
        if (!CONNECTOR_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid connector id: " + raw);
        }
        return value;
    }

    @NonNull
    private static String normalizeOperation(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return OPERATION_PUBLISH;
        switch (value) {
            case OPERATION_PUBLISH:
            case OPERATION_SET:
            case OPERATION_TOGGLE:
                return value;
            default:
                throw new IllegalArgumentException("Unknown action operation: " + raw);
        }
    }

    @NonNull
    private static String bounded(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxLength || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    @NonNull
    private static String boundedPayload(String raw) {
        String value = raw == null ? "" : raw;
        if (value.length() > MAX_PAYLOAD_CHARS || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid payload");
        }
        return value;
    }
}
