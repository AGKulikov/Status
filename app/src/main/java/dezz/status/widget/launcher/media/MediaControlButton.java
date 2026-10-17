/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher.media;

import android.content.Context;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ImageButton;

/** The visible icon owns a tap, not its potentially overlapping editor rectangle. */
public final class MediaControlButton extends ImageButton {
    private boolean projected;
    private boolean cancelled;

    public MediaControlButton(Context context) { super(context); }

    public boolean containsTouch(float x, float y, float scaleX, float scaleY, float slop) {
        Drawable drawable = getDrawable();
        if (drawable == null || !isEnabled()) return false;
        RectF bounds = new RectF(drawable.getBounds());
        if (bounds.isEmpty()) return false;
        getImageMatrix().mapRect(bounds);
        bounds.offset(getPaddingLeft(), getPaddingTop());
        float density = getResources().getDisplayMetrics().density;
        float sx = Math.max(.01f, Math.abs(scaleX));
        float sy = Math.max(.01f, Math.abs(scaleY));
        // Keep a usable 48dp target when the glyph is small, with only 8dp around larger glyphs.
        float dx = Math.max(8f * density / sx, (48f * density / sx - bounds.width()) / 2f);
        float dy = Math.max(8f * density / sy, (48f * density / sy - bounds.height()) / 2f);
        bounds.inset(-dx - slop / sx, -dy - slop / sy);
        return bounds.contains(x, y) && x >= -slop / sx && y >= -slop / sy
                && x < getWidth() + slop / sx && y < getHeight() + slop / sy;
    }

    /** HOME already tested the actual screen transform; do not test again at source scale. */
    public boolean dispatchProjectedTouchEvent(MotionEvent event) {
        projected = true;
        try { return dispatchTouchEvent(event); }
        finally { projected = false; }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (projected) return super.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancelled = false;
            if (!containsTouch(event.getX(), event.getY(), 1f, 1f, 0f)) return false;
        }
        if ((action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && !containsTouch(event.getX(), event.getY(), 1f, 1f,
                ViewConfiguration.get(getContext()).getScaledTouchSlop())) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            try { super.onTouchEvent(cancel); } finally { cancel.recycle(); }
            cancelled = true;
        }
        if (cancelled) return true;
        return super.onTouchEvent(event);
    }
}
