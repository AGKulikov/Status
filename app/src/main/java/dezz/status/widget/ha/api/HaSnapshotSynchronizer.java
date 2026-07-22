/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Buffers state_changed events while the authoritative get_states snapshot is in flight. */
final class HaSnapshotSynchronizer {
    private final List<HaWebSocketProtocol.StateChange> buffered = new ArrayList<>();
    private boolean snapshotPending;

    void beginSnapshot() {
        buffered.clear();
        snapshotPending = true;
    }

    boolean buffer(HaWebSocketProtocol.StateChange change) {
        if (!snapshotPending) return false;
        buffered.add(change);
        return true;
    }

    Completion completeSnapshot(JSONArray states) {
        if (!snapshotPending) throw new IllegalStateException("No Home Assistant snapshot pending");
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
