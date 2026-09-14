/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dezz.status.widget.diagnostics.DiagnosticJournal;

/**
 * Live telemetry cache, never user settings, enrollment, ownership records or a command journal.
 * Reads and apply() publish immediately in memory. One newest snapshot is checkpointed at most
 * every five seconds with commit() on a worker, so Android's process-wide QueuedWork cannot make
 * ActivityThread wait for these fsyncs at every service/broadcast lifecycle boundary.
 * A killed process can lose the last checkpoint interval; each caller retains its freshness rules.
 */
public final class RuntimeSnapshotPreferences implements SharedPreferences {
    private static final Map<String, RuntimeSnapshotPreferences> STORES = new HashMap<>();
    private static final ScheduledExecutorService DISK = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "runtime-snapshot-disk");
        thread.setDaemon(true);
        return thread;
    });
    private final SharedPreferences disk;
    private final Consumer<Runnable> schedule, callbacks;
    private final Consumer<String> report;
    private final Object lock = new Object(), diskLock = new Object();
    private final Map<OnSharedPreferenceChangeListener, Boolean> listeners = new WeakHashMap<>();
    private Map<String, Object> values;
    private long revision, savedRevision;
    private boolean scheduled;

    public static SharedPreferences open(Context context, String name) {
        // The legacy isolated HUD is a disk reader; preserve its explicit cross-process reload.
        if (AppProcessPolicy.isHudProcess()) {
            return context.getSharedPreferences(name, AppProcessPolicy.preferenceMode());
        }
        String key = context.getDataDir().getAbsolutePath() + "/" + name;
        synchronized (STORES) {
            RuntimeSnapshotPreferences store = STORES.get(key);
            if (store == null) {
                Handler main = new Handler(Looper.getMainLooper());
                store = new RuntimeSnapshotPreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE),
                        task -> DISK.schedule(task, 5L, TimeUnit.SECONDS),
                        task -> { if (Looper.myLooper() == main.getLooper()) task.run(); else main.post(task); },
                        detail -> DiagnosticJournal.warn("runtime-cache", "store=" + name + ", " + detail));
                STORES.put(key, store);
            }
            return store;
        }
    }

    RuntimeSnapshotPreferences(SharedPreferences disk, Consumer<Runnable> schedule,
                               Consumer<Runnable> callbacks, Consumer<String> report) {
        this.disk = disk;
        this.schedule = schedule;
        this.callbacks = callbacks;
        this.report = report;
        this.values = copy(disk.getAll());
    }

    private static Map<String, Object> copy(Map<String, ?> source) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : source.entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return result;
    }

    @Override public Map<String, ?> getAll() { synchronized (lock) { return copy(values); } }
    private Object get(String key, Object fallback) {
        synchronized (lock) { Object value = values.get(key); return value == null ? fallback : value; }
    }
    @Override public String getString(String key, String fallback) { return (String) get(key, fallback); }
    @SuppressWarnings("unchecked")
    @Override public Set<String> getStringSet(String key, Set<String> fallback) {
        synchronized (lock) {
            Set<String> value = (Set<String>) get(key, fallback);
            return value == null ? null : new HashSet<>(value);
        }
    }
    @Override public int getInt(String key, int fallback) { return (Integer) get(key, fallback); }
    @Override public long getLong(String key, long fallback) { return (Long) get(key, fallback); }
    @Override public float getFloat(String key, float fallback) { return (Float) get(key, fallback); }
    @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) get(key, fallback); }
    @Override public boolean contains(String key) { synchronized (lock) { return values.containsKey(key); } }
    @Override public Editor edit() { return new Update(); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) { listeners.put(listener, Boolean.TRUE); }
    }
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        synchronized (lock) { listeners.remove(listener); }
    }

    private void scheduleLocked() {
        if (scheduled || revision == savedRevision) return;
        scheduled = true;
        schedule.accept(() -> {
            try { persistLatest(); }
            finally { synchronized (lock) { scheduled = false; scheduleLocked(); } }
        });
    }

    private boolean persistLatest() {
        // Serializes disk operations, never held by live getters/apply(). New updates replace the
        // pending snapshot while a slow fsync runs; they cannot accumulate a queue of old records.
        synchronized (diskLock) {
            Map<String, Object> snapshot;
            long saving;
            synchronized (lock) {
                if (savedRevision == revision) return true;
                snapshot = copy(values);
                saving = revision;
            }
            long started = System.nanoTime();
            boolean success = false;
            try {
                Editor editor = disk.edit().clear();
                for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    if (value instanceof String) editor.putString(key, (String) value);
                    else if (value instanceof Long) editor.putLong(key, (Long) value);
                    else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                    else if (value instanceof Float) editor.putFloat(key, (Float) value);
                    else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                    else if (value instanceof Set) {
                        @SuppressWarnings("unchecked") Set<String> strings = (Set<String>) value;
                        editor.putStringSet(key, strings);
                    }
                }
                success = editor.commit();
            } catch (RuntimeException failure) {
                report.accept("checkpoint_error=" + failure.getClass().getSimpleName());
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (success) { synchronized (lock) { savedRevision = saving; } }
            if (!success || elapsedMs >= 250L) {
                report.accept("checkpoint_success=" + success + ", disk_ms=" + elapsedMs
                        + ", entries=" + snapshot.size() + ", live_reads_blocked=false");
            }
            return success;
        }
    }

    private final class Update implements Editor {
        private final Map<String, Object> changes = new HashMap<>();
        private boolean clear;
        @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
        @Override public Editor putStringSet(String key, Set<String> value) {
            changes.put(key, value == null ? null : new HashSet<>(value)); return this;
        }
        @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
        @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
        @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
        @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
        @Override public Editor remove(String key) { changes.put(key, null); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public void apply() { publish(); }
        @Override public boolean commit() { publish(); return persistLatest(); }

        private void publish() {
            Set<String> changed = new HashSet<>();
            Set<OnSharedPreferenceChangeListener> targets;
            synchronized (lock) {
                if (clear) { changed.addAll(values.keySet()); values.clear(); clear = false; }
                for (Map.Entry<String, Object> entry : changes.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    Object old = value == null ? values.remove(key) : values.put(key, value);
                    if (!java.util.Objects.equals(old, value)) changed.add(key);
                }
                changes.clear();
                if (changed.isEmpty()) return;
                revision++;
                scheduleLocked();
                targets = new HashSet<>(listeners.keySet());
            }
            if (!targets.isEmpty()) callbacks.accept(() -> {
                for (OnSharedPreferenceChangeListener listener : targets) {
                    if (listener != null) for (String key : changed) {
                        listener.onSharedPreferenceChanged(RuntimeSnapshotPreferences.this, key);
                    }
                }
            });
        }
    }
}
