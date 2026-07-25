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
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.Preferences;

/**
 * Ten independently assignable compact favorite blocks opened from driver-rail buttons.
 *
 * <p>Actions remain in {@link LauncherShortcutStore}; this additive document owns only block
 * identity and grid appearance. The former one-list drawer migrates into the stable default
 * block without changing any action, icon or long-press binding.</p>
 */
public final class DriverFavoriteBlocksStore {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_BLOCKS = 10;
    public static final int MAX_COLUMNS = 8;
    public static final int MAX_ROWS = 12;
    public static final String DEFAULT_BLOCK_ID = "default";
    public static final String TARGET_PREFIX = "favorites:";

    public static final class Block {
        @NonNull public final String id;
        @NonNull public String title;
        public int columns;
        public int cellSizePx;
        public int gapPx;
        /** Comma-separated one-based boundaries after columns/rows. */
        @NonNull public String verticalDividers;
        @NonNull public String horizontalDividers;

        private Block(@NonNull String id, @NonNull String title,
                      int columns, int cellSizePx, int gapPx) {
            this(id, title, columns, cellSizePx, gapPx, "", "");
        }

        private Block(@NonNull String id, @NonNull String title,
                      int columns, int cellSizePx, int gapPx,
                      @NonNull String verticalDividers,
                      @NonNull String horizontalDividers) {
            this.id = id;
            this.title = title;
            this.columns = columns;
            this.cellSizePx = cellSizePx;
            this.gapPx = gapPx;
            this.verticalDividers = verticalDividers;
            this.horizontalDividers = horizontalDividers;
            normalize();
        }

        @NonNull
        public Block copy() {
            return new Block(id, title, columns, cellSizePx, gapPx,
                    verticalDividers, horizontalDividers);
        }

        public boolean hasVerticalDividerAfter(int zeroBasedColumn) {
            return dividerSet(verticalDividers, Math.max(0, columns - 1))
                    .contains(zeroBasedColumn + 1);
        }

        public boolean hasHorizontalDividerAfter(int zeroBasedRow) {
            return dividerSet(horizontalDividers, MAX_ROWS - 1)
                    .contains(zeroBasedRow + 1);
        }

        public void setVerticalDividerAfter(int zeroBasedColumn, boolean enabled) {
            Set<Integer> values = dividerSet(verticalDividers, Math.max(0, columns - 1));
            updateDivider(values, zeroBasedColumn + 1, enabled);
            verticalDividers = encodeDividers(values);
        }

        public void setHorizontalDividerAfter(int zeroBasedRow, boolean enabled) {
            Set<Integer> values = dividerSet(horizontalDividers, MAX_ROWS - 1);
            updateDivider(values, zeroBasedRow + 1, enabled);
            horizontalDividers = encodeDividers(values);
        }

        private void normalize() {
            title = title == null || title.trim().isEmpty() ? "Избранное" : title.trim();
            columns = clamp(columns, 1, MAX_COLUMNS);
            cellSizePx = clamp(cellSizePx, 64, 180);
            gapPx = clamp(gapPx, 0, 40);
            verticalDividers = encodeDividers(
                    dividerSet(verticalDividers, Math.max(0, columns - 1)));
            horizontalDividers = encodeDividers(
                    dividerSet(horizontalDividers, MAX_ROWS - 1));
        }
    }

    private final Preferences preferences;
    @NonNull private final List<Block> blocks = new ArrayList<>();

    public DriverFavoriteBlocksStore(@NonNull Preferences preferences) {
        this.preferences = preferences;
        load();
    }

    public void load() {
        blocks.clear();
        String raw = preferences.driverFavoriteBlocksJson.get();
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject root = new JSONObject(raw);
                if (root.optInt("version", 0) != SCHEMA_VERSION) {
                    throw new JSONException("schema");
                }
                JSONArray values = root.optJSONArray("blocks");
                if (values == null) throw new JSONException("blocks");
                Set<String> ids = new HashSet<>();
                for (int index = 0;
                     index < values.length() && blocks.size() < MAX_BLOCKS; index++) {
                    JSONObject value = values.optJSONObject(index);
                    if (value == null) continue;
                    String id = clean(value.optString("id", ""));
                    if (id.isEmpty() || !ids.add(id)) continue;
                    blocks.add(new Block(id, value.optString("title", "Избранное"),
                            value.optInt("columns", 4),
                            value.optInt("cellSizePx", 92),
                            value.optInt("gapPx", 6),
                            value.optString("verticalDividers", ""),
                            value.optString("horizontalDividers", "")));
                }
            } catch (JSONException ignored) {
                blocks.clear();
            }
        }
        ensureDefault();
    }

    @NonNull
    public List<Block> blocks() {
        List<Block> result = new ArrayList<>(blocks.size());
        for (Block block : blocks) result.add(block.copy());
        return Collections.unmodifiableList(result);
    }

    @NonNull
    public Block defaultBlock() {
        return find(DEFAULT_BLOCK_ID);
    }

    @NonNull
    public Block find(@Nullable String rawId) {
        String id = clean(rawId);
        for (Block block : blocks) if (block.id.equals(id)) return block.copy();
        return blocks.get(0).copy();
    }

    @Nullable
    public Block create() {
        if (blocks.size() >= MAX_BLOCKS) return null;
        Block value = new Block(UUID.randomUUID().toString(),
                "Избранное " + (blocks.size() + 1), 4, 92, 6);
        blocks.add(value);
        save();
        return value.copy();
    }

    public void upsert(@NonNull Block source) {
        Block value = source.copy();
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).id.equals(value.id)) {
                blocks.set(index, value);
                save();
                return;
            }
        }
        if (blocks.size() >= MAX_BLOCKS) return;
        blocks.add(value);
        save();
    }

    /**
     * Removes a non-default block and its items. Panel buttons targeting it safely fall back to
     * the default block, so an imported/stale assignment can never open a blank full-screen view.
     */
    public boolean remove(@NonNull String id, @NonNull LauncherShortcutStore shortcuts) {
        if (DEFAULT_BLOCK_ID.equals(id)) return false;
        boolean removed = blocks.removeIf(value -> value.id.equals(id));
        if (!removed) return false;
        for (LauncherShortcutStore.Shortcut item : shortcuts.all()) {
            if (id.equals(item.collectionId)) shortcuts.remove(item.id);
        }
        save();
        return true;
    }

    /**
     * Returns visible members after a deterministic first-free placement. Old drawer members have
     * no collection or cell coordinates and are migrated into the default block exactly once.
     */
    @NonNull
    public List<LauncherShortcutStore.Shortcut> items(
            @NonNull String requestedBlockId,
            @NonNull LauncherShortcutStore shortcuts) {
        Block block = find(requestedBlockId);
        List<LauncherShortcutStore.Shortcut> result = new ArrayList<>();
        boolean[][] occupied = new boolean[MAX_ROWS][block.columns];
        for (LauncherShortcutStore.Shortcut item : shortcuts.all()) {
            String collection = clean(item.collectionId);
            if (collection.isEmpty()) {
                item.collectionId = DEFAULT_BLOCK_ID;
                item.backgroundColor = legacyTransparentBackground(item.backgroundColor);
                if (item.gridColumn < 0 || item.gridRow < 0) {
                    item.gridColumn = 0;
                    item.gridRow = 0;
                }
                shortcuts.upsert(item);
                collection = DEFAULT_BLOCK_ID;
            }
            if (!block.id.equals(collection)) continue;
            int width = clamp(item.columnSpan, 1, block.columns);
            int height = clamp(item.rowSpan, 1, MAX_ROWS);
            int column = clamp(item.gridColumn, 0, block.columns - width);
            int row = clamp(item.gridRow, 0, MAX_ROWS - height);
            if (!free(occupied, column, row, width, height)) {
                int[] free = firstFree(occupied, width, height);
                if (free == null) continue;
                column = free[0];
                row = free[1];
            }
            occupy(occupied, column, row, width, height);
            if (item.gridColumn != column || item.gridRow != row
                    || item.columnSpan != width || item.rowSpan != height) {
                item.gridColumn = column;
                item.gridRow = row;
                item.columnSpan = width;
                item.rowSpan = height;
                shortcuts.upsert(item);
            }
            result.add(item);
        }
        return result;
    }

    public boolean setPlacement(@NonNull Block block,
                                @NonNull LauncherShortcutStore shortcuts,
                                @NonNull String itemId,
                                int column, int row, int columnSpan, int rowSpan) {
        List<LauncherShortcutStore.Shortcut> items = items(block.id, shortcuts);
        LauncherShortcutStore.Shortcut selected = null;
        boolean[][] occupied = new boolean[MAX_ROWS][block.columns];
        for (LauncherShortcutStore.Shortcut item : items) {
            if (item.id.equals(itemId)) {
                selected = item;
                continue;
            }
            occupy(occupied, item.gridColumn, item.gridRow,
                    item.columnSpan, item.rowSpan);
        }
        if (selected == null) return false;
        int width = clamp(columnSpan, 1, block.columns);
        int height = clamp(rowSpan, 1, MAX_ROWS);
        int x = clamp(column, 0, block.columns - width);
        int y = clamp(row, 0, MAX_ROWS - height);
        if (!free(occupied, x, y, width, height)) return false;
        selected.gridColumn = x;
        selected.gridRow = y;
        selected.columnSpan = width;
        selected.rowSpan = height;
        shortcuts.upsert(selected);
        return true;
    }

    public int usedRows(@NonNull Block block,
                        @NonNull List<LauncherShortcutStore.Shortcut> items) {
        int rows = 1;
        for (LauncherShortcutStore.Shortcut item : items) {
            rows = Math.max(rows, item.gridRow + item.rowSpan);
        }
        return clamp(rows, 1, MAX_ROWS);
    }

    @NonNull
    public static String targetFor(@NonNull String blockId) {
        return TARGET_PREFIX + clean(blockId);
    }

    @NonNull
    public static String blockIdFromTarget(@Nullable String target) {
        String value = clean(target);
        if (LauncherShortcutStore.Builtin.FAVORITES.key.equals(value)) {
            return DEFAULT_BLOCK_ID;
        }
        return value.startsWith(TARGET_PREFIX)
                ? clean(value.substring(TARGET_PREFIX.length())) : DEFAULT_BLOCK_ID;
    }

    public static boolean isFavoritesTarget(@Nullable String target) {
        String value = clean(target);
        return LauncherShortcutStore.Builtin.FAVORITES.key.equals(value)
                || value.startsWith(TARGET_PREFIX);
    }

    private void ensureDefault() {
        for (Block block : blocks) {
            if (DEFAULT_BLOCK_ID.equals(block.id)) return;
        }
        blocks.add(0, new Block(DEFAULT_BLOCK_ID, "Избранное", 4, 92, 6));
        save();
    }

    private void save() {
        try {
            JSONArray values = new JSONArray();
            for (Block block : blocks) {
                block.normalize();
                values.put(new JSONObject()
                        .put("id", block.id)
                        .put("title", block.title)
                        .put("columns", block.columns)
                        .put("cellSizePx", block.cellSizePx)
                        .put("gapPx", block.gapPx)
                        .put("verticalDividers", block.verticalDividers)
                        .put("horizontalDividers", block.horizontalDividers));
            }
            preferences.driverFavoriteBlocksJson.set(new JSONObject()
                    .put("version", SCHEMA_VERSION)
                    .put("blocks", values).toString());
        } catch (JSONException ignored) {
        }
    }

    private static boolean free(boolean[][] occupied, int column, int row,
                                int width, int height) {
        if (row < 0 || column < 0 || row + height > occupied.length
                || column + width > occupied[0].length) return false;
        for (int y = row; y < row + height; y++) {
            for (int x = column; x < column + width; x++) {
                if (occupied[y][x]) return false;
            }
        }
        return true;
    }

    @Nullable
    private static int[] firstFree(boolean[][] occupied, int width, int height) {
        for (int row = 0; row + height <= occupied.length; row++) {
            for (int column = 0; column + width <= occupied[0].length; column++) {
                if (free(occupied, column, row, width, height)) {
                    return new int[]{column, row};
                }
            }
        }
        return null;
    }

    private static void occupy(boolean[][] occupied, int column, int row,
                               int width, int height) {
        if (!free(occupied, column, row, width, height)) return;
        for (int y = row; y < row + height; y++) {
            for (int x = column; x < column + width; x++) occupied[y][x] = true;
        }
    }

    @NonNull
    private static Set<Integer> dividerSet(@Nullable String raw, int maximum) {
        Set<Integer> result = new java.util.TreeSet<>();
        String value = clean(raw);
        if (value.isEmpty() || maximum < 1) return result;
        for (String token : value.split(",")) {
            try {
                int boundary = Integer.parseInt(token.trim());
                if (boundary >= 1 && boundary <= maximum) result.add(boundary);
            } catch (NumberFormatException ignored) {
            }
        }
        return result;
    }

    private static void updateDivider(@NonNull Set<Integer> values,
                                      int boundary, boolean enabled) {
        if (enabled) values.add(boundary); else values.remove(boundary);
    }

    @NonNull
    private static String encodeDividers(@NonNull Set<Integer> values) {
        StringBuilder result = new StringBuilder();
        for (Integer value : values) {
            if (result.length() > 0) result.append(',');
            result.append(value);
        }
        return result.toString();
    }

    @NonNull
    private static String legacyTransparentBackground(@Nullable String value) {
        String raw = clean(value);
        return raw.isEmpty() || "#B5222733".equalsIgnoreCase(raw)
                ? "#00000000" : raw;
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
