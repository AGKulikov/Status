/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.integration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import dezz.status.widget.scenario.Input;
import dezz.status.widget.scenario.ValueReference;
import dezz.status.widget.scenario.ValueResolver;
import dezz.status.widget.scenario.ValueResolverRegistry;

/** Thread-safe in-process registry shared by HA, MQTT, Sprut.hub and the local scenario engine. */
public final class ConnectorValueRegistry implements ValueResolver {
    public static final int MAX_VALUES = 65_536;
    public static final int MAX_SNAPSHOT_VALUES = 32_768;
    public static final int MAX_LISTENERS = 64;
    private static final int MAX_CONNECTOR_ID_CHARS = 256;
    private static final int MAX_RESOURCE_ID_CHARS = 4_096;

    public interface Listener {
        /** Called after an upsert/removal/freshness barrier. The collection may be empty. */
        void onValuesChanged(@NonNull Collection<ConnectorValue> changedValues);
    }

    private final Map<Key, ConnectorValue> values = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(@NonNull Listener listener) {
        Listener checked = Objects.requireNonNull(listener, "listener");
        synchronized (listeners) {
            if (listeners.contains(checked)) return;
            if (listeners.size() >= MAX_LISTENERS) {
                throw new IllegalStateException("Too many connector value listeners");
            }
            listeners.add(checked);
        }
    }

    public void removeListener(@NonNull Listener listener) {
        listeners.remove(listener);
    }

    public void upsert(@NonNull ConnectorValue value) {
        Objects.requireNonNull(value, "value");
        synchronized (this) {
            Key key = Key.of(value.connectorType, value.connectorId, value.resourceId);
            if (!values.containsKey(key) && values.size() >= MAX_VALUES) {
                throw new IllegalStateException("Too many connector values");
            }
            values.put(key, value);
        }
        notifyListeners(Collections.singletonList(value));
    }

    /** Atomically replaces every known resource for one connector with an authoritative snapshot. */
    public void replaceSnapshot(@NonNull ConnectorType type, String connectorId,
                                @NonNull Collection<ConnectorValue> snapshot) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(snapshot, "snapshot");
        String normalizedId = normalizeConnectorId(connectorId);
        LinkedHashMap<Key, ConnectorValue> replacement = new LinkedHashMap<>();
        int visited = 0;
        for (ConnectorValue value : snapshot) {
            if (++visited > MAX_SNAPSHOT_VALUES) {
                throw new IllegalArgumentException("Connector snapshot is too large");
            }
            Objects.requireNonNull(value, "snapshot value");
            if (value.connectorType != type || !value.connectorId.equals(normalizedId)) {
                throw new IllegalArgumentException("Snapshot contains a foreign connector value");
            }
            Key key = Key.of(type, normalizedId, value.resourceId);
            if (replacement.put(key, value) != null) {
                throw new IllegalArgumentException("Snapshot contains a duplicate resource");
            }
        }

        ArrayList<ConnectorValue> changed = new ArrayList<>();
        synchronized (this) {
            LinkedHashMap<Key, ConnectorValue> next = new LinkedHashMap<>(values);
            for (Map.Entry<Key, ConnectorValue> entry : values.entrySet()) {
                Key key = entry.getKey();
                if (key.type == type && key.connectorId.equals(normalizedId)) {
                    next.remove(key);
                    if (!replacement.containsKey(key)) changed.add(entry.getValue().asStale());
                }
            }
            next.putAll(replacement);
            if (next.size() > MAX_VALUES) throw new IllegalStateException("Too many connector values");
            changed.addAll(replacement.values());
            values.clear();
            values.putAll(next);
        }
        notifyListeners(changed);
    }

    public boolean remove(@NonNull ConnectorType type, String connectorId, String resourceId) {
        ConnectorValue removed;
        synchronized (this) {
            removed = values.remove(Key.of(type, connectorId, resourceId));
        }
        if (removed != null) notifyListeners(Collections.singletonList(removed.asStale()));
        return removed != null;
    }

    /** Marks all cached values from one transport session stale without discarding their value. */
    public void markConnectorStale(@NonNull ConnectorType type, String connectorId) {
        String normalizedId = normalizeConnectorId(connectorId);
        ArrayList<ConnectorValue> changed = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Key, ConnectorValue> entry : values.entrySet()) {
                Key key = entry.getKey();
                if (key.type != type || !key.connectorId.equals(normalizedId)) continue;
                ConnectorValue stale = entry.getValue().asStale();
                entry.setValue(stale);
                changed.add(stale);
            }
        }
        notifyListeners(changed);
    }

    public void markAllStale() {
        ArrayList<ConnectorValue> changed = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<Key, ConnectorValue> entry : values.entrySet()) {
                ConnectorValue stale = entry.getValue().asStale();
                entry.setValue(stale);
                changed.add(stale);
            }
        }
        notifyListeners(changed);
    }

    @Nullable
    public synchronized ConnectorValue get(@NonNull ConnectorType type, String connectorId,
                                           String resourceId) {
        return values.get(Key.of(type, connectorId, resourceId));
    }

    @NonNull
    public synchronized List<ConnectorValue> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(values.values()));
    }

    /** Registers this shared resolver under a concrete connector profile. */
    public void registerResolver(@NonNull ValueResolverRegistry registry,
                                 @NonNull ConnectorType type, String connectorId) {
        registry.register(type.jsonName(), normalizeConnectorId(connectorId), this);
    }

    @Override
    @NonNull
    public Input resolve(@NonNull ValueReference reference) {
        final ConnectorType type;
        try {
            type = ConnectorType.fromJsonName(reference.connectorType, ConnectorType.MQTT);
        } catch (IllegalArgumentException ignored) {
            return Input.unavailable();
        }
        ConnectorValue value = get(type, reference.connectorId, reference.resourceId);
        return value == null ? Input.unavailable() : value.toInput(reference.valuePath);
    }

    /** Convenience resolver for a brick's display binding. */
    @NonNull
    public Input resolve(@Nullable SourceBinding binding) {
        if (binding == null || !binding.isBound()) return Input.unavailable();
        ConnectorValue value = get(binding.connectorType, binding.connectorId,
                binding.resourceId);
        return value == null ? Input.unavailable() : value.toInput(binding.valuePath);
    }

    private void notifyListeners(Collection<ConnectorValue> changed) {
        Collection<ConnectorValue> immutable = Collections.unmodifiableList(
                new ArrayList<>(changed));
        for (Listener listener : listeners) {
            try {
                listener.onValuesChanged(immutable);
            } catch (RuntimeException ignored) {
                // UI/scenario consumers are isolated from transport callback threads. A broken
                // listener must not prevent the remaining consumers from observing the update.
            }
        }
    }

    private static String normalizeConnectorId(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = SourceBinding.DEFAULT_CONNECTOR_ID;
        return bounded(value, "connectorId", MAX_CONNECTOR_ID_CHARS, true);
    }

    private static String bounded(String raw, String field, int maxLength, boolean required) {
        String value = raw == null ? "" : raw.trim();
        if ((required && value.isEmpty()) || value.length() > maxLength
                || value.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static final class Key {
        final ConnectorType type;
        final String connectorId;
        final String resourceId;

        private Key(ConnectorType type, String connectorId, String resourceId) {
            this.type = Objects.requireNonNull(type, "type");
            this.connectorId = normalizeConnectorId(connectorId);
            this.resourceId = bounded(resourceId, "resourceId", MAX_RESOURCE_ID_CHARS, true);
        }

        static Key of(ConnectorType type, String connectorId, String resourceId) {
            return new Key(type, connectorId, resourceId);
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return type == key.type && connectorId.equals(key.connectorId)
                    && resourceId.equals(key.resourceId);
        }

        @Override public int hashCode() {
            return Objects.hash(type, connectorId, resourceId);
        }
    }
}
