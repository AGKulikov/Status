/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Installation-local playback history used by boot auto-resume.
 *
 * <p>This deliberately does not live in the exported launcher settings document: a package name
 * and its last playback state describe this head unit, not a layout that should be copied to a
 * different car. The media player remains responsible for retaining its own queue/track.</p>
 */
public final class MediaPlaybackHistoryStore {
    private static final String PREFS = "launcher_media_playback_history";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_WAS_PLAYING = "wasPlaying";
    private static final String KEY_UPDATED_AT = "updatedAt";

    public static final class Snapshot {
        @NonNull public final String packageName;
        public final boolean wasPlaying;
        public final long updatedAtMillis;

        Snapshot(@NonNull String packageName, boolean wasPlaying, long updatedAtMillis) {
            this.packageName = packageName;
            this.wasPlaying = wasPlaying;
            this.updatedAtMillis = updatedAtMillis;
        }

        public boolean canResume() {
            return wasPlaying && !packageName.isEmpty();
        }
    }

    private MediaPlaybackHistoryStore() {}

    public static void record(@NonNull Context context, @NonNull String packageName,
                              boolean playing) {
        String normalized = packageName.trim();
        if (normalized.isEmpty() || normalized.equals(context.getPackageName())) return;
        SharedPreferences preferences = preferences(context);
        if (normalized.equals(preferences.getString(KEY_PACKAGE, ""))
                && playing == preferences.getBoolean(KEY_WAS_PLAYING, false)) {
            // MediaSession callbacks and the HOME progress ticker may publish the same state once
            // per second. Playback history is edge-triggered; rewriting flash storage on every
            // progress update would add wear without improving boot recovery.
            return;
        }
        preferences.edit()
                .putString(KEY_PACKAGE, normalized)
                .putBoolean(KEY_WAS_PLAYING, playing)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    @NonNull
    public static Snapshot read(@NonNull Context context) {
        SharedPreferences prefs = preferences(context);
        return new Snapshot(
                prefs.getString(KEY_PACKAGE, ""),
                prefs.getBoolean(KEY_WAS_PLAYING, false),
                prefs.getLong(KEY_UPDATED_AT, 0L));
    }

    @NonNull
    private static SharedPreferences preferences(@NonNull Context context) {
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        Context storage = app.createDeviceProtectedStorageContext();
        return storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
