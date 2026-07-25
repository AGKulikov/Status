/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Draws and operates one live panel child while its own frame uses global HOME coordinates. */
public final class LauncherGlobalElementProxyView extends View {
    public interface Source {
        @Nullable View resolve();
    }

    @NonNull private final Source source;

    public LauncherGlobalElementProxyView(@NonNull Context context,
                                          @NonNull Source source) {
        super(context);
        this.source = source;
        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
    }

    @Nullable
    public View sourceView() {
        return source.resolve();
    }

    public boolean sourceIsShown() {
        View value = sourceView();
        return value != null && value.isShown() && value.getWidth() > 0 && value.getHeight() > 0;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        View value = sourceView();
        if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0) return;
        float scaleX = getWidth() / (float) value.getWidth();
        float scaleY = getHeight() / (float) value.getHeight();
        int checkpoint = canvas.save();
        canvas.scale(scaleX, scaleY);
        value.draw(canvas);
        canvas.restoreToCount(checkpoint);
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        View value = sourceView();
        if (value == null || value.getWidth() <= 0 || value.getHeight() <= 0
                || getWidth() <= 0 || getHeight() <= 0) return false;
        MotionEvent forwarded = MotionEvent.obtain(event);
        forwarded.setLocation(
                event.getX() * value.getWidth() / getWidth(),
                event.getY() * value.getHeight() / getHeight());
        boolean handled;
        try {
            handled = value.dispatchTouchEvent(forwarded);
        } finally {
            forwarded.recycle();
        }
        if (!handled && event.getActionMasked() == MotionEvent.ACTION_UP) {
            performClickableAncestor(value);
        }
        invalidate();
        return true;
    }

    private static void performClickableAncestor(@NonNull View source) {
        ViewParent parent = source.getParent();
        while (parent instanceof View && !(parent instanceof LauncherElementFrame)) {
            View candidate = (View) parent;
            if (candidate.isClickable()) {
                candidate.performClick();
                return;
            }
            parent = candidate.getParent();
        }
    }
}
