/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cell geometry for the mixed HOME actions/smart-home panel.
 *
 * <p>Action definitions deliberately remain in {@link LauncherShortcutStore}. Keeping geometry
 * in an additive document means an update cannot lose an application, vehicle command, RULE
 * binding, long action, or live-state presentation merely because the grid evolves.</p>
 */
public final class LauncherActionsGridConfig {
    public static final String ADD_TILE_ID = "action.add";
    public static final int DEFAULT_COLUMNS = 3;
    public static final int MIN_COLUMNS = 1;
    public static final int MAX_COLUMNS = 8;
    public static final int MIN_ROWS = 1;
    public static final int MAX_ROWS = 24;
    public static final int DEFAULT_GAP_PX = 8;
    public static final int MAX_GAP_PX = 48;

    public static final class Placement {
        @NonNull public final String id;
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;

        public Placement(@NonNull String id, int column, int row,
                         int columnSpan, int rowSpan) {
            this.id = id;
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        @NonNull
        public Placement copy() {
            return new Placement(id, column, row, columnSpan, rowSpan);
        }
    }

    public int columns = DEFAULT_COLUMNS;
    public int rows = MIN_ROWS;
    public int gapPx = DEFAULT_GAP_PX;
    @NonNull private final LinkedHashMap<String, Placement> placements =
            new LinkedHashMap<>();

    @NonNull
    public List<Placement> placements() {
        List<Placement> result = new ArrayList<>(placements.size());
        for (Placement placement : placements.values()) result.add(placement.copy());
        return Collections.unmodifiableList(result);
    }

    @Nullable
    public Placement placement(@NonNull String id) {
        return placements.get(id);
    }

    public void put(@NonNull Placement placement) {
        placements.put(placement.id, placement.copy());
    }

    /**
     * Reconciles stable shortcut IDs without moving an existing valid rectangle. Hidden icons
     * keep their cells reserved, so switching one back on cannot unexpectedly rearrange HOME.
     */
    public boolean reconcile(@NonNull List<LauncherShortcutStore.Shortcut> shortcuts) {
        columns = clamp(columns, MIN_COLUMNS, MAX_COLUMNS);
        rows = clamp(rows, MIN_ROWS, MAX_ROWS);
        gapPx = clamp(gapPx, 0, MAX_GAP_PX);

        LinkedHashMap<String, LauncherShortcutStore.Shortcut> desired = new LinkedHashMap<>();
        for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
            if (shortcut == null || shortcut.id.trim().isEmpty()) continue;
            desired.put(shortcut.id, shortcut);
        }
        Set<String> retained = new HashSet<>(desired.keySet());
        retained.add(ADD_TILE_ID);
        boolean changed = placements.keySet().retainAll(retained);

        for (LauncherShortcutStore.Shortcut shortcut : desired.values()) {
            if (placements.containsKey(shortcut.id)) continue;
            // A never-shown legacy item must not make the first upgraded HOME grid taller and
            // shrink all visible cells. It receives a stable cell when the user enables it.
            if (!shortcut.enabled) continue;
            int width = clamp(shortcut.columnSpan, 1, columns);
            int height = clamp(shortcut.rowSpan, 1, MAX_ROWS);
            Placement created = placeFirstFree(shortcut.id, width, height);
            if (created != null) {
                placements.put(created.id, created);
                changed = true;
            }
        }
        if (!placements.containsKey(ADD_TILE_ID)) {
            Placement add = placeFirstFree(ADD_TILE_ID, 1, 1);
            if (add != null) {
                placements.put(add.id, add);
                changed = true;
            }
        }
        return normalize() || changed;
    }

    /**
     * Applies one drag/resize transaction. A collision is rejected without changing any item.
     */
    public boolean setPlacement(@NonNull String id, int column, int row,
                                int columnSpan, int rowSpan) {
        Placement current = placements.get(id);
        if (current == null) return false;
        int width = clamp(columnSpan, 1, columns);
        int height = clamp(rowSpan, 1, rows);
        int selectedColumn = clamp(column, 0, columns - width);
        int selectedRow = clamp(row, 0, rows - height);
        Placement candidate = new Placement(id, selectedColumn, selectedRow, width, height);
        if (!isFree(candidate, id)) return false;
        boolean changed = current.column != selectedColumn || current.row != selectedRow
                || current.columnSpan != width || current.rowSpan != height;
        if (!changed) return false;
        current.column = selectedColumn;
        current.row = selectedRow;
        current.columnSpan = width;
        current.rowSpan = height;
        return true;
    }

    /**
     * Changes logical cell size atomically. Items are kept near their relative old positions;
     * an impossibly small grid leaves the last valid configuration untouched.
     */
    public boolean setGridSize(int requestedColumns, int requestedRows) {
        int selectedColumns = clamp(requestedColumns, MIN_COLUMNS, MAX_COLUMNS);
        int selectedRows = clamp(requestedRows, MIN_ROWS, MAX_ROWS);
        if (selectedColumns == columns && selectedRows == rows) return true;

        LauncherActionsGridConfig candidate = copy();
        int oldColumns = candidate.columns;
        int oldRows = candidate.rows;
        candidate.columns = selectedColumns;
        candidate.rows = selectedRows;
        candidate.placements.clear();
        for (Placement old : placements.values()) {
            int width = clamp(old.columnSpan, 1, selectedColumns);
            int height = clamp(old.rowSpan, 1, selectedRows);
            int preferredColumn = scaledStart(old.column, oldColumns,
                    selectedColumns, width);
            int preferredRow = scaledStart(old.row, oldRows, selectedRows, height);
            Placement placed = candidate.placeNearest(old.id, preferredColumn, preferredRow,
                    width, height, false);
            if (placed == null) return false;
            candidate.placements.put(placed.id, placed);
        }
        columns = candidate.columns;
        rows = candidate.rows;
        placements.clear();
        placements.putAll(candidate.placements);
        return true;
    }

    @NonNull
    public LauncherActionsGridConfig copy() {
        LauncherActionsGridConfig result = new LauncherActionsGridConfig();
        result.columns = columns;
        result.rows = rows;
        result.gapPx = gapPx;
        for (Placement placement : placements.values()) {
            result.placements.put(placement.id, placement.copy());
        }
        return result;
    }

    /**
     * Exact bridge from HA1079: active shortcuts are first-fit packed in saved order, followed
     * by the existing Add tile. Legacy-hidden shortcuts receive a cell only when enabled, so
     * they cannot make the first upgraded HOME grid taller and shrink all visible icons.
     */
    @NonNull
    public static LauncherActionsGridConfig migrateLegacy(
            @NonNull List<LauncherShortcutStore.Shortcut> shortcuts, int legacyColumns) {
        LauncherActionsGridConfig result = new LauncherActionsGridConfig();
        result.columns = clamp(legacyColumns, MIN_COLUMNS, MAX_COLUMNS);
        result.rows = MIN_ROWS;
        for (LauncherShortcutStore.Shortcut shortcut : shortcuts) {
            if (!shortcut.enabled) continue;
            result.appendLegacy(shortcut.id, shortcut.columnSpan, shortcut.rowSpan);
        }
        result.appendLegacy(ADD_TILE_ID, 1, 1);
        result.normalize();
        return result;
    }

    private void appendLegacy(@NonNull String id, int requestedWidth, int requestedHeight) {
        int width = clamp(requestedWidth, 1, columns);
        int height = clamp(requestedHeight, 1, MAX_ROWS);
        for (int row = 0; row + height <= MAX_ROWS; row++) {
            for (int column = 0; column + width <= columns; column++) {
                Placement candidate = new Placement(id, column, row, width, height);
                if (!isFree(candidate, id)) continue;
                placements.put(id, candidate);
                rows = Math.max(rows, row + height);
                return;
            }
        }
    }

    /** Normalizes imported data deterministically, growing rows instead of hiding an icon. */
    public boolean normalize() {
        int originalColumns = columns;
        int originalRows = rows;
        int originalGap = gapPx;
        columns = clamp(columns, MIN_COLUMNS, MAX_COLUMNS);
        rows = clamp(rows, MIN_ROWS, MAX_ROWS);
        gapPx = clamp(gapPx, 0, MAX_GAP_PX);
        boolean changed = columns != originalColumns || rows != originalRows
                || gapPx != originalGap;

        LinkedHashMap<String, Placement> normalized = new LinkedHashMap<>();
        for (Placement old : placements.values()) {
            if (old == null || old.id.trim().isEmpty() || normalized.containsKey(old.id)) {
                changed = true;
                continue;
            }
            int width = clamp(old.columnSpan, 1, columns);
            int height = clamp(old.rowSpan, 1, MAX_ROWS);
            int preferredColumn = clamp(old.column, 0, Math.max(0, columns - width));
            int preferredRow = Math.max(0, old.row);
            Placement placed = placeNearest(normalized, old.id, preferredColumn, preferredRow,
                    width, height, true);
            if (placed == null) {
                changed = true;
                continue;
            }
            changed |= old.column != placed.column || old.row != placed.row
                    || old.columnSpan != placed.columnSpan || old.rowSpan != placed.rowSpan;
            normalized.put(placed.id, placed);
            rows = Math.max(rows, placed.row + placed.rowSpan);
        }
        rows = clamp(rows, MIN_ROWS, MAX_ROWS);
        placements.clear();
        placements.putAll(normalized);
        return changed;
    }

    @Nullable
    private Placement placeFirstFree(@NonNull String id, int width, int height) {
        Placement placed = placeNearest(id, 0, 0, width, height, true);
        if (placed != null) rows = Math.max(rows, placed.row + placed.rowSpan);
        return placed;
    }

    @Nullable
    private Placement placeNearest(@NonNull String id, int preferredColumn, int preferredRow,
                                   int width, int height, boolean mayGrowRows) {
        return placeNearest(placements, id, preferredColumn, preferredRow,
                width, height, mayGrowRows);
    }

    @Nullable
    private Placement placeNearest(@NonNull Map<String, Placement> occupied,
                                   @NonNull String id, int preferredColumn, int preferredRow,
                                   int requestedWidth, int requestedHeight,
                                   boolean mayGrowRows) {
        int width = clamp(requestedWidth, 1, columns);
        int height = clamp(requestedHeight, 1, mayGrowRows ? MAX_ROWS : rows);
        int maximumRows = mayGrowRows ? MAX_ROWS : rows;
        Placement best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int row = 0; row + height <= maximumRows; row++) {
            for (int column = 0; column + width <= columns; column++) {
                Placement candidate = new Placement(id, column, row, width, height);
                if (!isFree(candidate, id, occupied)) continue;
                int distance = Math.abs(column - preferredColumn)
                        + Math.abs(row - preferredRow);
                if (best == null || distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean isFree(@NonNull Placement candidate, @NonNull String exceptId) {
        return isFree(candidate, exceptId, placements);
    }

    private static boolean isFree(@NonNull Placement candidate, @NonNull String exceptId,
                                  @NonNull Map<String, Placement> occupied) {
        for (Placement other : occupied.values()) {
            if (other.id.equals(exceptId)) continue;
            if (overlaps(candidate, other)) return false;
        }
        return true;
    }

    static boolean overlaps(@NonNull Placement first, @NonNull Placement second) {
        return first.column < second.column + second.columnSpan
                && first.column + first.columnSpan > second.column
                && first.row < second.row + second.rowSpan
                && first.row + first.rowSpan > second.row;
    }

    private static int scaledStart(int oldStart, int oldCount, int newCount, int span) {
        if (oldCount <= 1 || newCount <= span) return 0;
        float fraction = clamp(oldStart, 0, oldCount - 1) / (float) (oldCount - 1);
        return clamp(Math.round(fraction * (newCount - span)), 0, newCount - span);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
