/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

public final class InnerStrokeGradientDrawable extends android.graphics.drawable.GradientDrawable {
    private float[] cornerRadii;
    private android.graphics.ColorFilter strokeColorFilter;
    private int strokeWidth;
    private final android.graphics.Paint strokePaint = new android.graphics.Paint(1);
    private int strokeColor = 0;
    private int drawableAlpha = 255;

    public InnerStrokeGradientDrawable() {
        this.strokePaint.setStyle(android.graphics.Paint.Style.STROKE);
    }

    @Override // android.graphics.drawable.GradientDrawable
    public void setCornerRadii(float[] fArr) {
        this.cornerRadii = fArr == null ? null : (float[]) fArr.clone();
        super.setCornerRadii(fArr);
    }

    @Override // android.graphics.drawable.GradientDrawable
    public void setStroke(int i, int i2) {
        this.strokeWidth = java.lang.Math.max(0, i);
        this.strokeColor = i2;
        invalidateSelf();
    }

    @Override // android.graphics.drawable.GradientDrawable, android.graphics.drawable.Drawable
    public void setAlpha(int i) {
        this.drawableAlpha = java.lang.Math.max(0, java.lang.Math.min(255, i));
        super.setAlpha(i);
        invalidateSelf();
    }

    @Override // android.graphics.drawable.GradientDrawable, android.graphics.drawable.Drawable
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        this.strokeColorFilter = colorFilter;
        super.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override // android.graphics.drawable.GradientDrawable, android.graphics.drawable.Drawable
    public void draw(android.graphics.Canvas canvas) {
        super.draw(canvas);
        if (this.strokeWidth <= 0 || android.graphics.Color.alpha(this.strokeColor) == 0) {
            return;
        }
        android.graphics.Rect bounds = getBounds();
        float f = this.strokeWidth / 2.0f;
        float f2 = 2.0f * f;
        if (bounds.width() <= f2 || bounds.height() <= f2) {
            return;
        }
        android.graphics.RectF rectF = new android.graphics.RectF(bounds.left + f, bounds.top + f, bounds.right - f, bounds.bottom - f);
        float[] fArrInsetRadii = insetRadii(this.cornerRadii, f);
        android.graphics.Path path = new android.graphics.Path();
        if (fArrInsetRadii == null) {
            path.addRect(rectF, android.graphics.Path.Direction.CW);
        } else {
            path.addRoundRect(rectF, fArrInsetRadii, android.graphics.Path.Direction.CW);
        }
        this.strokePaint.setStrokeWidth(this.strokeWidth);
        this.strokePaint.setColor(this.strokeColor);
        this.strokePaint.setAlpha((android.graphics.Color.alpha(this.strokeColor) * this.drawableAlpha) / 255);
        this.strokePaint.setColorFilter(this.strokeColorFilter);
        canvas.drawPath(path, this.strokePaint);
    }

    private static float[] insetRadii(float[] fArr, float f) {
        if (fArr == null || fArr.length != 8) {
            return null;
        }
        float[] fArr2 = (float[]) fArr.clone();
        for (int i = 0; i < fArr2.length; i++) {
            fArr2[i] = java.lang.Math.max(0.0f, fArr2[i] - f);
        }
        return fArr2;
    }
}
