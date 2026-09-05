/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

/** Android-9-compatible GPU alpha feather, confined to one map child's saveLayer. */
public final class MapEdgeFade {
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LinearGradient horizontal, vertical;
    private float cachedWidth, cachedHeight;
    private int cachedSize, cachedStrength;

    public MapEdgeFade() {
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    public int begin(Canvas canvas, RectF bounds, boolean enabled, int size, int strength) {
        if (!MapEdgeFadePolicy.enabled(enabled, bounds.width(), bounds.height(), size, strength))
            return -1;
        if (horizontal == null || cachedWidth != bounds.width() || cachedHeight != bounds.height()
                || cachedSize != size || cachedStrength != strength) {
            cachedWidth = bounds.width();
            cachedHeight = bounds.height();
            cachedSize = size;
            cachedStrength = strength;
            int edge = (MapEdgeFadePolicy.edgeAlpha(strength) << 24) | 0x00FFFFFF;
            int[] colors = {edge, Color.WHITE, Color.WHITE, edge};
            horizontal = new LinearGradient(0, 0, cachedWidth, 0, colors,
                    MapEdgeFadePolicy.stops(cachedWidth, size), Shader.TileMode.CLAMP);
            vertical = new LinearGradient(0, 0, 0, cachedHeight, colors,
                    MapEdgeFadePolicy.stops(cachedHeight, size), Shader.TileMode.CLAMP);
        }
        // TextureView participates directly in the window's GPU composition. No frame readback,
        // bitmap rescaling, RenderEffect (API 31), or re-creation of the producer Surface is needed.
        return canvas.saveLayer(bounds, null);
    }

    public void finish(Canvas canvas, int saveCount, RectF bounds) {
        if (saveCount < 0) return;
        canvas.translate(bounds.left, bounds.top);
        maskPaint.setShader(horizontal);
        canvas.drawRect(0, 0, bounds.width(), bounds.height(), maskPaint);
        maskPaint.setShader(vertical);
        canvas.drawRect(0, 0, bounds.width(), bounds.height(), maskPaint);
        canvas.restoreToCount(saveCount);
    }
}
