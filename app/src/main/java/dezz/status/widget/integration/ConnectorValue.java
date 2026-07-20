/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dezz.status.widget.scenario.Input;

/**
 * Immutable raw value published by a connector.
 *
 * <p>The transport owns freshness and availability. Presentation (localized text, colors,
 * icons and visibility) is intentionally absent and is produced later by the local rule engine.
 * This separation lets one connector value drive UI elements backed by another connector.</p>
 */
public final class ConnectorValue {
    private static final int MAX_ID_CHARS = 4_096;
    private static final int MAX_TYPE_CHARS = 256;

    @NonNull public final ConnectorType connectorType;
    @NonNull public final String connectorId;
    @NonNull public final String resourceId;
    @Nullable public final Object rawValue;
    public final boolean fresh;
    public final boolean available;
    public final boolean readable;
    public final boolean writable;
    @NonNull public final String valueType;
    @NonNull public final String unit;
    @NonNull public final Map<String, Object> attributes;
    public final long updatedAt;

    public ConnectorValue(@NonNull ConnectorType connectorType, String connectorId,
                          String resourceId, @Nullable Object rawValue, boolean fresh,
                          boolean available, boolean readable, boolean writable,
                          String valueType, String unit, Map<String, ?> attributes,
                          long updatedAt) {
        this.connectorType = Objects.requireNonNull(connectorType, "connectorType");
        this.connectorId = bounded(connectorId, "connectorId", 256, true);
        this.resourceId = bounded(resourceId, "resourceId", MAX_ID_CHARS, true);
        this.rawValue = freeze(rawValue);
        this.fresh = fresh;
        this.available = available;
        this.readable = readable;
        this.writable = writable;
        this.valueType = bounded(valueType, "valueType", MAX_TYPE_CHARS, false);
        this.unit = bounded(unit, "unit", MAX_TYPE_CHARS, false);
        this.attributes = freezeAttributes(attributes);
        this.updatedAt = Math.max(0L, updatedAt);
    }

    @NonNull
    public static ConnectorValue current(@NonNull ConnectorType connectorType,
                                         String connectorId, String resourceId,
                                         @Nullable Object rawValue, boolean available,
                                         boolean readable, boolean writable, String valueType,
                                         String unit, Map<String, ?> attributes) {
        return new ConnectorValue(connectorType, connectorId, resourceId, rawValue, true,
                available, readable, writable, valueType, unit, attributes,
                System.currentTimeMillis());
    }

    /** Keeps the last known value for diagnostics/display while crossing a session barrier. */
    @NonNull
    public ConnectorValue asStale() {
        if (!fresh) return this;
        return new ConnectorValue(connectorType, connectorId, resourceId, rawValue, false,
                available, readable, writable, valueType, unit, attributes, updatedAt);
    }

    /** Converts this transport value to the provider-neutral scenario input. */
    @NonNull
    public Input toInput(@Nullable String valuePath) {
        ResolvedValue resolved = resolvePath(valuePath);
        // A missing attribute/path is different from an explicitly present null. Treating both as
        // null made a typo satisfy EMPTY and could unexpectedly show a cross-connector target.
        if (!resolved.found) {
            return new Input(null, fresh, false, false, writable,
                    valueType, unit, attributes);
        }
        return new Input(resolved.value, fresh, available, readable, writable,
                valueType, unit, attributes);
    }

    /**
     * Selects a nested value without executing expressions. Empty, {@code value}, and
     * {@code state} address the connector's primary value. Other paths walk attributes/maps and
     * may use numeric list indices; a literal dotted attribute key wins over path splitting.
     */
    @Nullable
    public Object resolveValue(@Nullable String valuePath) {
        return resolvePath(valuePath).value;
    }

    @NonNull
    private ResolvedValue resolvePath(@Nullable String valuePath) {
        String path = valuePath == null ? "" : valuePath.trim();
        if (path.isEmpty() || "value".equals(path) || "state".equals(path)) {
            return ResolvedValue.found(rawValue);
        }
        if ("attributes".equals(path)) return ResolvedValue.found(attributes);
        if (path.startsWith("attributes.")) path = path.substring("attributes.".length());
        return walk(attributes, path);
    }

    @NonNull
    private static ResolvedValue walk(@Nullable Object root, String path) {
        if (path.isEmpty()) return ResolvedValue.found(root);
        if (root == null) return ResolvedValue.missing();
        String[] segments = path.split("\\.");
        Object current = root;
        for (int index = 0; index < segments.length; index++) {
            if (current instanceof Map<?, ?>) {
                Map<?, ?> map = (Map<?, ?>) current;
                String remainder = join(segments, index);
                if (map.containsKey(remainder)) return ResolvedValue.found(map.get(remainder));
                if (!map.containsKey(segments[index])) return ResolvedValue.missing();
                current = map.get(segments[index]);
            } else if (current instanceof List<?>) {
                int item;
                try {
                    item = Integer.parseInt(segments[index]);
                } catch (NumberFormatException ignored) {
                    return ResolvedValue.missing();
                }
                List<?> list = (List<?>) current;
                if (item < 0 || item >= list.size()) return ResolvedValue.missing();
                current = list.get(item);
            } else {
                return ResolvedValue.missing();
            }
        }
        return ResolvedValue.found(current);
    }

    private static String join(String[] segments, int start) {
        StringBuilder result = new StringBuilder();
        for (int index = start; index < segments.length; index++) {
            if (result.length() > 0) result.append('.');
            result.append(segments[index]);
        }
        return result.toString();
    }

    private static final class ResolvedValue {
        final boolean found;
        @Nullable final Object value;

        private ResolvedValue(boolean found, @Nullable Object value) {
            this.found = found;
            this.value = value;
        }

        static ResolvedValue found(@Nullable Object value) {
            return new ResolvedValue(true, value);
        }

        static ResolvedValue missing() {
            return new ResolvedValue(false, null);
        }
    }

    @NonNull
    private static Map<String, Object> freezeAttributes(Map<String, ?> source) {
        if (source == null || source.isEmpty()) return Collections.emptyMap();
        LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            if (copy.size() >= 128) break;
            String key = bounded(entry.getKey(), "attribute key", 256, true);
            copy.put(key, freeze(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @Nullable
    private static Object freeze(@Nullable Object value) {
        if (value instanceof CharSequence && ((CharSequence) value).length() > 8_192) {
            return value.toString().substring(0, 8_192);
        }
        if (value instanceof Map<?, ?>) {
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                result.put(String.valueOf(entry.getKey()), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?>) {
            ArrayList<Object> result = new ArrayList<>(((List<?>) value).size());
            for (Object item : (List<?>) value) result.add(freeze(item));
            return Collections.unmodifiableList(result);
        }
        return value;
    }

    @NonNull
    private static String bounded(@Nullable String raw, String field, int maxLength,
                                  boolean required) {
        String value = raw == null ? "" : raw.trim();
        if ((required && value.isEmpty()) || value.length() > maxLength
                || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }
}
