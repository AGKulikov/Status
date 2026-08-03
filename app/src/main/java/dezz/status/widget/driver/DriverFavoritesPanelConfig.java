/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;

import java.util.UUID;

/** One independently addressable compact Favorites panel attached to a driver-rail button. */
public final class DriverFavoritesPanelConfig {
    public static final String DEFAULT_ID = "favorites_default";
    public static final int MIN_COLUMNS = 1;
    public static final int MAX_COLUMNS = 8;
    public static final int MIN_VISIBLE_ROWS = 1;
    public static final int MAX_VISIBLE_ROWS = 8;
    public static final int MIN_CELL_SIZE_PX = 56;
    public static final int MAX_CELL_SIZE_PX = 180;
    public static final int MAX_GAP_PX = 40;
    public static final int MAX_BORDER_WIDTH_PX = 12;

    @NonNull public String id = newId();
    @NonNull public String title = "Избранное";
    public int columns = 4;
    public int visibleRows = 3;
    public int cellSizePx = 96;
    public int gapPx = 8;
    public boolean borderEnabled = false;
    public int borderWidthPx = 1;
    @NonNull public String borderColor = "#55FFFFFF";

    @NonNull
    public DriverFavoritesPanelConfig copy() {
        DriverFavoritesPanelConfig value = new DriverFavoritesPanelConfig();
        value.id = id;
        value.title = title;
        value.columns = columns;
        value.visibleRows = visibleRows;
        value.cellSizePx = cellSizePx;
        value.gapPx = gapPx;
        value.borderEnabled = borderEnabled;
        value.borderWidthPx = borderWidthPx;
        value.borderColor = borderColor;
        return value;
    }

    @NonNull
    static String newId() {
        return "favorites_" + UUID.randomUUID().toString().replace("-", "");
    }
}
