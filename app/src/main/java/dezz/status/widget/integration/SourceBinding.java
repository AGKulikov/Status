/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Connector-neutral address of the value displayed by one brick.
 *
 * <p>{@code resourceId} is an HA entity id, an MQTT topic (or legacy automation id), or the
 * canonical Sprut.hub {@code aId/sId/cId} path. {@code valuePath} optionally selects a nested
 * attribute from the connector response.</p>
 */
public final class SourceBinding {
    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_CONNECTOR_ID = "default";

    public static final String PRESENTATION_AUTO = "AUTO";
    public static final String PRESENTATION_COVER = "COVER";
    public static final String PRESENTATION_BOOLEAN = "BOOLEAN";
    public static final String PRESENTATION_TEMPERATURE = "TEMPERATURE";
    public static final String PRESENTATION_RAW = "RAW";

    private static final int MAX_RESOURCE_ID_CHARS = 4_096;
    private static final int MAX_VALUE_PATH_CHARS = 1_024;
    private static final int MAX_UNIT_SUFFIX_CHARS = 64;
    private static final Pattern CONNECTOR_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    @NonNull public final ConnectorType connectorType;
    @NonNull public final String connectorId;
    @NonNull public final String resourceId;
    @NonNull public final String valuePath;
    @NonNull public final String presentation;
    /** Empty means that the connector/device-provided unit should be used as-is. */
    @NonNull public final String unitSuffix;

    public SourceBinding(@NonNull ConnectorType connectorType, String connectorId,
                         String resourceId, String valuePath, String presentation,
                         String unitSuffix) {
        this.connectorType = Objects.requireNonNull(connectorType, "connectorType");
        this.connectorId = normalizeConnectorId(connectorId);
        this.resourceId = bounded(resourceId, "resourceId", MAX_RESOURCE_ID_CHARS);
        this.valuePath = bounded(valuePath, "valuePath", MAX_VALUE_PATH_CHARS);
        this.presentation = normalizePresentation(presentation);
        this.unitSuffix = bounded(unitSuffix, "unitSuffix", MAX_UNIT_SUFFIX_CHARS);
    }

    /** Legacy MQTT/Broadcast state binding keyed by the old automation id. */
    @NonNull
    public static SourceBinding legacy(String automationId) {
        return new SourceBinding(ConnectorType.MQTT, DEFAULT_CONNECTOR_ID, automationId,
                "", PRESENTATION_AUTO, "");
    }

    /** Explicit empty binding for static elements. */
    @NonNull
    public static SourceBinding unbound() {
        return legacy("");
    }

    /** A binding is usable once it addresses a connector resource. */
    public boolean isBound() {
        return !resourceId.isEmpty();
    }

    @NonNull
    public static SourceBinding fromJson(@NonNull JSONObject object) {
        ConnectorType connector = ConnectorType.fromJsonName(
                object.optString("connectorType", object.optString("connector", "")),
                ConnectorType.MQTT);
        return new SourceBinding(connector,
                object.optString("connectorId", DEFAULT_CONNECTOR_ID),
                object.optString("resourceId", ""),
                object.optString("valuePath", ""),
                object.optString("presentation", PRESENTATION_AUTO),
                object.optString("unitSuffix", ""));
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("schema", SCHEMA_VERSION);
        object.put("connectorType", connectorType.jsonName());
        object.put("connectorId", connectorId);
        object.put("resourceId", resourceId);
        object.put("valuePath", valuePath);
        object.put("presentation", presentation);
        if (!unitSuffix.isEmpty()) object.put("unitSuffix", unitSuffix);
        return object;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SourceBinding)) return false;
        SourceBinding binding = (SourceBinding) other;
        return connectorType == binding.connectorType
                && connectorId.equals(binding.connectorId)
                && resourceId.equals(binding.resourceId)
                && valuePath.equals(binding.valuePath)
                && presentation.equals(binding.presentation)
                && unitSuffix.equals(binding.unitSuffix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectorType, connectorId, resourceId, valuePath, presentation,
                unitSuffix);
    }

    @Override
    public String toString() {
        return connectorType.jsonName() + ":" + connectorId + ":" + resourceId;
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
    private static String normalizePresentation(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) return PRESENTATION_AUTO;
        switch (value) {
            case PRESENTATION_AUTO:
            case PRESENTATION_COVER:
            case PRESENTATION_BOOLEAN:
            case PRESENTATION_TEMPERATURE:
            case PRESENTATION_RAW:
                return value;
            default:
                throw new IllegalArgumentException("Unknown source presentation: " + raw);
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
}
