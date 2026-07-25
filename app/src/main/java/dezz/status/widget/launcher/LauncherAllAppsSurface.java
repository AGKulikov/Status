/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;

import dezz.status.widget.Preferences;

/** Pixel-identical chrome shared by HOME and the driver-panel All Apps surface. */
public final class LauncherAllAppsSurface {
    public static final class Views {
        @NonNull public final FrameLayout root;
        @NonNull public final MaterialButton close;
        @NonNull public final GridView grid;

        Views(@NonNull FrameLayout root, @NonNull MaterialButton close,
              @NonNull GridView grid) {
            this.root = root;
            this.close = close;
            this.grid = grid;
        }
    }

    private LauncherAllAppsSurface() {
    }

    @NonNull
    public static Views create(@NonNull Context context,
                               @NonNull Preferences preferences) {
        FrameLayout root = new FrameLayout(context);
        root.setPadding(dp(context, 24), dp(context, 18),
                dp(context, 24), dp(context, 24));
        root.setBackgroundColor(Color.argb(247, 10, 13, 18));

        TextView title = new TextView(context);
        title.setText("Все приложения");
        title.setTextColor(Color.WHITE);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 72),
                Gravity.TOP | Gravity.START));

        MaterialButton close = new MaterialButton(context);
        close.setText("✕");
        close.setTextSize(24);
        close.setTextColor(Color.WHITE);
        close.setAllCaps(false);
        close.setMinWidth(0);
        close.setMinimumWidth(0);
        close.setInsetTop(0);
        close.setInsetBottom(0);
        close.setContentDescription("Закрыть список приложений");
        root.addView(close, new FrameLayout.LayoutParams(
                dp(context, 72), dp(context, 72), Gravity.TOP | Gravity.END));

        GridView grid = new GridView(context);
        grid.setNumColumns(Math.max(3,
                Math.min(8, preferences.launcherAllAppsColumns.get())));
        grid.setPadding(dp(context, 16), dp(context, 16),
                dp(context, 16), dp(context, 16));
        int gap = Math.max(0, Math.min(40, preferences.launcherAllAppsGapPx.get()));
        grid.setVerticalSpacing(dp(context, gap));
        grid.setHorizontalSpacing(dp(context, gap));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        FrameLayout.LayoutParams gridParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        gridParams.topMargin = dp(context, 84);
        root.addView(grid, gridParams);
        return new Views(root, close, grid);
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
