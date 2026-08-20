/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.LongPressFeedback;

/**
 * Recyclable all-apps cell with an OEM-style red minus badge.
 *
 * <p>The badge exists only while edit mode is active and only for a package which the shared
 * policy considers removable. The underlying tile remains in place while Android displays its
 * package-removal confirmation.</p>
 */
public final class AppDrawerTileView extends FrameLayout {
    @Nullable private LinearLayout content;
    @NonNull private final TextView uninstallBadge;

    public AppDrawerTileView(@NonNull Context context) {
        super(context);
        setClipChildren(false);
        setClipToPadding(false);

        uninstallBadge = new TextView(context);
        uninstallBadge.setText("−");
        uninstallBadge.setTextColor(Color.WHITE);
        uninstallBadge.setTextSize(22);
        uninstallBadge.setGravity(Gravity.CENTER);
        uninstallBadge.setContentDescription("Удалить приложение");
        GradientDrawable badgeBackground = new GradientDrawable();
        badgeBackground.setShape(GradientDrawable.OVAL);
        badgeBackground.setColor(0xFFFF1F2D);
        uninstallBadge.setBackground(badgeBackground);
        uninstallBadge.setElevation(dp(8));
        uninstallBadge.setVisibility(GONE);
        LayoutParams badgeParams = new LayoutParams(dp(30), dp(30),
                Gravity.TOP | Gravity.START);
        badgeParams.leftMargin = dp(1);
        badgeParams.topMargin = dp(1);
        addView(uninstallBadge, badgeParams);
    }

    @Nullable
    public LinearLayout reusableContent() {
        return content;
    }

    public void bind(@NonNull LinearLayout tile,
                     boolean editMode,
                     boolean uninstallable,
                     @NonNull Runnable launch,
                     @NonNull Runnable enterEditMode,
                     @NonNull Runnable uninstall) {
        if (content != tile) {
            if (content != null) removeView(content);
            content = tile;
            addView(tile, 0, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        }
        tile.setClickable(false);
        tile.setLongClickable(false);
        uninstallBadge.setVisibility(editMode && uninstallable ? VISIBLE : GONE);
        uninstallBadge.setOnClickListener(editMode && uninstallable
                ? view -> uninstall.run() : null);
        setOnClickListener(view -> {
            if (!editMode) launch.run();
        });
        setOnLongClickListener(view -> {
            LongPressFeedback.play(view);
            enterEditMode.run();
            return true;
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
