/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.media;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import dezz.status.widget.diagnostics.DiagnosticJournal;

/** Coalesced runtime writes. commit runs off MAIN/input and creates no apply() finisher. */
public final class RuntimePreferenceWriter {
    private static final Map<SharedPreferences, Map<String, Object>> pending = new IdentityHashMap<>();
    private static final Handler writer;
    static {
        HandlerThread thread = new HandlerThread("natro-runtime-preferences");
        thread.start(); writer = new Handler(thread.getLooper());
    }
    private RuntimePreferenceWriter() {}
    public static void put(SharedPreferences preferences, Object... pairs) {
        Map<String, Object> changes = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) changes.put((String) pairs[i], pairs[i + 1]);
        put(preferences, changes);
    }
    public static void put(SharedPreferences preferences, Map<String, ?> changes) {
        synchronized (pending) {
            Map<String, Object> queued = pending.get(preferences);
            if (queued == null) {
                queued = new HashMap<>(); pending.put(preferences, queued);
                writer.postDelayed(() -> flush(preferences, 0), 200);
            }
            queued.putAll(changes);
        }
    }
    private static void flush(SharedPreferences preferences, int attempt) {
        Map<String, Object> changes;
        synchronized (pending) { changes = pending.remove(preferences); }
        if (changes == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            Object v = e.getValue();
            if (v == null) editor.remove(e.getKey());
            else if (v instanceof Boolean) editor.putBoolean(e.getKey(), (Boolean) v);
            else if (v instanceof Integer) editor.putInt(e.getKey(), (Integer) v);
            else if (v instanceof Long) editor.putLong(e.getKey(), (Long) v);
            else editor.putString(e.getKey(), String.valueOf(v));
        }
        long start = android.os.SystemClock.uptimeMillis();
        boolean saved;
        try { saved = editor.commit(); }
        catch (RuntimeException failed) { saved = false; }
        long duration = android.os.SystemClock.uptimeMillis() - start;
        if (!saved || duration >= 250) DiagnosticJournal.infoAsync("runtime-preferences",
                "commit success=" + saved + ", duration_ms=" + duration + ", keys=" + changes.keySet());
        if (!saved && attempt < 2) {
            synchronized (pending) {
                Map<String, Object> newer = pending.get(preferences);
                if (newer != null) { changes.putAll(newer); pending.put(preferences, changes); }
                else {
                    pending.put(preferences, changes);
                    writer.postDelayed(() -> flush(preferences, attempt + 1), 500);
                }
            }
        }
    }
}
