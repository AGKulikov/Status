/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.climate;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Crash-safe journal for screen reservations.
 *
 * <p>This state intentionally lives outside the normal application preferences and in
 * device-protected storage. A reservation changes global WindowManager state, so its exact
 * pre-existing overscan must remain available before user unlock and after a process restart.
 *
 * <p>A display is considered managed as soon as both its baseline and intended target have been
 * committed. The controller writes that journal <em>before</em> issuing a mutating shell command.
 * A successful exact restore removes the entry; a failed or interrupted operation leaves it in
 * place so the next reconciliation can recover safely.
 */
public final class ScreenReservationStateStore {
    private static final String PREF_SUFFIX = "_screen_reservation_state_v1";
    private static final String BASELINE_PREFIX = "display.baseline.";
    private static final String TARGET_PREFIX = "display.target.";

    interface Backend {
        @NonNull Map<String, String> readAll();

        boolean commit(@NonNull Map<String, String> values,
                       @NonNull Set<String> removals);
    }

    private static final class SharedPreferencesBackend implements Backend {
        private final SharedPreferences preferences;

        SharedPreferencesBackend(@NonNull SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @NonNull
        @Override
        public Map<String, String> readAll() {
            Map<String, ?> raw = preferences.getAll();
            LinkedHashMap<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, ?> entry : raw.entrySet()) {
                if (entry.getValue() instanceof String) {
                    result.put(entry.getKey(), (String) entry.getValue());
                }
            }
            return result;
        }

        @Override
        public boolean commit(@NonNull Map<String, String> values,
                              @NonNull Set<String> removals) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String key : removals) editor.remove(key);
            for (Map.Entry<String, String> entry : values.entrySet()) {
                editor.putString(entry.getKey(), entry.getValue());
            }
            return editor.commit();
        }
    }

    /** Immutable journal entry for one display. */
    static final class Entry {
        final int displayId;
        @NonNull final ScreenReservationController.Insets baseline;
        @NonNull final ScreenReservationController.Insets target;

        Entry(int displayId,
              @NonNull ScreenReservationController.Insets baseline,
              @NonNull ScreenReservationController.Insets target) {
            this.displayId = displayId;
            this.baseline = baseline;
            this.target = target;
        }
    }

    private final Backend backend;

    /** Opens the dedicated device-protected journal for this application. */
    public ScreenReservationStateStore(@NonNull Context context) {
        Context app = context.getApplicationContext();
        Context device = app.createDeviceProtectedStorageContext();
        SharedPreferences preferences = device.getSharedPreferences(
                app.getPackageName() + PREF_SUFFIX, Context.MODE_PRIVATE);
        backend = new SharedPreferencesBackend(preferences);
    }

    /** Test-only constructor that does not touch Android framework storage. */
    ScreenReservationStateStore(@NonNull Backend backend) {
        this.backend = backend;
    }

    /**
     * Atomically records the immutable baseline (if this is the first reservation) and the next
     * intended target. Returning false means no global WindowManager mutation may be attempted.
     */
    synchronized boolean journalBeforeMutation(
            int displayId,
            @NonNull ScreenReservationController.Insets observedBaseline,
            @NonNull ScreenReservationController.Insets target) {
        Map<String, String> snapshot = backend.readAll();
        String baselineKey = baselineKey(displayId);
        ScreenReservationController.Insets storedBaseline = decode(snapshot.get(baselineKey));

        HashMap<String, String> values = new HashMap<>();
        if (storedBaseline == null) values.put(baselineKey, encode(observedBaseline));
        values.put(targetKey(displayId), encode(target));
        return backend.commit(values, Collections.emptySet());
    }

    @Nullable
    synchronized Entry get(int displayId) {
        Map<String, String> snapshot = backend.readAll();
        ScreenReservationController.Insets baseline = decode(snapshot.get(baselineKey(displayId)));
        ScreenReservationController.Insets target = decode(snapshot.get(targetKey(displayId)));
        if (baseline == null || target == null) return null;
        return new Entry(displayId, baseline, target);
    }

    /**
     * True when a mutation was journalled but has not yet been followed by a verified restore.
     * Boot code may use this independently of normal UI preferences: clearing settings must not
     * strand a global WindowManager modification on the device.
     */
    public synchronized boolean hasManagedReservation() {
        return !managedEntries().isEmpty();
    }

    /** Returns only complete entries; corrupt or half-written data is never used for mutation. */
    @NonNull
    synchronized List<Entry> managedEntries() {
        Map<String, String> snapshot = backend.readAll();
        ArrayList<Integer> displayIds = new ArrayList<>();
        for (String key : snapshot.keySet()) {
            if (!key.startsWith(TARGET_PREFIX)) continue;
            try {
                int id = Integer.parseInt(key.substring(TARGET_PREFIX.length()));
                if (id >= 0) displayIds.add(id);
            } catch (NumberFormatException ignored) {
                // A malformed key cannot safely identify a display. Leave it untouched.
            }
        }
        Collections.sort(displayIds);

        ArrayList<Entry> result = new ArrayList<>();
        for (Integer id : displayIds) {
            ScreenReservationController.Insets baseline = decode(snapshot.get(baselineKey(id)));
            ScreenReservationController.Insets target = decode(snapshot.get(targetKey(id)));
            if (baseline != null && target != null) result.add(new Entry(id, baseline, target));
        }
        return Collections.unmodifiableList(result);
    }

    /** Removes recovery state only after a verified exact restore (or explicit emergency reset). */
    synchronized boolean completeRestore(int displayId) {
        HashSet<String> removals = new HashSet<>();
        removals.add(baselineKey(displayId));
        removals.add(targetKey(displayId));
        return backend.commit(Collections.emptyMap(), removals);
    }

    @NonNull
    private static String baselineKey(int displayId) {
        return BASELINE_PREFIX + displayId;
    }

    @NonNull
    private static String targetKey(int displayId) {
        return TARGET_PREFIX + displayId;
    }

    @NonNull
    static String encode(@NonNull ScreenReservationController.Insets value) {
        return value.left + "," + value.top + "," + value.right + "," + value.bottom;
    }

    @Nullable
    static ScreenReservationController.Insets decode(@Nullable String raw) {
        if (raw == null) return null;
        String[] parts = raw.trim().split("\\s*,\\s*", -1);
        if (parts.length != 4) return null;
        try {
            return new ScreenReservationController.Insets(
                    Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
