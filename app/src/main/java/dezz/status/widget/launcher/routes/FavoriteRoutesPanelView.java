/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import dezz.status.widget.launcher.LauncherIconResolver;

/**
 * Responsive grid of user-defined Yandex routes.
 *
 * <p>The panel owns no configuration state: {@link #reloadConfig()} always rebuilds it from the
 * store. Settings screens may enable {@link #setPreviewMode(boolean)} to render the exact same
 * tiles without accidentally starting navigation.</p>
 */
public final class FavoriteRoutesPanelView extends FrameLayout {
    private static final int DEFAULT_COLUMNS = 2;
    private static final int TILE_MARGIN_DP = 4;
    private static final int TILE_PADDING_DP = 10;
    private static final int TILE_CORNER_DP = 14;

    @NonNull private final FavoriteRoutesConfigStore store;
    @NonNull private List<FavoriteRouteConfig> routes = Collections.emptyList();
    private int columns;
    private boolean previewMode;

    public FavoriteRoutesPanelView(@NonNull Context context,
                                   @NonNull FavoriteRoutesConfigStore store) {
        this(context, store, DEFAULT_COLUMNS);
    }

    public FavoriteRoutesPanelView(@NonNull Context context,
                                   @NonNull FavoriteRoutesConfigStore store,
                                   int columns) {
        super(context);
        this.store = store;
        this.columns = normalizeColumns(columns);
        setClipChildren(false);
        setClipToPadding(false);
        reloadConfig();
    }

    /** Reloads the ordered route list and all tile appearance values from persistent storage. */
    public void reloadConfig() {
        List<FavoriteRouteConfig> loaded = store.load();
        routes = loaded == null ? Collections.emptyList() : loaded;
        rebuild();
    }

    /** Changes the responsive grid width without re-reading persistent configuration. */
    public void setColumns(int value) {
        int normalized = normalizeColumns(value);
        if (columns == normalized) return;
        columns = normalized;
        rebuild();
    }

    public int getColumns() {
        return columns;
    }

    /** Disables every route action while preserving a pixel-identical settings preview. */
    public void setPreviewMode(boolean value) {
        if (previewMode == value) return;
        previewMode = value;
        rebuild();
    }

    public boolean isPreviewMode() {
        return previewMode;
    }

    /** True when the current saved configuration contains at least one visible destination. */
    public boolean hasEnabledRoutes() {
        for (FavoriteRouteConfig route : routes) {
            if (route != null && route.enabled) return true;
        }
        return false;
    }

    private void rebuild() {
        removeAllViews();

        GridLayout grid = new GridLayout(getContext());
        grid.setColumnCount(columns);
        grid.setOrientation(GridLayout.HORIZONTAL);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);

        int itemIndex = 0;
        for (FavoriteRouteConfig route : routes) {
            if (route == null || !route.enabled) continue;
            int row = itemIndex / columns;
            int column = itemIndex % columns;
            grid.addView(buildTile(route), cellLayout(row, column, true));
            itemIndex++;
        }

        // GridLayout distributes weight only between columns represented by children. Invisible
        // zero-height cells keep a partially filled (or single-row) grid divided into exactly the
        // requested number of columns instead of stretching its last tile across the whole panel.
        if (itemIndex > 0) {
            int missing = (columns - itemIndex % columns) % columns;
            int row = itemIndex / columns;
            for (int index = 0; index < missing; index++) {
                int column = itemIndex % columns;
                View spacer = new View(getContext());
                spacer.setVisibility(INVISIBLE);
                grid.addView(spacer, cellLayout(row, column, false));
                itemIndex++;
            }
        }

        // The number of destinations is intentionally unlimited. A compact HOME rectangle keeps
        // its chosen pixel size and becomes scrollable instead of silently clipping later rows.
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(itemIndex > columns);
        scroll.addView(grid, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @NonNull
    private View buildTile(@NonNull FavoriteRouteConfig route) {
        LinearLayout tile = new LinearLayout(getContext());
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        int padding = dp(TILE_PADDING_DP);
        tile.setPadding(padding, padding, padding, padding);
        tile.setBackground(tileBackground(route.backgroundColor));

        String title = route.title == null ? "" : route.title.trim();
        tile.setContentDescription(title);

        ImageView icon = new ImageView(getContext());
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        Drawable drawable = LauncherIconResolver.resolvePreset(
                getContext(), route.icon, route.iconColor);
        icon.setImageDrawable(drawable);
        int iconSize = Math.max(1, route.iconSizePx);
        tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(color(route.textColor, Color.WHITE));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.max(1, route.labelSizeSp));
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams labelLayout = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLayout.topMargin = dp(6);
        tile.addView(label, labelLayout);

        if (previewMode) {
            tile.setClickable(false);
            tile.setFocusable(false);
        } else {
            tile.setClickable(true);
            tile.setFocusable(true);
            // The listener belongs to the outer tile. Its icon and label deliberately have no
            // listeners, so every visible point inside the cell starts the same route.
            tile.setOnClickListener(view -> YandexRouteLauncher.launch(getContext(), route));
        }
        return tile;
    }

    @NonNull
    private GridLayout.LayoutParams cellLayout(int row, int column, boolean withMargins) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(row), GridLayout.spec(column, 1, 1f));
        params.width = 0;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (withMargins) {
            int margin = dp(TILE_MARGIN_DP);
            params.setMargins(margin, margin, margin, margin);
        }
        return params;
    }

    @NonNull
    private Drawable tileBackground(@Nullable String value) {
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setColor(color(value, Color.rgb(31, 36, 46)));
        background.setCornerRadius(dp(TILE_CORNER_DP));
        return background;
    }

    private static int normalizeColumns(int value) {
        return Math.max(1, value);
    }

    private static int color(@Nullable String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Color.parseColor(value.trim());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
