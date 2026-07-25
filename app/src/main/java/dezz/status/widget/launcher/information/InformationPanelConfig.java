/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.information;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dezz.status.widget.integration.SourceBinding;

/** User-owned grid and read-only source bindings for the HOME “Information” panel. */
public final class InformationPanelConfig {
    public static final int MIN_SCALE = 55;
    public static final int MAX_SCALE = 220;

    @NonNull public String backgroundColor = "#111822";
    public int backgroundAlpha = 220;
    public int cornerRadiusPx = 28;
    public int contentPaddingPx = 12;
    public int gapPx = 8;
    public int columns = 3;
    public int rows = 2;
    @NonNull private final List<Item> items = new ArrayList<>();

    public enum SourceKind {
        SYSTEM,
        VEHICLE,
        CONNECTOR
    }

    public enum Visibility {
        ALWAYS,
        WHEN_KNOWN,
        WHEN_ACTIVE,
        WHEN_INACTIVE
    }

    /** One independently positioned, non-interactive status cell. */
    public static final class Item {
        @NonNull public final String id;
        @NonNull public SourceKind sourceKind;
        /** Stable system/vehicle metric id. Empty for a connector source. */
        @NonNull public String sourceId;
        @Nullable public SourceBinding binding;
        /** Catalog metadata retained so labels/icons remain useful while a connector is offline. */
        @NonNull public String sourceLabel;
        @NonNull public String sourceUnit;
        @NonNull public String sourceTypeHint;
        @NonNull public String labelOverride;
        /** “auto” follows source semantics; any other value is a LauncherIconResolver preset. */
        @NonNull public String iconKey;
        @NonNull public String iconColor;
        @NonNull public String valueColor;
        @NonNull public String labelColor;
        @NonNull public Visibility visibility;
        public boolean enabled;
        public boolean showIcon;
        public boolean showLabel;
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;
        public int scalePercent;
        public int decimals;
        @NonNull public String unitOverride;
        /** Optional top gap for vertical surfaces; -1 inherits the panel-wide gap. */
        public int gapBeforePx;
        /** Draw a visual group boundary immediately before this item. */
        public boolean dividerBefore;

        private Item(@NonNull String id, @NonNull SourceKind sourceKind,
                     @NonNull String sourceId, @Nullable SourceBinding binding,
                     @NonNull String sourceLabel, @NonNull String sourceUnit,
                     @NonNull String sourceTypeHint) {
            this.id = id;
            this.sourceKind = sourceKind;
            this.sourceId = sourceId;
            this.binding = binding;
            this.sourceLabel = sourceLabel;
            this.sourceUnit = sourceUnit;
            this.sourceTypeHint = sourceTypeHint;
            labelOverride = "";
            iconKey = "auto";
            iconColor = "#FFFFFF";
            valueColor = "#FFFFFF";
            labelColor = "#AEB9C8";
            visibility = Visibility.ALWAYS;
            enabled = true;
            showIcon = true;
            showLabel = true;
            column = 0;
            row = 0;
            columnSpan = 1;
            rowSpan = 1;
            scalePercent = 100;
            decimals = suggestedDecimals(sourceUnit);
            unitOverride = "";
            gapBeforePx = -1;
            dividerBefore = false;
        }

        @NonNull
        public static Item system(@NonNull String sourceId, @NonNull String label,
                                  @NonNull String unit, @NonNull String typeHint) {
            return new Item(newId(), SourceKind.SYSTEM, sourceId, null, label, unit, typeHint);
        }

        @NonNull
        public static Item vehicle(@NonNull String sourceId, @NonNull String label,
                                   @NonNull String unit, @NonNull String typeHint) {
            return new Item(newId(), SourceKind.VEHICLE, sourceId, null, label, unit, typeHint);
        }

        @NonNull
        public static Item connector(@NonNull SourceBinding binding, @NonNull String label,
                                     @NonNull String unit, @NonNull String typeHint) {
            return new Item(newId(), SourceKind.CONNECTOR, "", binding, label, unit, typeHint);
        }

        @NonNull
        static Item restored(@NonNull String id, @NonNull SourceKind sourceKind,
                             @NonNull String sourceId, @Nullable SourceBinding binding,
                             @NonNull String sourceLabel, @NonNull String sourceUnit,
                             @NonNull String sourceTypeHint) {
            return new Item(id, sourceKind, sourceId, binding, sourceLabel, sourceUnit,
                    sourceTypeHint);
        }

        @NonNull
        public Item copy() {
            Item value = new Item(id, sourceKind, sourceId, binding, sourceLabel, sourceUnit,
                    sourceTypeHint);
            value.labelOverride = labelOverride;
            value.iconKey = iconKey;
            value.iconColor = iconColor;
            value.valueColor = valueColor;
            value.labelColor = labelColor;
            value.visibility = visibility;
            value.enabled = enabled;
            value.showIcon = showIcon;
            value.showLabel = showLabel;
            value.column = column;
            value.row = row;
            value.columnSpan = columnSpan;
            value.rowSpan = rowSpan;
            value.scalePercent = scalePercent;
            value.decimals = decimals;
            value.unitOverride = unitOverride;
            value.gapBeforePx = gapBeforePx;
            value.dividerBefore = dividerBefore;
            return value;
        }

        @NonNull
        public String displayLabel() {
            return labelOverride.isEmpty() ? sourceLabel : labelOverride;
        }

        private void normalize(int columns, int rows) {
            sourceId = clean(sourceId);
            sourceLabel = textOr(sourceLabel, sourceId.isEmpty() ? "Статус" : sourceId);
            sourceUnit = clean(sourceUnit);
            sourceTypeHint = clean(sourceTypeHint);
            labelOverride = clean(labelOverride);
            iconKey = textOr(iconKey, "auto");
            iconColor = colorOr(iconColor, "#FFFFFF");
            valueColor = colorOr(valueColor, "#FFFFFF");
            labelColor = colorOr(labelColor, "#AEB9C8");
            visibility = visibility == null ? Visibility.ALWAYS : visibility;
            scalePercent = clamp(scalePercent, MIN_SCALE, MAX_SCALE);
            decimals = clamp(decimals, 0, 4);
            unitOverride = clean(unitOverride);
            gapBeforePx = clamp(gapBeforePx, -1, 160);
            columnSpan = clamp(columnSpan, 1, columns);
            rowSpan = clamp(rowSpan, 1, rows);
            column = clamp(column, 0, Math.max(0, columns - columnSpan));
            row = clamp(row, 0, Math.max(0, rows - rowSpan));
            if (sourceKind == SourceKind.CONNECTOR
                    && (binding == null || !binding.isBound())) {
                enabled = false;
            }
        }
    }

    @NonNull
    public List<Item> items() {
        List<Item> copy = new ArrayList<>(items.size());
        for (Item item : items) copy.add(item.copy());
        return Collections.unmodifiableList(copy);
    }

    /** Mutable live entries for the settings screen; callers persist through the store. */
    @NonNull
    public List<Item> mutableItems() {
        return items;
    }

    @Nullable
    public Item find(@NonNull String id) {
        for (Item item : items) if (item.id.equals(id)) return item;
        return null;
    }

    public void add(@NonNull Item item) {
        Item value = item.copy();
        placeInFirstFreeCell(value);
        items.add(value);
        normalize();
    }

    public boolean remove(@NonNull String id) {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id.equals(id)) {
                items.remove(index);
                return true;
            }
        }
        return false;
    }

    public boolean move(@NonNull String id, int delta) {
        int from = -1;
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).id.equals(id)) {
                from = index;
                break;
            }
        }
        if (from < 0 || delta == 0) return false;
        int to = clamp(from + (delta < 0 ? -1 : 1), 0, items.size() - 1);
        if (from == to) return false;
        Item value = items.remove(from);
        items.add(to, value);
        return true;
    }

    /** Atomic WYSIWYG placement used by the real-HOME editor. */
    public boolean setPlacement(@NonNull String id, int column, int row,
                                int columnSpan, int rowSpan) {
        Item target = find(id);
        if (target == null || !target.enabled) return false;
        int nextColumnSpan = clamp(columnSpan, 1, columns);
        int nextRowSpan = clamp(rowSpan, 1, rows);
        int nextColumn = clamp(column, 0, columns - nextColumnSpan);
        int nextRow = clamp(row, 0, rows - nextRowSpan);
        if (target.column == nextColumn && target.row == nextRow
                && target.columnSpan == nextColumnSpan
                && target.rowSpan == nextRowSpan) return false;
        for (Item other : items) {
            if (other == target || !other.enabled) continue;
            if (intersects(nextColumn, nextRow, nextColumnSpan, nextRowSpan,
                    other.column, other.row, other.columnSpan, other.rowSpan)) {
                return false;
            }
        }
        target.column = nextColumn;
        target.row = nextRow;
        target.columnSpan = nextColumnSpan;
        target.rowSpan = nextRowSpan;
        return true;
    }

    public boolean hasEnabledItems() {
        for (Item item : items) if (item.enabled) return true;
        return false;
    }

    @NonNull
    public InformationPanelConfig copy() {
        InformationPanelConfig value = new InformationPanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.contentPaddingPx = contentPaddingPx;
        value.gapPx = gapPx;
        value.columns = columns;
        value.rows = rows;
        for (Item item : items) value.items.add(item.copy());
        value.normalize();
        return value;
    }

    public void normalize() {
        backgroundColor = colorOr(backgroundColor, "#111822");
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 120);
        contentPaddingPx = clamp(contentPaddingPx, 0, 80);
        gapPx = clamp(gapPx, 0, 48);
        columns = clamp(columns, 1, 8);
        // Driver information tiles are intentionally not subject to the ten-button limit.
        // HOME keeps its traditional twelve-row ceiling unless actual configured items require
        // more storage rows; the driver policy later projects every enabled item to one row.
        rows = clamp(rows, 1, Math.max(12, items.size() + 1));
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < items.size();) {
            Item item = items.get(index);
            if (item == null || item.id.trim().isEmpty() || !ids.add(item.id)) {
                items.remove(index);
                continue;
            }
            item.normalize(columns, rows);
            index++;
        }

        // GridLayout permits overlapping specs, but a status hidden behind another tile is never
        // a useful or discoverable result. Preserve the first item at its requested rectangle;
        // relocate later collisions deterministically, growing rows when necessary.
        boolean[][] occupied = new boolean[Math.max(rows, items.size() + 1)][columns];
        for (Item item : items) {
            if (!item.enabled) continue;
            if (!fits(item, occupied, rows)) {
                int[] free = firstFree(item.columnSpan, item.rowSpan, occupied, rows, columns);
                if (free == null) {
                    item.enabled = false;
                    continue;
                }
                item.column = free[0];
                item.row = free[1];
                rows = Math.max(rows, item.row + item.rowSpan);
            }
            occupy(item, occupied);
        }
    }

    private static boolean fits(@NonNull Item item, @NonNull boolean[][] occupied,
                                int activeRows) {
        if (item.row < 0 || item.column < 0
                || item.row + item.rowSpan > activeRows
                || item.column + item.columnSpan > occupied[0].length) return false;
        for (int row = item.row; row < item.row + item.rowSpan; row++) {
            for (int column = item.column; column < item.column + item.columnSpan; column++) {
                if (occupied[row][column]) return false;
            }
        }
        return true;
    }

    @Nullable
    private static int[] firstFree(int columnSpan, int rowSpan,
                                   @NonNull boolean[][] occupied, int activeRows,
                                   int columns) {
        int maximumStart = occupied.length - rowSpan;
        for (int row = 0; row <= maximumStart; row++) {
            // Existing rows are searched first; new rows immediately follow when the visible
            // rectangle is full. This keeps imported order stable and makes growth predictable.
            if (row > activeRows && row - activeRows > 1) continue;
            for (int column = 0; column + columnSpan <= columns; column++) {
                boolean free = true;
                for (int checkRow = row; checkRow < row + rowSpan && free; checkRow++) {
                    for (int checkColumn = column;
                         checkColumn < column + columnSpan; checkColumn++) {
                        if (occupied[checkRow][checkColumn]) {
                            free = false;
                            break;
                        }
                    }
                }
                if (free) return new int[]{column, row};
            }
        }
        return null;
    }

    private static void occupy(@NonNull Item item, @NonNull boolean[][] occupied) {
        for (int row = item.row; row < item.row + item.rowSpan; row++) {
            for (int column = item.column; column < item.column + item.columnSpan; column++) {
                occupied[row][column] = true;
            }
        }
    }

    private static boolean intersects(int firstColumn, int firstRow,
                                      int firstColumnSpan, int firstRowSpan,
                                      int secondColumn, int secondRow,
                                      int secondColumnSpan, int secondRowSpan) {
        return firstColumn < secondColumn + secondColumnSpan
                && firstColumn + firstColumnSpan > secondColumn
                && firstRow < secondRow + secondRowSpan
                && firstRow + firstRowSpan > secondRow;
    }

    private void placeInFirstFreeCell(@NonNull Item item) {
        boolean[][] used = new boolean[Math.max(1, rows)][Math.max(1, columns)];
        for (Item existing : items) {
            if (!existing.enabled) continue;
            int endRow = Math.min(rows, existing.row + Math.max(1, existing.rowSpan));
            int endColumn = Math.min(columns,
                    existing.column + Math.max(1, existing.columnSpan));
            for (int row = Math.max(0, existing.row); row < endRow; row++) {
                for (int column = Math.max(0, existing.column);
                     column < endColumn; column++) used[row][column] = true;
            }
        }
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (!used[row][column]) {
                    item.row = row;
                    item.column = column;
                    return;
                }
            }
        }
        item.row = rows;
        item.column = 0;
        rows++;
    }

    @NonNull
    private static String newId() {
        return "info_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static int suggestedDecimals(@Nullable String unit) {
        String value = unit == null ? "" : unit.toLowerCase(java.util.Locale.ROOT);
        return value.contains("°") || value.contains("bar") || value.contains("бар") ? 1 : 0;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @NonNull
    private static String clean(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    @NonNull
    private static String textOr(@Nullable String value, @NonNull String fallback) {
        String clean = clean(value);
        return clean.isEmpty() ? fallback : clean;
    }

    @NonNull
    private static String colorOr(@Nullable String value, @NonNull String fallback) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")
                ? value : fallback;
    }
}
