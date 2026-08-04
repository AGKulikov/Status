/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.driver;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

/**
 * FrameLayout that reapplies one child's exact centre on every layout pass.
 *
 * <p>The ECARX Android 9 FrameLayout occasionally retains the child's previous top after only the
 * parent height changes. Gravity.CENTER is therefore insufficient for an adjustable-height driver
 * button. This host measures and lays out the climate canvas from the current physical rectangle
 * every time, while still respecting deliberately asymmetric button padding.</p>
 */
public final class DriverExactCenterFrameLayout extends FrameLayout {
    private View exactlyCenteredChild;
    private int centeredWidthPx;
    private int centeredHeightPx;

    public DriverExactCenterFrameLayout(@NonNull Context context) {
        super(context);
    }

    public void addExactlyCentered(@NonNull View child, int widthPx, int heightPx) {
        exactlyCenteredChild = child;
        centeredWidthPx = Math.max(1, widthPx);
        centeredHeightPx = Math.max(1, heightPx);
        addView(child, new LayoutParams(centeredWidthPx, centeredHeightPx));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        measureExactlyCenteredChild();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        View child = exactlyCenteredChild;
        if (child == null || child.getVisibility() == GONE) return;
        measureExactlyCenteredChild();
        int contentLeft = getPaddingLeft();
        int contentTop = getPaddingTop();
        int contentRight = Math.max(contentLeft, right - left - getPaddingRight());
        int contentBottom = Math.max(contentTop, bottom - top - getPaddingBottom());
        int childLeft = contentLeft + (contentRight - contentLeft - centeredWidthPx) / 2;
        int childTop = contentTop + (contentBottom - contentTop - centeredHeightPx) / 2;
        child.setTranslationX(0f);
        child.setTranslationY(0f);
        child.layout(childLeft, childTop,
                childLeft + centeredWidthPx, childTop + centeredHeightPx);
    }

    private void measureExactlyCenteredChild() {
        View child = exactlyCenteredChild;
        if (child == null || child.getVisibility() == GONE) return;
        child.measure(MeasureSpec.makeMeasureSpec(centeredWidthPx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(centeredHeightPx, MeasureSpec.EXACTLY));
    }
}
