/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.sprut;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * Immutable, low-memory discovery index used by the Sprut settings screen.
 *
 * <p>The index keeps one small row per accessory rather than duplicating every nested metadata
 * string. Searches walk immutable service/characteristic metadata on a worker thread and return
 * only one bounded page. {@link Query} is also used by the expanded UI, so an accessory that
 * matches discovery can always reveal the exact matching service and characteristic.</p>
 */
public final class SprutCatalogIndex {
    private final List<Entry> entries;

    private SprutCatalogIndex(List<Entry> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    @NonNull
    public static SprutCatalogIndex build(@NonNull SprutCatalog catalog) {
        List<Entry> values = new ArrayList<>(catalog.accessories().size());
        int position = 0;
        for (SprutCatalog.Accessory accessory : catalog.accessories()) {
            if ((position++ & 63) == 0) checkInterrupted();
            String room = catalog.roomNameFor(accessory);
            String sortKey = normalize(room + " " + accessory.name() + " " + accessory.id());
            values.add(new Entry(accessory, room, sortKey));
        }
        values.sort(Comparator.comparing(Entry::sortKey));
        checkInterrupted();
        return new SprutCatalogIndex(values);
    }

    public int size() { return entries.size(); }

    /** Returns one bounded page while still counting matches across the complete catalog. */
    @NonNull
    public Page search(String query, int requestedPage, int pageSize) {
        return search(Query.parse(query), requestedPage, pageSize);
    }

    /** Query overload lets the background result and expanded UI share exactly one predicate. */
    @NonNull
    public Page search(@NonNull Query query, int requestedPage, int pageSize) {
        if (pageSize <= 0) throw new IllegalArgumentException("pageSize must be positive");
        int requested = Math.max(0, requestedPage);
        long requestedFrom = (long) requested * pageSize;
        List<Entry> visible = new ArrayList<>(pageSize);
        int total = 0;
        int inspected = 0;
        for (Entry entry : entries) {
            if ((inspected++ & 63) == 0) checkInterrupted();
            if (!entry.matches(query)) continue;
            if (total >= requestedFrom && visible.size() < pageSize) visible.add(entry);
            total++;
        }

        int pageCount = total == 0 ? 0 : (total + pageSize - 1) / pageSize;
        int page = pageCount == 0 ? 0 : Math.min(requested, pageCount - 1);
        int from = Math.min(total, page * pageSize);
        int to = Math.min(total, from + pageSize);
        if (total > 0 && page != requested) visible = collectPage(query, from, pageSize);
        return new Page(Collections.unmodifiableList(visible), total, page, pageCount, from, to);
    }

    private List<Entry> collectPage(Query query, int from, int pageSize) {
        List<Entry> result = new ArrayList<>(pageSize);
        int match = 0;
        int inspected = 0;
        for (Entry entry : entries) {
            if ((inspected++ & 63) == 0) checkInterrupted();
            if (!entry.matches(query)) continue;
            if (match++ < from) continue;
            result.add(entry);
            if (result.size() >= pageSize) break;
        }
        return result;
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Entry {
        private final SprutCatalog.Accessory accessory;
        private final String roomName;
        private final String sortKey;

        private Entry(SprutCatalog.Accessory accessory, String roomName, String sortKey) {
            this.accessory = accessory;
            this.roomName = roomName;
            this.sortKey = sortKey;
        }

        private boolean matches(Query query) {
            if (query.matchesAccessoryHeader(accessory, roomName)) return true;
            for (SprutCatalog.Service service : accessory.services()) {
                if (query.matchesService(accessory, roomName, service)) return true;
            }
            return false;
        }

        public SprutCatalog.Accessory accessory() { return accessory; }

        public String roomName() { return roomName; }

        private String sortKey() { return sortKey; }
    }

    /** Token-AND matcher over an accessory → service → characteristic breadcrumb. */
    public static final class Query {
        private final String[] tokens;

        private Query(String[] tokens) { this.tokens = tokens; }

        @NonNull
        public static Query parse(String value) {
            String normalized = normalize(value);
            return new Query(normalized.isEmpty() ? new String[0] : normalized.split("\\s+"));
        }

        public boolean isEmpty() { return tokens.length == 0; }

        public boolean matchesAccessoryHeader(SprutCatalog.Accessory accessory, String room) {
            for (String token : tokens) {
                if (!accessoryHeaderContains(accessory, room, token)) return false;
            }
            return true;
        }

        public boolean matchesServiceHeader(SprutCatalog.Accessory accessory, String room,
                                            SprutCatalog.Service service) {
            for (String token : tokens) {
                if (!serviceHeaderContains(accessory, room, service, token)) return false;
            }
            return true;
        }

        /** True when the header or at least one characteristic breadcrumb matches all tokens. */
        public boolean matchesService(SprutCatalog.Accessory accessory, String room,
                                      SprutCatalog.Service service) {
            if (matchesServiceHeader(accessory, room, service)) return true;
            int position = 0;
            for (SprutCatalog.Characteristic characteristic : service.characteristics()) {
                if ((position++ & 255) == 0) checkInterrupted();
                if (matchesCharacteristic(accessory, room, service, characteristic)) return true;
            }
            return false;
        }

        public boolean matchesCharacteristic(SprutCatalog.Accessory accessory, String room,
                                             SprutCatalog.Service service,
                                             SprutCatalog.Characteristic characteristic) {
            for (String token : tokens) {
                if (!characteristicContains(accessory, room, service, characteristic, token)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean characteristicContains(SprutCatalog.Accessory accessory,
                                                      String room,
                                                      SprutCatalog.Service service,
                                                      SprutCatalog.Characteristic characteristic,
                                                      String token) {
            return serviceHeaderContains(accessory, room, service, token)
                    || contains(characteristic.name(), token)
                    || contains(characteristic.type(), token)
                    || contains(characteristic.unit(), token)
                    || contains(characteristic.path().stableId(), token);
        }

        private static boolean serviceHeaderContains(SprutCatalog.Accessory accessory,
                                                     String room,
                                                     SprutCatalog.Service service,
                                                     String token) {
            return accessoryHeaderContains(accessory, room, token)
                    || contains(service.name(), token)
                    || contains(service.type(), token)
                    || contains(Long.toString(service.id()), token);
        }

        private static boolean accessoryHeaderContains(SprutCatalog.Accessory accessory,
                                                       String room, String token) {
            return contains(room, token)
                    || contains(accessory.name(), token)
                    || contains(accessory.manufacturer(), token)
                    || contains(accessory.model(), token)
                    || contains(accessory.serial(), token)
                    || contains(Long.toString(accessory.id()), token);
        }

        /** Allocation-free case-insensitive substring check for already-normalized tokens. */
        private static boolean contains(String value, String token) {
            if (value == null || token.isEmpty() || value.length() < token.length()) return false;
            int limit = value.length() - token.length();
            for (int i = 0; i <= limit; i++) {
                if (value.regionMatches(true, i, token, 0, token.length())) return true;
            }
            return false;
        }
    }

    public static final class Page {
        private final List<Entry> entries;
        private final int totalMatches;
        private final int pageIndex;
        private final int pageCount;
        private final int fromIndex;
        private final int toIndex;

        private Page(List<Entry> entries, int totalMatches, int pageIndex, int pageCount,
                     int fromIndex, int toIndex) {
            this.entries = entries;
            this.totalMatches = totalMatches;
            this.pageIndex = pageIndex;
            this.pageCount = pageCount;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }

        public List<Entry> entries() { return entries; }

        public int totalMatches() { return totalMatches; }

        public int pageIndex() { return pageIndex; }

        public int pageCount() { return pageCount; }

        /** Zero-based inclusive offset in the complete filtered result. */
        public int fromIndex() { return fromIndex; }

        /** Zero-based exclusive offset in the complete filtered result. */
        public int toIndex() { return toIndex; }
    }
}
