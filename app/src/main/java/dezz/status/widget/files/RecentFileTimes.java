/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RecentFileTimes {
    private static final String OPENED = "recentLocalOpens";

    private static final int MAX_OPENS = 200;

    private static Map<String, Long> opened(SharedPreferences preferences) {
        Map<String, Long> times = new LinkedHashMap<>();
        try {
            JSONArray rows = new JSONArray(preferences.getString(OPENED, "[]"));
            for (int i = 0; i < Math.min(rows.length(), MAX_OPENS); i++) {
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
            if (rows.length() == MAX_OPENS) break;
            rows.put(new JSONArray().put(entry.getKey()).put(entry.getValue()));
        }
        preferences.edit().putString(OPENED, rows.toString()).apply();
    }

    private RecentFileTimes() {}
}
