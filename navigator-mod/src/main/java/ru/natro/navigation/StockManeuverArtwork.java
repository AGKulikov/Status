/* SPDX-License-Identifier: GPL-3.0-or-later */
package ru.natro.navigation;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.widget.ImageView;

/** Pixel-verified snapshots of the actual stock ImageView; no guessed maneuver graphics. */
final class StockManeuverArtwork {
    private Bitmap scratch;
    private Canvas canvas;
    private Bitmap publishedArtwork;
    private int revision;

    int revision() { return revision; }

    Bitmap capture(ImageView image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (image.getDrawable() == null || width <= 0 || height <= 0
                || width > 256 || height > 256 || (long) width * height > 65_536L) return null;
        try {
            if (scratch == null || scratch.getWidth() != width
                    || scratch.getHeight() != height) {
                scratch = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(scratch);
            }
            // A Drawable can change its pixels without changing identity, level or view size.
            // Redraw only the bounded stock icon, never the map or a whole display surface.
            scratch.eraseColor(0);
            int save = canvas.save();
            try { image.draw(canvas); }
            finally { canvas.restoreToCount(save); }
            if (publishedArtwork != null && !publishedArtwork.isRecycled()
                    && scratch.sameAs(publishedArtwork)) {
                return publishedArtwork;
            }
            // Published bitmaps may still be in asynchronous IPC. Never mutate/recycle them.
            Bitmap published = scratch.copy(Bitmap.Config.ARGB_8888, false);
            if (published == null) return null;
            publishedArtwork = published;
            revision++;
            return published;
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

}
