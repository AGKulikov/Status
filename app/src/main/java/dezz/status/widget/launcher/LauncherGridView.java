/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import androidx.annotation.NonNull;

/** Lightweight editor grid; it is never part of the normal launcher presentation. */
public final class LauncherGridView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int stepPx = 20;

    public LauncherGridView(@NonNull Context context) {
        super(context);
        paint.setColor(Color.argb(90, 65, 145, 255));
        paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        setClickable(false);
        setFocusable(false);
    }

    public void setStepPx(int value) {
        stepPx = Math.max(4, value);
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        for (int x = stepPx; x < getWidth(); x += stepPx) {
            canvas.drawLine(x, 0, x, getHeight(), paint);
        }
        for (int y = stepPx; y < getHeight(); y += stepPx) {
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
    }
}
