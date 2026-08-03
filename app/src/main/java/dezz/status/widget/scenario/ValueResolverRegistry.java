/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.scenario;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Small in-process registry routing value references to connector-specific adapters. */
public final class ValueResolverRegistry implements ValueResolver {
    public static final int MAX_RESOLVERS = 64;

    private final Map<Key, ValueResolver> resolvers = new LinkedHashMap<>();

    public synchronized ValueResolverRegistry register(String connectorType, String connectorId,
                                                       ValueResolver resolver) {
        Key key = new Key(connectorType, connectorId);
        if (!resolvers.containsKey(key) && resolvers.size() >= MAX_RESOLVERS) {
            throw new IllegalStateException("Too many scenario value resolvers");
        }
        resolvers.put(key, Objects.requireNonNull(resolver, "resolver"));
        return this;
    }

    public synchronized boolean unregister(String connectorType, String connectorId) {
        return resolvers.remove(new Key(connectorType, connectorId)) != null;
    }

    public synchronized int size() {
        return resolvers.size();
    }

    /**
     * Resolution is fail-closed: a missing adapter, connector exception, or null snapshot is
     * represented as stale and unavailable, so ordinary value conditions cannot match it.
     */
    @Override
    public Input resolve(ValueReference reference) {
        Objects.requireNonNull(reference, "reference");
        ValueResolver resolver;
        synchronized (this) {
            resolver = resolvers.get(new Key(reference.connectorType, reference.connectorId));
        }
        if (resolver == null) return Input.unavailable();
        try {
            Input input = resolver.resolve(reference);
            return input == null ? Input.unavailable() : input;
        } catch (RuntimeException ignored) {
            return Input.unavailable();
        }
    }

    private static final class Key {
        private final String type;
        private final String id;

        private Key(String type, String id) {
            this.type = bounded(type, "connectorType", 64).toUpperCase(Locale.ROOT);
            this.id = bounded(id, "connectorId", 256);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key key = (Key) other;
            return type.equals(key.type) && id.equals(key.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, id);
        }

        private static String bounded(String raw, String field, int maxLength) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty() || value.length() > maxLength || value.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Invalid scenario " + field);
            }
            return value;
        }
    }
}
