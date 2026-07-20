/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.ha.api;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persistent-style entity catalog: every accepted update produces a new immutable snapshot. */
public final class HaEntityCatalog {
    private final Map<String, HaEntity> entities;

    public HaEntityCatalog(Collection<HaEntity> source) {
        Map<String, HaEntity> index = new LinkedHashMap<>();
        if (source != null) {
            for (HaEntity entity : source) {
                if (entity != null) index.put(entity.entityId(), entity);
            }
        }
        entities = Collections.unmodifiableMap(index);
    }

    private HaEntityCatalog(Map<String, HaEntity> source) {
        entities = Collections.unmodifiableMap(source);
    }

    public static HaEntityCatalog empty() {
        return new HaEntityCatalog(Collections.emptyList());
    }

    public static HaEntityCatalog fromStates(JSONArray states) {
        Objects.requireNonNull(states, "states");
        List<HaEntity> entities = new ArrayList<>(states.length());
        for (int index = 0; index < states.length(); index++) {
            JSONObject state = states.optJSONObject(index);
            if (state != null) entities.add(HaEntity.fromJson(state));
        }
        return new HaEntityCatalog(entities);
    }

    public Map<String, HaEntity> entities() { return entities; }

    public Collection<HaEntity> values() { return entities.values(); }

    public HaEntity find(String entityId) { return entities.get(entityId); }

    public int size() { return entities.size(); }

    public boolean isEmpty() { return entities.isEmpty(); }

    /** Applies one state_changed payload and reports whether the snapshot actually changed. */
    public Update apply(HaWebSocketProtocol.StateChange change) {
        Objects.requireNonNull(change, "change");
        HaEntity previous = entities.get(change.entityId());
        HaEntity next = change.newState();
        if (next == null) {
            if (previous == null) return new Update(this, null);
            HaEntity removalVersion = change.oldState();
            if (removalVersion != null && !removalVersion.isAtLeastAsRecentAs(previous)) {
                return new Update(this, null);
            }
            Map<String, HaEntity> copy = new LinkedHashMap<>(entities);
            copy.remove(change.entityId());
            return new Update(new HaEntityCatalog(copy),
                    new EntityUpdate(change.entityId(), previous, null));
        }
        if (!next.isAtLeastAsRecentAs(previous)) return new Update(this, null);
        if (next.equals(previous)) return new Update(this, null);
        Map<String, HaEntity> copy = new LinkedHashMap<>(entities);
        copy.put(next.entityId(), next);
        return new Update(new HaEntityCatalog(copy),
                new EntityUpdate(next.entityId(), previous, next));
    }

    public static final class Update {
        private final HaEntityCatalog catalog;
        private final EntityUpdate entityUpdate;

        Update(HaEntityCatalog catalog, EntityUpdate entityUpdate) {
            this.catalog = catalog;
            this.entityUpdate = entityUpdate;
        }

        public HaEntityCatalog catalog() { return catalog; }

        /** Null means that an obsolete or duplicate event was ignored. */
        public EntityUpdate entityUpdate() { return entityUpdate; }

        public boolean changed() { return entityUpdate != null; }
    }

    public static final class EntityUpdate {
        private final String entityId;
        private final HaEntity previous;
        private final HaEntity current;

        EntityUpdate(String entityId, HaEntity previous, HaEntity current) {
            this.entityId = entityId;
            this.previous = previous;
            this.current = current;
        }

        public String entityId() { return entityId; }

        public HaEntity previous() { return previous; }

        /** Null means the entity was removed from Home Assistant. */
        public HaEntity current() { return current; }

        public boolean removed() { return current == null; }
    }
}
