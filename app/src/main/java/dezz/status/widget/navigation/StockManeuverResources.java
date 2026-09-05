/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.navigation;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.LruCache;
import java.util.List;

/** Draw only resources from the installed, signature-matched Navigator at target pixel size. */
public final class StockManeuverResources {
    private static final String PACKAGE = "ru.yandex.yandexnavi";
    private final Context context;
    private final LruCache<String, Drawable> cache = new LruCache<>(128);
    private Resources resources;
    private long checkedAt, updatedAt;
    private int configuration;
    public StockManeuverResources(Context context) { this.context = context.getApplicationContext(); }

    private Drawable get(String name) {
        if (name.isEmpty() || !name.matches("[a-z0-9_]{1,160}")) return null;
        try {
            long now = SystemClock.elapsedRealtime();
            int config = context.getResources().getConfiguration().hashCode();
            if (resources == null || now - checkedAt >= 1000 || configuration != config) {
                PackageManager manager = context.getPackageManager();
                PackageInfo info = manager.getPackageInfo(PACKAGE, 0);
                if (info.getLongVersionCode() != 739564630L || manager.checkSignatures(
                        context.getPackageName(), PACKAGE) != PackageManager.SIGNATURE_MATCH) {
                    cache.evictAll(); resources = null; return null;
                }
                if (resources == null || updatedAt != info.lastUpdateTime || configuration != config) {
                    cache.evictAll(); resources = manager.getResourcesForApplication(PACKAGE);
                    updatedAt = info.lastUpdateTime; configuration = config;
                }
                checkedAt = now;
            }
            Drawable value = cache.get(name);
            if (value == null) {
                int id = resources.getIdentifier(name, "drawable", PACKAGE);
                if (id == 0) return null;
                value = resources.getDrawable(id, null).mutate();
                cache.put(name, value);
            }
            return value;
        } catch (Exception unavailable) { cache.evictAll(); resources = null; return null; }
    }
    public boolean available(String name) { return get(name) != null; }
    public boolean available(StockManeuverCardState state) {
        if (!state.visible) return false;
        if (state.imageVisible && !available(state.image)) return false;
        if (!state.auxiliaryImage.isEmpty() && !available(state.auxiliaryImage)) return false;
        for (StockManeuverCardState.Sign sign : state.signs)
            if (!sign.image.isEmpty() && !available(sign.image)) return false;
        for (StockManeuverCardState.Sign sign : state.followingSigns)
            if (!sign.image.isEmpty() && !available(sign.image)) return false;
        return availableLanes(state.lanes) && availableLanes(state.auxiliaryLanes);
    }
    private boolean availableLanes(List<StockManeuverCardState.Lane> lanes) {
        for (StockManeuverCardState.Lane lane : lanes) {
            for (String layer : lane.secondary) if (!available(layer)) return false;
            for (String layer : new String[]{lane.highlighted, lane.kind, lane.crop})
                if (!layer.isEmpty() && !available(layer)) return false;
        }
        return true;
    }
    public boolean draw(Canvas canvas, String name, RectF bounds, int alpha) {
        return draw(canvas, name, bounds, alpha, null, true);
    }
    public boolean draw(Canvas canvas, String name, RectF bounds, int alpha, Integer tint, boolean fit) {
        Drawable value = get(name);
        if (value == null || bounds.isEmpty()) return false;
        RectF target = new RectF(bounds);
        if (fit && value.getIntrinsicWidth() > 0 && value.getIntrinsicHeight() > 0) {
            float scale = Math.min(bounds.width() / value.getIntrinsicWidth(), bounds.height() / value.getIntrinsicHeight());
            float width = value.getIntrinsicWidth() * scale, height = value.getIntrinsicHeight() * scale;
            target.set(bounds.centerX() - width / 2, bounds.centerY() - height / 2,
                    bounds.centerX() + width / 2, bounds.centerY() + height / 2);
        }
        int save = canvas.save();
        try {
            canvas.clipRect(bounds);
            value.setAlpha(Math.max(0, Math.min(255, alpha)));
            if (tint == null) value.clearColorFilter();
            else value.setColorFilter(tint, PorterDuff.Mode.SRC_ATOP);
            value.setBounds(Math.round(target.left), Math.round(target.top), Math.round(target.right), Math.round(target.bottom));
            value.draw(canvas);
            return true;
        } finally { value.clearColorFilter(); value.setAlpha(255); canvas.restoreToCount(save); }
    }
    /** Composition of original vector layers; geometry comes from the original lane containers. */
    public void drawLanes(Canvas canvas, List<StockManeuverCardState.Lane> lanes, RectF bounds, int alpha) {
        if (lanes.isEmpty() || bounds.isEmpty() || !availableLanes(lanes)) return;
        float width = 0, height = 0;
        for (StockManeuverCardState.Lane lane : lanes) {
            width += lane.width + lane.left + lane.right; height = Math.max(height, lane.height);
        }
        if (width <= 0 || height <= 0) return;
        float scale = Math.min(bounds.width() / width, bounds.height() / height);
        float x = bounds.centerX() - width * scale / 2;
        int all = canvas.saveLayerAlpha(bounds, Math.max(0, Math.min(255, alpha)));
        try {
            canvas.clipRect(bounds);
            for (StockManeuverCardState.Lane lane : lanes) {
                x += lane.left * scale;
                RectF box = new RectF(x, bounds.centerY() - lane.height * scale / 2,
                        x + lane.width * scale, bounds.centerY() + lane.height * scale / 2);
                int save = canvas.saveLayer(box, null);
                try {
                    for (String resource : lane.secondary) draw(canvas, resource, box, 255, null, false);
                    canvas.clipRect(box);
                    canvas.drawColor(0x66FFFFFF, PorterDuff.Mode.MULTIPLY);
                    if (!lane.highlighted.isEmpty()) draw(canvas, lane.highlighted, box, 255, null, false);
                    if (!lane.kind.isEmpty()) {
                        if (!lane.crop.isEmpty()) {
                            Paint erase = new Paint();
                            erase.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                            int mask = canvas.saveLayer(box, erase);
                            draw(canvas, lane.crop, box, 255, null, false);
                            canvas.restoreToCount(mask);
                        }
                        // Stock inactive lane kind is white at 40% alpha (SRC_IN).
                        draw(canvas, lane.kind, box, lane.highlighted.isEmpty() ? 102 : 255,
                                lane.highlighted.isEmpty() ? 0xFFFFFFFF : null, false);
                    }
                } finally { canvas.restoreToCount(save); }
                x += (lane.width + lane.right) * scale;
            }
        } finally { canvas.restoreToCount(all); }
    }
}
