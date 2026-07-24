/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Buffers state_changed events while the authoritative get_states snapshot is in flight. */
final class HaSnapshotSynchronizer {
    /** Protects a slow/hostile get_states response from retaining an unbounded event backlog. */
    static final int MAX_BUFFERED_CHANGES = 4_096;
    private final List<HaWebSocketProtocol.StateChange> buffered = new ArrayList<>();
    private boolean snapshotPending;
    private boolean overflowed;

    void beginSnapshot() {
        buffered.clear();
        overflowed = false;
        snapshotPending = true;
    }

    boolean buffer(HaWebSocketProtocol.StateChange change) {
        if (!snapshotPending) return false;
        if (buffered.size() >= MAX_BUFFERED_CHANGES) {
            overflowed = true;
        } else {
            buffered.add(change);
        }
        return true;
    }

    Completion completeSnapshot(JSONArray states) {
        if (!snapshotPending) throw new IllegalStateException("No Home Assistant snapshot pending");
        if (overflowed) {
            reset();
            // Reconnecting is safer than publishing a snapshot after silently dropping a newer
            // state_changed event. The hard cap also bounds replay cost on weak head units.
            throw new IllegalStateException("Too many Home Assistant updates during snapshot");
        }
        HaEntityCatalog catalog = HaEntityCatalog.fromStates(states);
        List<HaEntityCatalog.EntityUpdate> replayed = new ArrayList<>();
        for (HaWebSocketProtocol.StateChange change : buffered) {
            HaEntityCatalog.Update update = catalog.apply(change);
            catalog = update.catalog();
            if (update.changed()) replayed.add(update.entityUpdate());
        }
        buffered.clear();
        snapshotPending = false;
        return new Completion(catalog, replayed);
    }

    boolean snapshotPending() { return snapshotPending; }

    int bufferedCount() { return buffered.size(); }

    void reset() {
        buffered.clear();
        overflowed = false;
        snapshotPending = false;
    }

    static final class Completion {
        private final HaEntityCatalog catalog;
        private final List<HaEntityCatalog.EntityUpdate> replayed;

        Completion(HaEntityCatalog catalog, List<HaEntityCatalog.EntityUpdate> replayed) {
            this.catalog = catalog;
            this.replayed = Collections.unmodifiableList(new ArrayList<>(replayed));
        }

        HaEntityCatalog catalog() { return catalog; }

        List<HaEntityCatalog.EntityUpdate> replayedUpdates() { return replayed; }
    }
}
