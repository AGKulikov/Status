/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable provider-neutral state evaluated by a {@link RuleSet}. */
public final class Input {
    public static final String FIELD_VALUE = "value";
    public static final String FIELD_FRESH = "fresh";
    public static final String FIELD_AVAILABLE = "available";
    public static final String FIELD_READABLE = "readable";
    public static final String FIELD_WRITABLE = "writable";
    public static final String FIELD_TYPE = "type";
    public static final String FIELD_UNIT = "unit";
    public static final String FIELD_ATTRIBUTES = "attributes";
    public static final String ATTRIBUTE_PREFIX = "attributes.";

    private static final int MAX_TYPE_OR_UNIT_CHARS = 256;
    private static final int MAX_ATTRIBUTES = 128;
    private static final int MAX_ATTRIBUTE_KEY_CHARS = 256;
    private static final int MAX_ATTRIBUTE_STRING_CHARS = 8_192;

    public final Object rawValue;
    public final boolean fresh;
    public final boolean available;
    public final boolean readable;
    public final boolean writable;
    public final String type;
    public final String unit;
    public final Map<String, Object> attributes;

    public Input(Object rawValue, boolean fresh, boolean available, boolean readable,
                 boolean writable, String type, String unit, Map<String, ?> attributes) {
        this.rawValue = validateRuntimeValue(rawValue, "rawValue");
        this.fresh = fresh;
        this.available = available;
        this.readable = readable;
        this.writable = writable;
        this.type = bounded(type, "type", MAX_TYPE_OR_UNIT_CHARS);
        this.unit = bounded(unit, "unit", MAX_TYPE_OR_UNIT_CHARS);

        Map<String, ?> source = attributes == null ? Collections.emptyMap() : attributes;
        if (source.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("Too many input attributes");
        }
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            String key = bounded(entry.getKey(), "attribute key", MAX_ATTRIBUTE_KEY_CHARS);
            if (key.isEmpty()) throw new IllegalArgumentException("Empty attribute key");
            copy.put(key, validateRuntimeValue(entry.getValue(), "attribute value"));
        }
        this.attributes = Collections.unmodifiableMap(copy);
    }

    /** Convenience input with no attributes or declared type/unit. */
    public static Input value(Object rawValue, boolean fresh, boolean available) {
        return new Input(rawValue, fresh, available, true, false, "", "",
                Collections.emptyMap());
    }

    /** Safe result used when a referenced connector/profile/resource cannot be resolved. */
    public static Input unavailable() {
        return new Input(null, false, false, false, false, "", "",
                Collections.emptyMap());
    }

    /**
     * Resolves a rule field. Attribute names are addressed literally as
     * {@code attributes.<name>}; no expressions or nested executable paths are evaluated.
     */
    public Object resolve(String field) {
        String key = field == null ? "" : field.trim();
        if (key.isEmpty() || FIELD_VALUE.equals(key)) return rawValue;
        switch (key) {
            case FIELD_FRESH: return fresh;
            case FIELD_AVAILABLE: return available;
            case FIELD_READABLE: return readable;
            case FIELD_WRITABLE: return writable;
            case FIELD_TYPE: return type;
            case FIELD_UNIT: return unit;
            case FIELD_ATTRIBUTES: return attributes;
            default:
                if (key.startsWith(ATTRIBUTE_PREFIX)) {
                    return attributes.get(key.substring(ATTRIBUTE_PREFIX.length()));
                }
                return null;
        }
    }

    private static Object validateRuntimeValue(Object value, String field) {
        if (value instanceof CharSequence
                && ((CharSequence) value).length() > MAX_ATTRIBUTE_STRING_CHARS) {
            throw new IllegalArgumentException(field + " is too large");
        }
        return value;
    }

    private static String bounded(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maxLength || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }
}
