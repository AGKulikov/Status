package dezz.status.widget.phone;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class PhoneAppIconDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path;
    private final float viewportWidth;
    private final float viewportHeight;

    PhoneAppIconDrawable(Path path, float viewportWidth, float viewportHeight) {
        this.path = path;
        this.viewportWidth = Math.max(1f, viewportWidth);
        this.viewportHeight = Math.max(1f, viewportHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float scale = Math.min(bounds.width() / viewportWidth,
                bounds.height() / viewportHeight);
        float left = bounds.left + (bounds.width() - viewportWidth * scale) / 2f;
        float top = bounds.top + (bounds.height() - viewportHeight * scale) / 2f;
        int save = canvas.save();
        canvas.translate(left, top);
        canvas.scale(scale, scale);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
