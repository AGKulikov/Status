/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.launcher.apps.FavoriteAppConfig;

/** Shared cell renderer for HOME and driver-panel all-apps grids. */
public final class LauncherAppTileRenderer {
    private LauncherAppTileRenderer() {
    }

    @NonNull
    public static LinearLayout render(@NonNull Context context, @Nullable View reusable,
                                      @NonNull String labelText,
                                      @Nullable Drawable iconDrawable,
                                      @NonNull FavoriteAppConfig appearance,
                                      int scalePercent) {
        int scale = Math.max(1, scalePercent);
        LinearLayout cell = reusable instanceof LinearLayout
                ? (LinearLayout) reusable : new LinearLayout(context);
        cell.removeAllViews();
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setPadding(dp(context, 4), dp(context, 5), dp(context, 4), dp(context, 5));

        ImageView icon = new ImageView(context);
        icon.setImageDrawable(iconDrawable);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconSize = Math.max(24, appearance.iconSizePx * scale / 100);
        cell.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        if (appearance.showLabel) {
            TextView label = new TextView(context);
            label.setTextSize(appearance.labelSizeSp * scale / 100f);
            label.setTextColor(Color.WHITE);
            label.setGravity(Gravity.CENTER);
            label.setText(labelText);
            label.setMaxLines(1);
            cell.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.max(dp(context, 18), dp(context, 25) * scale / 100)));
        }
        return cell;
    }

    private static int dp(@NonNull Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
