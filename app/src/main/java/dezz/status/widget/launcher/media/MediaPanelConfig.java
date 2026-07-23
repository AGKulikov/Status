/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persisted, connector-independent composition of the HOME media panel. */
public final class MediaPanelConfig {
    public static final String ARTWORK = "media.artwork";
    public static final String TITLE = "media.title";
    public static final String ARTIST = "media.artist";
    public static final String ALBUM = "media.album";
    public static final String APPLICATION = "media.application";
    public static final String PROGRESS = "media.progress";
    public static final String PREVIOUS = "media.previous";
    public static final String PLAY_PAUSE = "media.play_pause";
    public static final String NEXT = "media.next";
    public static final String VOLUME = "media.volume";

    public static final class Spec {
        @NonNull public final String id;
        @NonNull public final String label;
        public final int baseWidthDp;
        public final int baseHeightDp;
        public final int defaultColumn;
        public final int defaultRow;
        public final int defaultColumnSpan;
        public final int defaultRowSpan;

        private Spec(@NonNull String id, @NonNull String label, int width, int height,
                     int column, int row, int columnSpan, int rowSpan) {
            this.id = id;
            this.label = label;
            this.baseWidthDp = width;
            this.baseHeightDp = height;
            this.defaultColumn = column;
            this.defaultRow = row;
            this.defaultColumnSpan = columnSpan;
            this.defaultRowSpan = rowSpan;
        }
    }

    public static final class Element {
        @NonNull public final String id;
        public boolean enabled;
        public int order;
        /** Size of text/icon inside the cell. The occupied area is controlled by spans. */
        public int scalePercent;
        /** Scroll overflowing title/artist/album text; short text remains stationary. */
        public boolean marqueeEnabled;
        public int column;
        public int row;
        public int columnSpan;
        public int rowSpan;

        private Element(@NonNull String id, boolean enabled, int order, int scalePercent,
                        boolean marqueeEnabled, int column, int row,
                        int columnSpan, int rowSpan) {
            this.id = id;
            this.enabled = enabled;
            this.order = order;
            this.scalePercent = scalePercent;
            this.marqueeEnabled = marqueeEnabled;
            this.column = column;
            this.row = row;
            this.columnSpan = columnSpan;
            this.rowSpan = rowSpan;
        }

        @NonNull private Element copy() {
            return new Element(id, enabled, order, scalePercent, marqueeEnabled,
                    column, row, columnSpan, rowSpan);
        }
    }

    /** Defaults preserve every layout saved before the grid became user-configurable. */
    public static final int DEFAULT_GRID_COLUMNS = 12;
    public static final int DEFAULT_GRID_ROWS = 6;
    public static final int MIN_GRID_COLUMNS = 1;
    public static final int MAX_GRID_COLUMNS = 24;
    public static final int MIN_GRID_ROWS = 1;
    public static final int MAX_GRID_ROWS = 16;

    /** Kept for source compatibility with older tests/downstream extensions. */
    @Deprecated public static final int GRID_COLUMNS = DEFAULT_GRID_COLUMNS;
    @Deprecated public static final int GRID_ROWS = DEFAULT_GRID_ROWS;

    public static final List<Spec> SPECS = Collections.unmodifiableList(Arrays.asList(
            new Spec(ARTWORK, "Обложка", 112, 112, 0, 0, 3, 3),
            new Spec(TITLE, "Название", 230, 58, 3, 0, 5, 1),
            new Spec(ARTIST, "Исполнитель", 190, 48, 3, 1, 4, 1),
            new Spec(ALBUM, "Альбом", 180, 42, 3, 2, 4, 1),
            new Spec(APPLICATION, "Приложение", 150, 40, 0, 5, 3, 1),
            new Spec(PROGRESS, "Позиция и длительность", 240, 58, 3, 3, 5, 1),
            new Spec(PREVIOUS, "Предыдущий трек", 64, 64, 3, 4, 1, 1),
            new Spec(PLAY_PAUSE, "Играть / пауза", 72, 72, 4, 4, 1, 1),
            new Spec(NEXT, "Следующий трек", 64, 64, 5, 4, 1, 1),
            new Spec(VOLUME, "Громкость", 220, 58, 6, 4, 5, 1)));

    @NonNull public String backgroundColor = "#121923";
    public int backgroundAlpha = 150;
    public int cornerRadiusPx = 28;
    public int spacingPx = 10;
    public int contentPaddingPx = 12;
    @NonNull public String titleColor = "#FFFFFF";
    @NonNull public String secondaryColor = "#C7D0DD";
    @NonNull public String controlColor = "#FFFFFF";
    @NonNull public String glassColor = "#FFFFFF";
    public int glassAlpha = 30;
    @NonNull public String accentColor = "#86B7FF";
    @NonNull public String outlineColor = "#FFFFFF";
    public int outlineAlpha = 42;
    public int outlineWidthPx = 1;
    public int gridColumns = DEFAULT_GRID_COLUMNS;
    public int gridRows = DEFAULT_GRID_ROWS;

    private final LinkedHashMap<String, Element> elements = new LinkedHashMap<>();

    public MediaPanelConfig() {
        for (int index = 0; index < SPECS.size(); index++) {
            Spec spec = SPECS.get(index);
            elements.put(spec.id, new Element(spec.id, true, index, 100,
                    supportsMarquee(spec.id),
                    spec.defaultColumn, spec.defaultRow,
                    spec.defaultColumnSpan, spec.defaultRowSpan));
        }
    }

    @NonNull
    public Element element(@NonNull String id) {
        Element value = elements.get(id);
        if (value != null) return value;
        // Public callers use stable built-ins; returning a detached disabled value is safer than
        // crashing HOME if an imported future schema contains an unknown id.
        return new Element(id, false, SPECS.size(), 100, false, 0, 0, 1, 1);
    }

    @NonNull
    public List<Element> orderedElements() {
        ArrayList<Element> result = new ArrayList<>(elements.values());
        result.sort(Comparator.comparingInt(value -> value.order));
        return Collections.unmodifiableList(result);
    }

    /** Returns the effective state; enabling can be rejected when the grid has no free slot. */
    public boolean setEnabled(@NonNull String id, boolean enabled) {
        Element value = elements.get(id);
        if (value == null) return false;
        if (!enabled) {
            value.enabled = false;
            return false;
        }
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        value.enabled = true;
        if (!displaceCollisions(value, value.column, value.row)) {
            restoreElements(snapshot);
            return false;
        }
        return true;
    }

    public void setScale(@NonNull String id, int scalePercent) {
        Element value = elements.get(id);
        if (value != null) value.scalePercent = scalePercent;
    }

    public void setMarqueeEnabled(@NonNull String id, boolean enabled) {
        Element value = elements.get(id);
        if (value != null && supportsMarquee(id)) value.marqueeEnabled = enabled;
    }

    public boolean setPosition(@NonNull String id, int column, int row) {
        Element value = elements.get(id);
        if (value == null) return false;
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        int preferredColumn = value.column;
        int preferredRow = value.row;
        value.column = column;
        value.row = row;
        normalizePlacement(value);
        if (!displaceCollisions(value, preferredColumn, preferredRow)) {
            restoreElements(snapshot);
            return false;
        }
        return true;
    }

    public boolean setSpan(@NonNull String id, int columnSpan, int rowSpan) {
        Element value = elements.get(id);
        if (value == null) return false;
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        int preferredColumn = value.column;
        int preferredRow = value.row;
        value.columnSpan = columnSpan;
        value.rowSpan = rowSpan;
        normalizePlacement(value);
        if (!displaceCollisions(value, preferredColumn, preferredRow)) {
            restoreElements(snapshot);
            return false;
        }
        return true;
    }

    /**
     * Changes the editor/runtime grid without allowing saved elements to overlap. Existing spans
     * remain cell based, so adding columns/rows really creates smaller, more precise cells. The
     * closest valid positions are chosen in visual order; an impossibly small grid is rejected
     * and the previous layout is left untouched.
     */
    public boolean setGridSize(int columns, int rows) {
        int selectedColumns = clamp(columns, MIN_GRID_COLUMNS, MAX_GRID_COLUMNS);
        int selectedRows = clamp(rows, MIN_GRID_ROWS, MAX_GRID_ROWS);
        if (selectedColumns == gridColumns && selectedRows == gridRows) return true;

        int oldColumns = gridColumns;
        int oldRows = gridRows;
        LinkedHashMap<String, Element> snapshot = snapshotElements();
        gridColumns = selectedColumns;
        gridRows = selectedRows;
        boolean[][] occupied = new boolean[gridRows][gridColumns];

        for (Element element : orderedElements()) {
            int oldColumn = element.column;
            int oldRow = element.row;
            element.columnSpan = clamp(element.columnSpan, 1, gridColumns);
            element.rowSpan = clamp(element.rowSpan, 1, gridRows);
            int preferredColumn = scaledStart(oldColumn, oldColumns, gridColumns,
                    element.columnSpan);
            int preferredRow = scaledStart(oldRow, oldRows, gridRows, element.rowSpan);
            if (!element.enabled) {
                element.column = preferredColumn;
                element.row = preferredRow;
                continue;
            }
            int[] slot = nearestFree(occupied, preferredColumn, preferredRow,
                    element.columnSpan, element.rowSpan);
            if (slot == null) {
                gridColumns = oldColumns;
                gridRows = oldRows;
                restoreElements(snapshot);
                return false;
            }
            element.column = slot[0];
            element.row = slot[1];
            occupy(occupied, element.column, element.row,
                    element.columnSpan, element.rowSpan);
        }
        return true;
    }

    /** Appends built-ins introduced after a saved layout without unexpectedly enabling them. */
    void appendMissingDisabled(@NonNull Set<String> restoredIds, int maximumOrder) {
        int order = maximumOrder;
        for (Spec spec : SPECS) {
            if (restoredIds.contains(spec.id)) continue;
            Element element = elements.get(spec.id);
            if (element == null) continue;
            element.order = ++order;
            element.enabled = false;
        }
    }

    /** Moves an element one visual slot and keeps a dense, deterministic order. */
    public void move(@NonNull String id, int direction) {
        if (direction == 0) return;
        ArrayList<Element> ordered = new ArrayList<>(orderedElements());
        int from = -1;
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).id.equals(id)) { from = index; break; }
        }
        if (from < 0) return;
        int to = Math.max(0, Math.min(ordered.size() - 1, from + direction));
        if (from == to) return;
        Element moving = ordered.remove(from);
        ordered.add(to, moving);
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
    }

    @NonNull
    public MediaPanelConfig copy() {
        MediaPanelConfig value = new MediaPanelConfig();
        value.backgroundColor = backgroundColor;
        value.backgroundAlpha = backgroundAlpha;
        value.cornerRadiusPx = cornerRadiusPx;
        value.spacingPx = spacingPx;
        value.contentPaddingPx = contentPaddingPx;
        value.titleColor = titleColor;
        value.secondaryColor = secondaryColor;
        value.controlColor = controlColor;
        value.glassColor = glassColor;
        value.glassAlpha = glassAlpha;
        value.accentColor = accentColor;
        value.outlineColor = outlineColor;
        value.outlineAlpha = outlineAlpha;
        value.outlineWidthPx = outlineWidthPx;
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
        backgroundAlpha = clamp(backgroundAlpha, 0, 255);
        cornerRadiusPx = clamp(cornerRadiusPx, 0, 96);
        spacingPx = clamp(spacingPx, 0, 48);
        contentPaddingPx = clamp(contentPaddingPx, 0, 64);
        glassAlpha = clamp(glassAlpha, 0, 255);
        outlineAlpha = clamp(outlineAlpha, 0, 255);
        outlineWidthPx = clamp(outlineWidthPx, 0, 8);
        if (!isHexColor(backgroundColor)) backgroundColor = "#121923";
        if (!isHexColor(titleColor)) titleColor = "#FFFFFF";
        if (!isHexColor(secondaryColor)) secondaryColor = "#C7D0DD";
        if (!isHexColor(controlColor)) controlColor = "#FFFFFF";
        if (!isHexColor(glassColor)) glassColor = "#FFFFFF";
        if (!isHexColor(accentColor)) accentColor = "#86B7FF";
        if (!isHexColor(outlineColor)) outlineColor = "#FFFFFF";

        for (int index = 0; index < SPECS.size(); index++) {
            Spec spec = SPECS.get(index);
            if (!elements.containsKey(spec.id)) {
                elements.put(spec.id, new Element(spec.id, true, index, 100,
                        supportsMarquee(spec.id),
                        spec.defaultColumn, spec.defaultRow,
                        spec.defaultColumnSpan, spec.defaultRowSpan));
            }
            Element element = elements.get(spec.id);
            if (element != null) {
                element.scalePercent = clamp(element.scalePercent, 45, 220);
                if (!supportsMarquee(element.id)) element.marqueeEnabled = false;
                normalizePlacement(element);
            }
        }
        ArrayList<Element> ordered = new ArrayList<>(elements.values());
        ordered.removeIf(value -> spec(value.id) == null);
        ordered.sort(Comparator.comparingInt(value -> value.order));
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).order = index;
    }

    @NonNull
    public Map<String, Element> elementMap() {
        LinkedHashMap<String, Element> result = new LinkedHashMap<>();
        for (Map.Entry<String, Element> entry : elements.entrySet()) {
            result.put(entry.getKey(), entry.getValue().copy());
        }
        return Collections.unmodifiableMap(result);
    }

    public static Spec spec(@NonNull String id) {
        for (Spec value : SPECS) if (value.id.equals(id)) return value;
        return null;
    }

    public static boolean supportsMarquee(@NonNull String id) {
        return TITLE.equals(id) || ARTIST.equals(id) || ALBUM.equals(id);
    }

    private static boolean isHexColor(String value) {
        return value != null && value.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?");
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void normalizePlacement(@NonNull Element element) {
        element.columnSpan = clamp(element.columnSpan, 1, gridColumns);
        element.rowSpan = clamp(element.rowSpan, 1, gridRows);
        element.column = clamp(element.column, 0, gridColumns - element.columnSpan);
        element.row = clamp(element.row, 0, gridRows - element.rowSpan);
    }

    /**
     * Keeps a drag/resize deterministic: overlapping enabled elements are displaced to the
     * nearest free slot, preferring the moving element's previous slot. A completely full grid
     * restores the last valid layout instead of silently stacking controls.
     */
    private boolean displaceCollisions(@NonNull Element anchor,
                                       int preferredColumn, int preferredRow) {
        ArrayList<Element> ordered = new ArrayList<>(orderedElements());
        for (Element candidate : ordered) {
            if (!candidate.enabled || candidate == anchor || !overlaps(anchor, candidate)) continue;
            int[] free = nearestFree(candidate, preferredColumn, preferredRow);
            if (free == null) return false;
            candidate.column = free[0];
            candidate.row = free[1];
        }
        // A displaced large element can touch more than the original anchor. Validate the final
        // arrangement rather than relying on processing order.
        for (int first = 0; first < ordered.size(); first++) {
            Element a = ordered.get(first);
            if (!a.enabled) continue;
            for (int second = first + 1; second < ordered.size(); second++) {
                Element b = ordered.get(second);
                if (b.enabled && overlaps(a, b)) return false;
            }
        }
        return true;
    }

    private int[] nearestFree(@NonNull Element moving, int preferredColumn, int preferredRow) {
        int maximumColumn = gridColumns - moving.columnSpan;
        int maximumRow = gridRows - moving.rowSpan;
        int bestColumn = -1;
        int bestRow = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int row = 0; row <= maximumRow; row++) {
            for (int column = 0; column <= maximumColumn; column++) {
                moving.column = column;
                moving.row = row;
                if (!isFree(moving)) continue;
                int distance = Math.abs(column - preferredColumn) + Math.abs(row - preferredRow);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestColumn = column;
                    bestRow = row;
                }
            }
        }
        if (bestColumn < 0) return null;
        return new int[] {bestColumn, bestRow};
    }

    private boolean isFree(@NonNull Element moving) {
        for (Element other : elements.values()) {
            if (other == moving || !other.enabled) continue;
            if (overlaps(moving, other)) return false;
        }
        return true;
    }

    static boolean overlaps(@NonNull Element first, @NonNull Element second) {
        return first.column < second.column + second.columnSpan
                && first.column + first.columnSpan > second.column
                && first.row < second.row + second.rowSpan
                && first.row + first.rowSpan > second.row;
    }

    @NonNull private LinkedHashMap<String, Element> snapshotElements() {
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
                int distance = Math.abs(column - preferredColumn) + Math.abs(row - preferredRow);
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

    private static void occupy(@NonNull boolean[][] occupied, int column, int row,
                               int columnSpan, int rowSpan) {
        for (int y = row; y < row + rowSpan; y++) {
            for (int x = column; x < column + columnSpan; x++) occupied[y][x] = true;
        }
    }
}
