/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.settings;

import java.util.*;

/** A key-scoped draft. Unrelated runtime writes never become part of Apply or Cancel. */
public final class SettingsDraft {
    private final Map<String, Object> before = new LinkedHashMap<>();
    private final Map<String, Object> changes = new LinkedHashMap<>();
    private boolean closed;
    public synchronized void put(String key, Object value, Map<String, ?> source) {
        if (closed) return;
        if (!before.containsKey(key)) before.put(key, copy(source.get(key)));
        Object next = copy(value);
        if (Objects.equals(before.get(key), next)) { changes.remove(key); before.remove(key); }
        else changes.put(key, next);
    }
    public synchronized boolean dirty() { return !changes.isEmpty(); }
    public synchronized boolean contains(String key) { return !closed && changes.containsKey(key); }
    public synchronized Object value(String key) { return copy(changes.get(key)); }
    public synchronized Map<String, Object> overlay(Map<String, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, copy(value)));
        if (!closed) changes.forEach((key, value) -> { if (value == null) result.remove(key); else result.put(key, copy(value)); });
        return result;
    }
    public synchronized Map<String, Object> changes() { return cloneMap(changes); }
    public synchronized Map<String, Object> original() { return cloneMap(before); }
    public synchronized Set<String> conflicts(Map<String, ?> actual) {
        Set<String> conflicts = new LinkedHashSet<>();
        before.forEach((key, value) -> {
            if (!Objects.equals(value, actual.get(key)) && !Objects.equals(changes.get(key), actual.get(key))) conflicts.add(key);
        });
        return conflicts;
    }
    public synchronized void applied() { changes.clear(); before.clear(); }
    public synchronized void close() { applied(); closed = true; }
    public synchronized boolean isClosed() { return closed; }
    private static Map<String, Object> cloneMap(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, value) -> result.put(key, copy(value))); return result;
    }
    static Object copy(Object value) {
        return value instanceof Set ? new HashSet<>((Set<?>) value) : value;
    }
}
