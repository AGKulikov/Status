/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RecentFileTimes {
    private static final String OPENED = "recentLocalOpens";

    static Map<String, Long> opened(SharedPreferences preferences) {
        Map<String, Long> times = new LinkedHashMap<>();
        try {
            JSONArray rows = new JSONArray(preferences.getString(OPENED, "[]"));
            for (int i = 0; i < Math.min(rows.length(), RecentFiles.LIMIT); i++) {
                JSONArray row = rows.optJSONArray(i);
                if (row != null && !row.optString(0).isEmpty() && row.optLong(1) > 0)
                    times.put(row.optString(0), row.optLong(1));
            }
        } catch (JSONException invalidHistory) {
            // The filesystem remains authoritative if an older backup contains invalid history.
        }
        return times;
    }

    public static void recordOpen(SharedPreferences preferences, File file, long now) {
        String path = file.getAbsolutePath();
        Map<String, Long> previous = opened(preferences);
        previous.remove(path);
        JSONArray rows = new JSONArray().put(new JSONArray().put(path).put(now));
        for (Map.Entry<String, Long> entry : previous.entrySet()) {
            if (rows.length() == RecentFiles.LIMIT) break;
            rows.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
        }
        preferences.edit().putString(OPENED, rows.toString()).apply();
    }

    // Downloads can retain the source mtime. Where Android knows the import time, use it too.
    static Map<String, Long> collect(SharedPreferences preferences, ContentResolver resolver,
                                     CancellationSignal cancel) {
        Map<String, Long> times = opened(preferences);
        String pathColumn = MediaStore.MediaColumns.DATA;
        String addedColumn = MediaStore.MediaColumns.DATE_ADDED;
        try (Cursor c = resolver.query(MediaStore.Files.getContentUri("external"),
                new String[]{pathColumn, addedColumn}, null, null, addedColumn + " DESC", cancel)) {
            for (int i = 0; c != null && i < RecentFiles.LIMIT * 5 && c.moveToNext(); i++) {
                cancel.throwIfCanceled();
                String path = c.getString(0);
                if (path != null) times.merge(path, c.getLong(1) * 1000L, Math::max);
            }
        } catch (RuntimeException unavailableIndex) {
            cancel.throwIfCanceled(); // A missing media index on an ECARX build is optional.
        }
        return times;
    }
    private RecentFileTimes() {}
}
