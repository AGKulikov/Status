/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Objects;

import dezz.status.widget.automation.AutomationContract;

/** Declarative, local-only presentation change emitted by a matching scenario. */
public final class LocalAction {
    private static final int MAX_STYLE_VALUE_CHARS = 128;

    public final TargetScope targetScope;
    public final String targetId;
    public final LocalField field;
    /** Boolean for boolean fields, String for style/icon fields. */
    public final Object value;

    public LocalAction(TargetScope targetScope, String targetId, LocalField field, Object value) {
        this.targetScope = Objects.requireNonNull(targetScope, "targetScope");
        // Scenario targets address the same AutomationStateStore keys as Broadcast/MQTT updates.
        // Reuse the canonical validator so a scenario accepted at import time cannot fail only
        // later, while the connector callback is being evaluated.
        this.targetId = AutomationContract.requireSafeId(targetId);
        this.field = Objects.requireNonNull(field, "field");
        this.value = normalizedValue(field, value);
    }

    public static LocalAction fromJson(JSONObject object) {
        if (object == null) throw new IllegalArgumentException("Scenario local action is missing");
        return new LocalAction(TargetScope.fromJsonName(object.optString("targetScope", "")),
                object.optString("targetId", ""),
                LocalField.fromJsonName(object.optString("field", "")), object.opt("value"));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("targetScope", targetScope.jsonName());
        object.put("targetId", targetId);
        object.put("field", field.jsonName());
        object.put("value", value);
        return object;
    }

    public Boolean booleanValue() {
        return value instanceof Boolean ? (Boolean) value : null;
    }

    public String stringValue() {
        return value instanceof String ? (String) value : null;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LocalAction)) return false;
        LocalAction action = (LocalAction) other;
        return targetScope == action.targetScope && targetId.equals(action.targetId)
                && field == action.field && value.equals(action.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(targetScope, targetId, field, value);
    }

    private static Object normalizedValue(LocalField field, Object raw) {
        if (field == LocalField.VISIBLE || field == LocalField.ACTION_ENABLED) {
            if (raw instanceof Boolean) return raw;
            if (raw instanceof CharSequence) {
                String value = raw.toString().trim();
                if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
                if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Scenario boolean action requires true or false");
        }
        if (!(raw instanceof CharSequence)) {
            throw new IllegalArgumentException("Scenario style action requires a string");
        }
        String value = raw.toString().trim();
        if (value.isEmpty() || value.length() > MAX_STYLE_VALUE_CHARS
                || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid scenario local action value");
        }
        return value;
    }
}
