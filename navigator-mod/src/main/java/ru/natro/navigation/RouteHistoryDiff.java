/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** History observations only: neither a selected item nor its removal proves navigation state. */
final class RouteHistoryDiff {
    static final int MAX_RECORDS = 1000;
    static final class Item {
        final String id, title;
        final double latitude, longitude;
        final long lastUsed;
        Item(String id, String title, double latitude, double longitude, long lastUsed) {
            if (id == null || id.isEmpty() || id.length() > 1024 || title == null
                    || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw new IllegalArgumentException("invalid history item");
            }
            this.id = id;
            this.title = title.length() > 256 ? title.substring(0, 256) : title;
            this.latitude = latitude;
            this.longitude = longitude;
            this.lastUsed = lastUsed;
        }
        boolean same(Item other) {
            return Objects.equals(title, other.title) && latitude == other.latitude
                    && longitude == other.longitude && lastUsed == other.lastUsed;
        }
    }
    static final class Change {
        final String kind;
        final Item item;
        Change(String kind, Item item) { this.kind = kind; this.item = item; }
    }
    private Map<String, Item> previous;
    void reset() { previous = null; }
    /** Call only after a complete valid snapshot. Failures must never advance the baseline. */
    List<Change> accept(List<Item> items) {
        if (items.size() > MAX_RECORDS) throw new IllegalArgumentException("history limit");
        Map<String, Item> next = new LinkedHashMap<>();
        for (Item item : items) {
            if (next.put(item.id, item) != null) throw new IllegalArgumentException("duplicate id");
        }
        List<Change> changes = new ArrayList<>();
        for (Item item : next.values()) {
            Item old = previous == null ? null : previous.get(item.id);
            if (old == null || !old.same(item)) {
                changes.add(new Change(previous == null ? "INITIAL" : old == null
                        ? "ADDED" : "UPDATED", item));
            }
        }
        if (previous != null) for (Item item : previous.values()) {
            if (!next.containsKey(item.id)) changes.add(new Change("REMOVED_FROM_HISTORY", item));
        }
        previous = next;
        return changes;
    }
}
