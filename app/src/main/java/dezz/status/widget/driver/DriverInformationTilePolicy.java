/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import androidx.annotation.NonNull;

import dezz.status.widget.launcher.information.InformationPanelConfig;

/** Adapts the shared read-only information model to the narrow driver rail. */
public final class DriverInformationTilePolicy {
    private DriverInformationTilePolicy() {
    }

    @NonNull
    public static InformationPanelConfig vertical(
            @NonNull InformationPanelConfig source) {
        InformationPanelConfig value = source.copy();
        value.columns = 1;
        value.backgroundAlpha = 0;
        value.contentPaddingPx = 0;
        int defaultGap = value.gapPx;
        value.gapPx = 0;
        int row = 0;
        for (InformationPanelConfig.Item item : value.mutableItems()) {
            if (item.gapBeforePx < 0) item.gapBeforePx = defaultGap;
            item.column = 0;
            item.row = item.enabled ? row++ : row;
            item.columnSpan = 1;
            item.rowSpan = 1;
        }
        value.rows = Math.max(1, row);
        value.normalize();
        return value;
    }

    public static int enabledCount(@NonNull InformationPanelConfig source) {
        int count = 0;
        for (InformationPanelConfig.Item item : source.mutableItems()) {
            if (item.enabled) count++;
        }
        return count;
    }
}
