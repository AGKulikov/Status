/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

/** Original drawing of the observed MConfig 48 style; no APK assets/code are bundled. */
final class HudDriveModeBanner {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint.FontMetrics metrics = new Paint.FontMetrics();
    private final RectF pill = new RectF();

    void draw(Canvas canvas, RectF viewport, String label, float density, float scaledDensity) {
        if (label.isEmpty() || viewport.isEmpty()) return;
        // MConfig: 10sp bold black, white rounded pill, 10dp horizontal / 0dp vertical padding.
        text.setTextSize(10f * scaledDensity);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.BLACK);
        text.getFontMetrics(metrics);
        float padding = Math.min(10f * density, viewport.width() / 4f);
        CharSequence shown = TextUtils.ellipsize(label, text,
                Math.max(0f, viewport.width() - 2f * padding), TextUtils.TruncateAt.END);
        float width = text.measureText(shown, 0, shown.length()) + 2f * padding;
        pill.set(viewport.centerX() - width / 2f, viewport.top,
                viewport.centerX() + width / 2f,
                Math.min(viewport.bottom, viewport.top + metrics.descent - metrics.ascent));
        int clip = canvas.save();
        canvas.clipRect(viewport);
        background.setColor(Color.WHITE);
        canvas.drawRoundRect(pill, 50f * density, 50f * density, background);
        canvas.drawText(shown, 0, shown.length(), pill.centerX(),
                pill.top - metrics.ascent, text);
        canvas.restoreToCount(clip);
    }
}
