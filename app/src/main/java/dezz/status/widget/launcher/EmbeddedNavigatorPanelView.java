/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Underlay for the live Navigator Activity.
 *
 * <p>In the merged build the mod's own interactive overlay covers this frame. Keeping a real
 * launcher frame underneath gives the common HOME editor one movable/resizable rectangle and
 * leaves a useful retry target if Navigator is still starting.</p>
 */
public final class EmbeddedNavigatorPanelView extends FrameLayout {
    public EmbeddedNavigatorPanelView(@NonNull Context context, @NonNull Runnable retry) {
        super(context);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(210, 17, 21, 29));
        background.setCornerRadius(dp(22));
        setBackground(background);
        setClickable(true);
        setOnClickListener(view -> retry.run());

        TextView status = new TextView(context);
        status.setText("Яндекс Навигатор\nНажмите, если окно не появилось");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        addView(status, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
