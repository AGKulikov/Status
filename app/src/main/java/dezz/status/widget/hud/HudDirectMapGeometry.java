/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Shared physical geometry for the one direct-Surface HUD map element. */
final class HudDirectMapGeometry {
    private HudDirectMapGeometry() {}

    @Nullable
    static HudElementConfig find(@NonNull HudPanelConfig config) {
        for (HudElementConfig item : config.drawingOrder()) {
            if (item.enabled && item.type == HudElementType.NAV_MAP
                    && HudElementConfig.DIRECT_MAP_RENDERER.equals(
                    item.options.optString("renderer", ""))) {
                return item;
            }
        }
        return null;
    }

    @NonNull
    static RectF bounds(@NonNull HudPanelConfig config,
                        @NonNull HudElementConfig item,
                        boolean localHudViewport) {
        float cellWidth = HudViewportPolicy.SAFE_WIDTH / (float) config.gridColumns;
        float cellHeight = HudViewportPolicy.SAFE_HEIGHT / (float) config.gridRows;
        float originX = localHudViewport ? 0f : HudViewportPolicy.SAFE_LEFT;
        float originY = localHudViewport ? 0f : HudViewportPolicy.SAFE_TOP;
        float fineX = (float) item.options.optDouble("fineX", 0d);
        float fineY = (float) item.options.optDouble("fineY", 0d);
        float left = originX + item.x * cellWidth + fineX;
        float top = originY + item.y * cellHeight + fineY;
        return new RectF(left, top, left + item.width * cellWidth,
                top + item.height * cellHeight);
    }
}
