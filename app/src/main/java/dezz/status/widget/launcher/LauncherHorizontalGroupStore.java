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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.Preferences;

/** Persistent launcher groups whose member widgets are arranged horizontally. */
public final class LauncherHorizontalGroupStore {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_JSON_CHARS = 1_048_576;
    private static final int MAX_GROUPS = 128;
    private static final int MAX_MEMBERS = 128;

    public static final class Group {
        @NonNull public String id = "";
        @NonNull public String name = "Горизонтальный ряд";
        public int x;
        public int y;
        public int width = 620;
        public int height = 150;
        public int gapPx;
        public int paddingLeftPx;
        public int paddingTopPx;
        public int paddingRightPx;
        public int paddingBottomPx;
        /** 0=start/top, 1=center, 2=end/bottom. */
        public int horizontalAlignment;
        public int verticalAlignment = 1;
        public int distribution = HorizontalGroupLayout.DISTRIBUTION_COMPACT;
        @NonNull public final List<String> memberIds = new ArrayList<>();

        @NonNull
        public Group copy() {
            Group value = new Group();
            value.id = id;
            value.name = name;
            value.x = x;
            value.y = y;
            value.width = width;
            value.height = height;
            value.gapPx = gapPx;
            value.paddingLeftPx = paddingLeftPx;
            value.paddingTopPx = paddingTopPx;
            value.paddingRightPx = paddingRightPx;
            value.paddingBottomPx = paddingBottomPx;
            value.horizontalAlignment = horizontalAlignment;
            value.verticalAlignment = verticalAlignment;
            value.distribution = distribution;
            value.memberIds.addAll(memberIds);
            return value;
        }
    }

    private final Preferences preferences;
    private final Map<String, Group> groups = new LinkedHashMap<>();
    private int screenWidth = 1;
    private int screenHeight = 1;

    public LauncherHorizontalGroupStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
    }

    public void load(int width, int height) {
        screenWidth = Math.max(1, width);
        screenHeight = Math.max(1, height);
        groups.clear();
        String raw = preferences.launcherHorizontalGroupsJson.get();
        if (raw == null || raw.trim().isEmpty() || raw.length() > MAX_JSON_CHARS) return;
        try {
            JSONObject root = new JSONObject(raw);
            if (root.optInt("version", 0) != SCHEMA_VERSION) return;
            JSONArray items = root.optJSONArray("items");
            if (items == null) return;
            Set<String> claimedMembers = new LinkedHashSet<>();
            for (int index = 0; index < items.length() && groups.size() < MAX_GROUPS; index++) {
                JSONObject item = items.optJSONObject(index);
                if (item == null) continue;
                Group parsed = normalize(decode(item));
                parsed.memberIds.removeIf(claimedMembers::contains);
                if (parsed.id.isEmpty() || parsed.memberIds.size() < 2
                        || groups.containsKey(parsed.id)) continue;
                groups.put(parsed.id, parsed);
                claimedMembers.addAll(parsed.memberIds);
            }
        } catch (JSONException ignored) {
            groups.clear();
        }
    }

    @NonNull
    public List<Group> all() {
        ArrayList<Group> result = new ArrayList<>(groups.size());
        for (Group value : groups.values()) result.add(value.copy());
        return result;
    }

    @Nullable
    public Group get(@NonNull String id) {
        Group value = groups.get(id);
        return value == null ? null : value.copy();
    }

    public boolean containsMember(@NonNull String memberId) {
        for (Group value : groups.values()) {
            if (value.memberIds.contains(memberId)) return true;
        }
        return false;
    }

    @NonNull
    public Group create(@NonNull List<String> memberIds,
                        int x, int y, int width, int height) {
        if (memberIds.size() < 2) {
            throw new IllegalArgumentException("A horizontal group needs at least two widgets");
        }
        int ordinal = 1;
        String id;
        do {
            id = "launcher_horizontal_group_" + ordinal++;
        } while (groups.containsKey(id));
        Group value = new Group();
        value.id = id;
        value.name = "Горизонтальный ряд " + (ordinal - 1);
        value.x = x;
        value.y = y;
        value.width = width;
        value.height = height;
        value.memberIds.addAll(memberIds);
        put(value);
        Group created = get(id);
        if (created == null) {
            throw new IllegalStateException("Horizontal group was not persisted");
        }
        return created;
    }

    public void put(@NonNull Group source) {
        Group value = normalize(source);
        if (value.id.isEmpty() || value.memberIds.size() < 2) return;
        // A widget belongs to one row only. Moving it to another row is deterministic.
        for (Group existing : groups.values()) {
            if (existing.id.equals(value.id)) continue;
            existing.memberIds.removeAll(value.memberIds);
        }
        groups.entrySet().removeIf(entry -> !entry.getKey().equals(value.id)
                && entry.getValue().memberIds.size() < 2);
        groups.put(value.id, value);
        save();
    }

    public void remove(@NonNull String id) {
        if (groups.remove(id) != null) save();
    }

    @NonNull
    private Group normalize(@NonNull Group source) {
        Group value = source.copy();
        value.id = clean(value.id, 120);
        value.name = clean(value.name, 120);
        if (value.name.isEmpty()) value.name = "Горизонтальный ряд";
        value.width = clamp(value.width, 1, screenWidth);
        value.height = clamp(value.height, 1, screenHeight);
        value.x = clamp(value.x, 0, Math.max(0, screenWidth - value.width));
        value.y = clamp(value.y, 0, Math.max(0, screenHeight - value.height));
        value.gapPx = clamp(value.gapPx, 0, 500);
        value.paddingLeftPx = clamp(value.paddingLeftPx, 0, 500);
        value.paddingTopPx = clamp(value.paddingTopPx, 0, 500);
        value.paddingRightPx = clamp(value.paddingRightPx, 0, 500);
        value.paddingBottomPx = clamp(value.paddingBottomPx, 0, 500);
        value.horizontalAlignment = clamp(value.horizontalAlignment, 0, 2);
        value.verticalAlignment = clamp(value.verticalAlignment, 0, 2);
        value.distribution = value.distribution == HorizontalGroupLayout.DISTRIBUTION_EQUAL
                ? HorizontalGroupLayout.DISTRIBUTION_EQUAL
                : HorizontalGroupLayout.DISTRIBUTION_COMPACT;
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String raw : value.memberIds) {
            String member = clean(raw, 240);
            if (!member.isEmpty() && unique.size() < MAX_MEMBERS) unique.add(member);
        }
        value.memberIds.clear();
        value.memberIds.addAll(unique);
        return value;
    }

    private void save() {
        try {
            JSONObject root = new JSONObject().put("version", SCHEMA_VERSION);
            JSONArray items = new JSONArray();
            for (Group value : groups.values()) {
                if (value.memberIds.size() >= 2) items.put(encode(value));
            }
            root.put("items", items);
            preferences.launcherHorizontalGroupsJson.set(root.toString());
        } catch (JSONException ignored) {
        }
    }

    @NonNull
    private static JSONObject encode(@NonNull Group value) throws JSONException {
        JSONArray members = new JSONArray();
        for (String id : value.memberIds) members.put(id);
        return new JSONObject()
                .put("id", value.id).put("name", value.name)
                .put("x", value.x).put("y", value.y)
                .put("width", value.width).put("height", value.height)
                .put("gapPx", value.gapPx)
                .put("paddingLeftPx", value.paddingLeftPx)
                .put("paddingTopPx", value.paddingTopPx)
                .put("paddingRightPx", value.paddingRightPx)
                .put("paddingBottomPx", value.paddingBottomPx)
                .put("horizontalAlignment", value.horizontalAlignment)
                .put("verticalAlignment", value.verticalAlignment)
                .put("distribution", value.distribution)
                .put("memberIds", members);
    }

    @NonNull
    private static Group decode(@NonNull JSONObject source) {
        Group value = new Group();
        value.id = source.optString("id", "");
        value.name = source.optString("name", "Горизонтальный ряд");
        value.x = source.optInt("x", 0);
        value.y = source.optInt("y", 0);
        value.width = source.optInt("width", 620);
        value.height = source.optInt("height", 150);
        value.gapPx = source.optInt("gapPx", 0);
        value.paddingLeftPx = source.optInt("paddingLeftPx", 0);
        value.paddingTopPx = source.optInt("paddingTopPx", 0);
        value.paddingRightPx = source.optInt("paddingRightPx", 0);
        value.paddingBottomPx = source.optInt("paddingBottomPx", 0);
        value.horizontalAlignment = source.optInt("horizontalAlignment", 0);
        value.verticalAlignment = source.optInt("verticalAlignment", 1);
        value.distribution = source.optInt("distribution",
                HorizontalGroupLayout.DISTRIBUTION_COMPACT);
        JSONArray members = source.optJSONArray("memberIds");
        if (members != null) {
            for (int index = 0; index < members.length(); index++) {
                value.memberIds.add(members.optString(index, ""));
            }
        }
        return value;
    }

    @NonNull
    private static String clean(@Nullable String raw, int maximum) {
        String value = raw == null ? "" : raw.trim();
        if (value.length() > maximum || value.indexOf('\u0000') >= 0) return "";
        return value;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
