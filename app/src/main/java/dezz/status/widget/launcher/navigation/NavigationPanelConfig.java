/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.navigation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dezz.status.widget.launcher.panels.PanelElementConfigStore;

/**
 * Connector-independent geometry of the live HOME navigation surface.
 *
 * <p>Coordinates and spans are expressed in cells, while {@link Element#scalePercent} changes
 * only the content inside the occupied rectangle. This keeps a saved layout stable when the
 * outer HOME panel is moved or resized on another display.</p>
 */
public final class NavigationPanelConfig {
    public static final String ARRIVAL = PanelElementConfigStore.NAV_ARRIVAL;
    public static final String DURATION = PanelElementConfigStore.NAV_DURATION;
    public static final String DISTANCE = PanelElementConfigStore.NAV_DISTANCE;
    public static final String MANEUVER_IMAGE = PanelElementConfigStore.NAV_MANEUVER_IMAGE;
    public static final String MANEUVER_DISTANCE =
            PanelElementConfigStore.NAV_MANEUVER_DISTANCE;
    public static final String MANEUVER = PanelElementConfigStore.NAV_MANEUVER;
    public static final String TRIP_INFO = PanelElementConfigStore.NAV_TRIP_INFO;
    public static final String COMBINED = PanelElementConfigStore.NAV_COMBINED;
    public static final String SPEED_LIMIT = PanelElementConfigStore.NAV_SPEED_LIMIT;
    public static final String TRAFFIC_LIGHT = PanelElementConfigStore.NAV_TRAFFIC_LIGHT;
    public static final String LANES_IMAGE = PanelElementConfigStore.NAV_LANES_IMAGE;
    public static final String LANE_INFO = PanelElementConfigStore.NAV_LANE_INFO;
    public static final String JAM_PROGRESS = PanelElementConfigStore.NAV_JAM_PROGRESS;
    public static final String RAINBOW_IMAGE = PanelElementConfigStore.NAV_RAINBOW_IMAGE;
    public static final String INACTIVE = PanelElementConfigStore.NAV_INACTIVE;

    public static final int DEFAULT_GRID_COLUMNS = 12;
    public static final int DEFAULT_GRID_ROWS = 8;
    public static final int MIN_GRID_COLUMNS = 1;
    public static final int MAX_GRID_COLUMNS = 24;
    public static final int MIN_GRID_ROWS = 1;
    public static final int MAX_GRID_ROWS = 16;
    public static final int MIN_SCALE = 45;
    public static final int MAX_SCALE = 220;

    public static final class Spec {
        @NonNull public final String id;
        @NonNull public final String label;
        public final boolean enabledByDefault;
        public final int defaultColumn;
        public final int defaultRow;
        public final int defaultColumnSpan;
        public final int defaultRowSpan;

        private Spec(@NonNull String id, @NonNull String label, boolean enabledByDefault,
                     int column, int row, int columnSpan, int rowSpan) {
            this.id = id;
            this.label = label;
            this.enabledByDefault = enabledByDefault;
            this.defaultColumn = column;
            this.defaultRow = row;
            this.defaultColumnSpan = columnSpan;
            this.defaultRowSpan = rowSpan;
        }
    }

    public static final class Element {
        @NonNull public final String id;
        public boolean enabled;
        public int scalePercent;
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;

        private Element(@NonNull String id, boolean enabled, int scalePercent,
                        int column, int row, int columnSpan, int rowSpan) {
            this.id = id;
            this.enabled = enabled;
            this.scalePercent = scalePercent;
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        @NonNull private Element copy() {
            return new Element(id, enabled, scalePercent, column, row, columnSpan, rowSpan);
        }
    }

    /**
     * The maneuver arrow bitmap and lane-guidance bitmap deliberately remain separate specs.
     * Some Yandex/MConfig publishers update them at different rates and either one may be absent.
     */
    public static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new Spec(ARRIVAL, "Время прибытия", true, 0, 0, 4, 2),
            new Spec(DURATION, "Оставшееся время", true, 4, 0, 4, 2),
            new Spec(DISTANCE, "Оставшееся расстояние", true, 8, 0, 4, 2),
            new Spec(MANEUVER_IMAGE, "Стрелка следующего манёвра", false, 0, 1, 3, 3),
            new Spec(MANEUVER_DISTANCE, "Расстояние до манёвра", false, 3, 2, 4, 1),
            new Spec(MANEUVER, "Описание манёвра", true, 3, 2, 6, 2),
            new Spec(TRIP_INFO, "Дополнительная информация", false, 3, 4, 6, 2),
            new Spec(COMBINED, "Манёвр: стрелка и текст", false, 0, 2, 9, 4),
            new Spec(SPEED_LIMIT, "Ограничение скорости", true, 9, 2, 3, 2),
            new Spec(TRAFFIC_LIGHT, "Светофор и отсчёт", true, 9, 4, 3, 2),
            new Spec(LANES_IMAGE, "Графика полос движения", false, 0, 5, 6, 2),
            new Spec(LANE_INFO, "Полосы, съезд и расстояние", false, 6, 5, 6, 1),
            new Spec(JAM_PROGRESS, "Графика пробок", false, 0, 6, 6, 1),
            new Spec(RAINBOW_IMAGE, "Графика Rainbow", false, 6, 6, 6, 1),
            new Spec(INACTIVE, "Сообщение без маршрута", true, 0, 6, 12, 2)));

    public int gridColumns = DEFAULT_GRID_COLUMNS;
    public int gridRows = DEFAULT_GRID_ROWS;
    private final LinkedHashMap<String, Element> elements = new LinkedHashMap<>();

    public NavigationPanelConfig() {
        for (Spec spec : SPECS) {
            elements.put(spec.id, new Element(spec.id, spec.enabledByDefault, 100,
                    spec.defaultColumn, spec.defaultRow,
                    spec.defaultColumnSpan, spec.defaultRowSpan));
        }
    }

    @Nullable
    public static Spec spec(@NonNull String id) {
        for (Spec value : SPECS) if (value.id.equals(id)) return value;
        return null;
    }

    @NonNull
    public Element element(@NonNull String id) {
        Element value = elements.get(id);
        return value == null ? new Element(id, false, 100, 0, 0, 1, 1) : value;
    }

    @NonNull
    public List<Element> elements() {
        return Collections.unmodifiableList(new ArrayList<>(elements.values()));
    }

    @NonNull
    public List<Element> enabledElements() {
        ArrayList<Element> result = new ArrayList<>();
        for (Element value : elements.values()) if (value.enabled) result.add(value);
        return Collections.unmodifiableList(result);
    }

    public boolean hasEnabledElements() {
        for (Element value : elements.values()) if (value.enabled) return true;
        return false;
    }

    /**
     * Enables an item at its saved/default location, or at the closest free location when that
     * rectangle is occupied. Existing elements never jump merely because a hidden item was added.
     */
    public boolean setEnabled(@NonNull String id, boolean enabled) {
        Element value = elements.get(id);
        if (value == null) return false;
        if (!enabled) {
            value.enabled = false;
            return true;
        }
        if (value.enabled) return true;
        value.enabled = true;
        normalizePlacement(value);
        if (isFree(value)) return true;
        int[] free = nearestFree(value, value.column, value.row);
        if (free == null) {
            value.enabled = false;
            return false;
        }
        value.column = free[0];
        value.row = free[1];
        return true;
    }

    public void setScale(@NonNull String id, int scalePercent) {
        Element value = elements.get(id);
        if (value != null) value.scalePercent = clamp(scalePercent, MIN_SCALE, MAX_SCALE);
    }

    public boolean setPosition(@NonNull String id, int column, int row) {
        Element value = elements.get(id);
        if (value == null) return false;
        return setPlacement(id, column, row, value.columnSpan, value.rowSpan);
    }

    public boolean setSpan(@NonNull String id, int columnSpan, int rowSpan) {
        Element value = elements.get(id);
        if (value == null) return false;
        return setPlacement(id, value.column, value.row, columnSpan, rowSpan);
    }

    /**
     * Applies one atomic drag/resize operation. Colliding enabled items move to the nearest free
     * slot, preferring the edited item's former location; an impossible change is rolled back.
     */
    public boolean setPlacement(@NonNull String id, int column, int row,
                                int columnSpan, int rowSpan) {
        Element anchor = elements.get(id);
        if (anchor == null) return false;
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        int previousColumn = anchor.column;
        int previousRow = anchor.row;
        anchor.column = column;
        anchor.row = row;
        anchor.columnSpan = columnSpan;
        anchor.rowSpan = rowSpan;
        normalizePlacement(anchor);
        if (!anchor.enabled || displaceCollisions(anchor, previousColumn, previousRow)) {
            return true;
        }
        restoreElements(snapshot);
        return false;
    }

    /**
     * Resizes the logical grid while preserving relative positions. A grid too small for all
     * enabled rectangles is rejected without partially mutating the current layout.
     */
    public boolean setGridSize(int columns, int rows) {
        int selectedColumns = clamp(columns, MIN_GRID_COLUMNS, MAX_GRID_COLUMNS);
        int selectedRows = clamp(rows, MIN_GRID_ROWS, MAX_GRID_ROWS);
        if (selectedColumns == gridColumns && selectedRows == gridRows) return true;

        int previousColumns = gridColumns;
        int previousRows = gridRows;
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        gridColumns = selectedColumns;
        gridRows = selectedRows;
        boolean[][] occupied = new boolean[gridRows][gridColumns];
        for (Element element : elements.values()) {
            int oldColumn = element.column;
            int oldRow = element.row;
            element.columnSpan = clamp(element.columnSpan, 1, gridColumns);
            element.rowSpan = clamp(element.rowSpan, 1, gridRows);
            int preferredColumn = scaledStart(oldColumn, previousColumns,
                    gridColumns, element.columnSpan);
            int preferredRow = scaledStart(oldRow, previousRows,
                    gridRows, element.rowSpan);
            if (!element.enabled) {
                element.column = preferredColumn;
                element.row = preferredRow;
                continue;
            }
            int[] free = nearestFree(occupied, preferredColumn, preferredRow,
                    element.columnSpan, element.rowSpan);
            if (free == null) {
                gridColumns = previousColumns;
                gridRows = previousRows;
                restoreElements(snapshot);
                return false;
            }
            element.column = free[0];
            element.row = free[1];
            occupy(occupied, element);
        }
        return true;
    }

    /**
     * One-time bridge from the original order/scale-only navigation editor. It intentionally
     * copies no order: stable default grid coordinates are the only safe migration across
     * arbitrary outer panel sizes.
     */
    public void applyLegacy(@NonNull PanelElementConfigStore.Panel legacy) {
        for (Spec spec : SPECS) {
            PanelElementConfigStore.Element old = legacy.find(spec.id);
            if (old == null) continue;
            Element target = elements.get(spec.id);
            if (target == null) continue;
            target.enabled = old.enabled;
            target.scalePercent = clamp(old.scalePercent, MIN_SCALE, MAX_SCALE);
        }
        normalize();
    }

    /** New built-ins remain hidden in an existing hand-tuned grid after an application update. */
    void appendMissingDisabled(@NonNull Set<String> restoredIds) {
        for (Spec spec : SPECS) {
            if (!restoredIds.contains(spec.id)) elements.get(spec.id).enabled = false;
        }
    }

    @NonNull
    public NavigationPanelConfig copy() {
        NavigationPanelConfig value = new NavigationPanelConfig();
        value.gridColumns = gridColumns;
        value.gridRows = gridRows;
        value.elements.clear();
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            value.elements.put(entry.getKey(), entry.getValue().copy());
        }
        return value;
    }

    public void normalize() {
        gridColumns = clamp(gridColumns, MIN_GRID_COLUMNS, MAX_GRID_COLUMNS);
        gridRows = clamp(gridRows, MIN_GRID_ROWS, MAX_GRID_ROWS);
        for (Spec spec : SPECS) {
            if (!elements.containsKey(spec.id)) {
                elements.put(spec.id, new Element(spec.id, spec.enabledByDefault, 100,
                        spec.defaultColumn, spec.defaultRow,
                        spec.defaultColumnSpan, spec.defaultRowSpan));
            }
            Element element = elements.get(spec.id);
            element.scalePercent = clamp(element.scalePercent, MIN_SCALE, MAX_SCALE);
            normalizePlacement(element);
        }
        elements.keySet().retainAll(knownIds());
        resolveEnabledOverlaps();
    }

    static boolean overlaps(@NonNull Element first, @NonNull Element second) {
        return first.column < second.column + second.columnSpan
                && first.column + first.columnSpan > second.column
                && first.row < second.row + second.rowSpan
                && first.row + first.rowSpan > second.row;
    }

    private void resolveEnabledOverlaps() {
        ArrayList<Element> accepted = new ArrayList<>();
        for (Element element : elements.values()) {
            if (!element.enabled) continue;
            boolean collision = false;
            for (Element other : accepted) {
                if (overlaps(element, other)) {
                    collision = true;
                    break;
                }
            }
            if (collision) {
                int[] free = nearestFree(element, element.column, element.row);
                if (free == null) {
                    element.enabled = false;
                    continue;
                }
                element.column = free[0];
                element.row = free[1];
            }
            accepted.add(element);
        }
    }

    private boolean displaceCollisions(@NonNull Element anchor,
                                       int preferredColumn, int preferredRow) {
        for (Element candidate : elements.values()) {
            if (!candidate.enabled || candidate == anchor || !overlaps(anchor, candidate)) {
                continue;
            }
            int[] free = nearestFree(candidate, preferredColumn, preferredRow);
            if (free == null) return false;
            candidate.column = free[0];
            candidate.row = free[1];
        }
        ArrayList<Element> enabled = new ArrayList<>(enabledElements());
        for (int first = 0; first < enabled.size(); first++) {
            for (int second = first + 1; second < enabled.size(); second++) {
                if (overlaps(enabled.get(first), enabled.get(second))) return false;
            }
        }
        return true;
    }

    @Nullable
    private int[] nearestFree(@NonNull Element moving, int preferredColumn, int preferredRow) {
        int maximumColumn = gridColumns - moving.columnSpan;
        int maximumRow = gridRows - moving.rowSpan;
        int bestColumn = -1;
        int bestRow = -1;
        int bestDistance = Integer.MAX_VALUE;
        int originalColumn = moving.column;
        int originalRow = moving.row;
        for (int row = 0; row <= maximumRow; row++) {
            for (int column = 0; column <= maximumColumn; column++) {
                moving.column = column;
                moving.row = row;
                if (!isFree(moving)) continue;
                int distance = Math.abs(column - preferredColumn)
                        + Math.abs(row - preferredRow);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestColumn = column;
                    bestRow = row;
                }
            }
        }
        moving.column = originalColumn;
        moving.row = originalRow;
        return bestColumn < 0 ? null : new int[] {bestColumn, bestRow};
    }

    private boolean isFree(@NonNull Element moving) {
        for (Element other : elements.values()) {
            if (other == moving || !other.enabled) continue;
            if (overlaps(moving, other)) return false;
        }
        return true;
    }

    private void normalizePlacement(@NonNull Element element) {
        element.columnSpan = clamp(element.columnSpan, 1, gridColumns);
        element.rowSpan = clamp(element.rowSpan, 1, gridRows);
        element.column = clamp(element.column, 0, gridColumns - element.columnSpan);
        element.row = clamp(element.row, 0, gridRows - element.rowSpan);
    }

    @NonNull
    private LinkedHashMap<String, Element> snapshotElements() {
        LinkedHashMap<String, Element> result = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            result.put(entry.getKey(), entry.getValue().copy());
        }
        return result;
    }

    private void restoreElements(@NonNull LinkedHashMap<String, Element> snapshot) {
        elements.clear();
        for (Map.Entry<String, Element> entry : snapshot.entrySet()) {
            elements.put(entry.getKey(), entry.getValue().copy());
        }
    }

    private static int scaledStart(int oldStart, int oldCount, int newCount, int span) {
        int oldMaximum = Math.max(0, oldCount - span);
        int newMaximum = Math.max(0, newCount - span);
        if (oldMaximum == 0 || newMaximum == 0) return 0;
        return clamp(Math.round(oldStart * newMaximum / (float) oldMaximum), 0, newMaximum);
    }

    @Nullable
    private static int[] nearestFree(@NonNull boolean[][] occupied, int preferredColumn,
                                     int preferredRow, int columnSpan, int rowSpan) {
        int rows = occupied.length;
        int columns = rows == 0 ? 0 : occupied[0].length;
        int maximumColumn = columns - columnSpan;
        int maximumRow = rows - rowSpan;
        int bestColumn = -1;
        int bestRow = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int row = 0; row <= maximumRow; row++) {
            for (int column = 0; column <= maximumColumn; column++) {
                if (!isFree(occupied, column, row, columnSpan, rowSpan)) continue;
                int distance = Math.abs(column - preferredColumn)
                        + Math.abs(row - preferredRow);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestColumn = column;
                    bestRow = row;
                }
            }
        }
        return bestColumn < 0 ? null : new int[] {bestColumn, bestRow};
    }

    private static boolean isFree(@NonNull boolean[][] occupied, int column, int row,
                                  int columnSpan, int rowSpan) {
        for (int y = row; y < row + rowSpan; y++) {
            for (int x = column; x < column + columnSpan; x++) {
                if (occupied[y][x]) return false;
            }
        }
        return true;
    }

    private static void occupy(@NonNull boolean[][] occupied, @NonNull Element element) {
        for (int y = element.row; y < element.row + element.rowSpan; y++) {
            for (int x = element.column; x < element.column + element.columnSpan; x++) {
                occupied[y][x] = true;
            }
        }
    }

    @NonNull
    private static Set<String> knownIds() {
        HashSet<String> result = new HashSet<>();
        for (Spec spec : SPECS) result.add(spec.id);
        return result;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
