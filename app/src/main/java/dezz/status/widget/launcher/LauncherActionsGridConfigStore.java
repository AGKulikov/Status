/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

import dezz.status.widget.Preferences;

/** Additive, versioned persistence for {@link LauncherActionsGridConfig}. */
public final class LauncherActionsGridConfigStore {
    public static final int SCHEMA_VERSION = 1;
    private final Preferences preferences;

    public LauncherActionsGridConfigStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    @NonNull
    public LauncherActionsGridConfig load(
            @NonNull List<LauncherShortcutStore.Shortcut> shortcuts) {
        String raw = preferences.launcherActionsGridJson.get();
        DecodeResult decoded = decode(raw);
        LauncherActionsGridConfig value = decoded.value != null
                ? decoded.value
                : LauncherActionsGridConfig.migrateLegacy(shortcuts,
                preferences.launcherActionsColumns.get());
        boolean reconciled = value.reconcile(shortcuts);
        preferences.launcherActionsColumns.set(value.columns);
        // Missing configuration is a normal one-time migration. Corrupt or future data is kept
        // untouched so a bad read can never overwrite the user's only saved grid.
        if (decoded.missing || (decoded.valid && reconciled)) save(value);
        return value;
    }

    public void save(@NonNull LauncherActionsGridConfig source) {
        try {
            LauncherActionsGridConfig value = source.copy();
            value.normalize();
            preferences.launcherActionsGridJson.set(encode(value).toString());
            // Keep the old control/import field useful for rollback builds.
            preferences.launcherActionsColumns.set(value.columns);
        } catch (JSONException ignored) {
            // Normalized primitive fields and stable IDs are JSON-safe.
        }
    }

    public void reset(@NonNull List<LauncherShortcutStore.Shortcut> shortcuts) {
        LauncherActionsGridConfig value = LauncherActionsGridConfig.migrateLegacy(shortcuts,
                preferences.launcherActionsColumns.get());
        save(value);
    }

    static final class DecodeResult {
        @Nullable final LauncherActionsGridConfig value;
        final boolean missing;
        final boolean valid;

        DecodeResult(@Nullable LauncherActionsGridConfig value, boolean missing, boolean valid) {
            this.value = value;
            this.missing = missing;
            this.valid = valid;
        }
    }

    @NonNull
    static DecodeResult decode(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new DecodeResult(null, true, false);
        }
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) {
                return new DecodeResult(null, false, false);
            }
            if (!root.has("columns") || !root.has("rows") || !root.has("gapPx")
                    || !root.has("placements")) {
                return new DecodeResult(null, false, false);
            }
            LauncherActionsGridConfig value = new LauncherActionsGridConfig();
            value.columns = root.optInt("columns", value.columns);
            value.rows = root.optInt("rows", value.rows);
            value.gapPx = root.optInt("gapPx", value.gapPx);
            JSONArray placements = root.optJSONArray("placements");
            if (placements == null) return new DecodeResult(null, false, false);
            for (int index = 0; index < placements.length(); index++) {
                JSONObject encoded = placements.optJSONObject(index);
                if (encoded == null || !encoded.has("id")
                        || !encoded.has("column") || !encoded.has("row")
                        || !encoded.has("columnSpan") || !encoded.has("rowSpan")) {
                    return new DecodeResult(null, false, false);
                }
                String id = encoded.optString("id", "").trim();
                if (id.isEmpty() || value.placement(id) != null) {
                    return new DecodeResult(null, false, false);
                }
                value.put(new LauncherActionsGridConfig.Placement(id,
                        encoded.optInt("column", 0),
                        encoded.optInt("row", 0),
                        encoded.optInt("columnSpan", 1),
                        encoded.optInt("rowSpan", 1)));
            }
            value.normalize();
            return new DecodeResult(value, false, true);
        } catch (JSONException ignored) {
            return new DecodeResult(null, false, false);
        }
    }

    @NonNull
    static JSONObject encode(@NonNull LauncherActionsGridConfig source) throws JSONException {
        LauncherActionsGridConfig value = source.copy();
        value.normalize();
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("columns", value.columns);
        root.put("rows", value.rows);
        root.put("gapPx", value.gapPx);
        JSONArray placements = new JSONArray();
        for (LauncherActionsGridConfig.Placement placement : value.placements()) {
            JSONObject encoded = new JSONObject();
            encoded.put("id", placement.id);
            encoded.put("column", placement.column);
            encoded.put("row", placement.row);
            encoded.put("columnSpan", placement.columnSpan);
            encoded.put("rowSpan", placement.rowSpan);
            placements.put(encoded);
        }
        root.put("placements", placements);
        return root;
    }
}
