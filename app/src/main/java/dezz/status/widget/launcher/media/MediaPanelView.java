/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.launcher.LauncherMediaController;

/** Responsive HOME media surface whose contents are fully driven by {@link MediaPanelConfig}. */
public final class MediaPanelView extends FrameLayout {
    public interface Controls {
        void previous();
        void playPause();
        void next();
    }

    private final MediaPanelConfigStore store;
    @Nullable private final Controls controls;
    private final Map<String, View> elementViews = new LinkedHashMap<>();
    private MediaPanelConfig config;
    private MediaElementFlowLayout flow;
    private ImageView artwork;
    private TextView title;
    private TextView artist;
    private TextView application;
    private ImageButton playPause;
    @Nullable private android.graphics.Bitmap artworkBitmap;
    @NonNull private String titleValue = "Музыка не воспроизводится";
    @NonNull private String artistValue = "";
    @NonNull private String applicationValue = "";
    private boolean playing;

    public MediaPanelView(@NonNull Context context, @NonNull MediaPanelConfigStore store,
                          @Nullable Controls controls) {
        super(context);
        this.store = store;
        this.controls = controls;
        config = store.load();
        setClipChildren(false);
        setClipToPadding(false);
        rebuild();
    }

    public void reloadConfig() {
        setConfig(store.load());
    }

    /** Used by the editor for a no-restart live preview. */
    public void setConfig(@NonNull MediaPanelConfig value) {
        config = value.copy();
        config.normalize();
        rebuild();
    }

    @NonNull
    public MediaPanelConfig currentConfig() {
        return config.copy();
    }

    /** Existing Android MediaSession state remains owned by LauncherMediaController. */
    public void setSnapshot(@NonNull LauncherMediaController.Snapshot state) {
        titleValue = state.title;
        artistValue = state.artist;
        applicationValue = state.application;
        artworkBitmap = state.artwork;
        playing = state.playing;
        applySnapshot();
    }

    /** Realistic content for the settings screen without requiring notification access there. */
    public void setPreviewContent(@NonNull String title, @NonNull String artist,
                                  @NonNull String application, boolean playing) {
        titleValue = title;
        artistValue = artist;
        applicationValue = application;
        artworkBitmap = null;
        this.playing = playing;
        applySnapshot();
    }

    private void rebuild() {
        removeAllViews();
        elementViews.clear();
        artwork = null;
        title = null;
        artist = null;
        application = null;
        playPause = null;
        applySurface();

        flow = new MediaElementFlowLayout(getContext());
        flow.setSpacing(config.spacingPx);
        flow.setPadding(config.contentPaddingPx, config.contentPaddingPx,
                config.contentPaddingPx, config.contentPaddingPx);
        for (MediaPanelConfig.Element element : config.orderedElements()) {
            if (!element.enabled) continue;
            MediaPanelConfig.Spec spec = MediaPanelConfig.spec(element.id);
            if (spec == null) continue;
            View view = buildElement(element, spec);
            elementViews.put(element.id, view);
            flow.addView(view, elementLayout(spec, element.scalePercent));
        }
        addView(flow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        applySnapshot();
    }

    @NonNull
    private View buildElement(@NonNull MediaPanelConfig.Element element,
                              @NonNull MediaPanelConfig.Spec spec) {
        switch (element.id) {
            case MediaPanelConfig.ARTWORK:
                artwork = new ImageView(getContext());
                artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
                artwork.setContentDescription("Обложка");
                return artwork;
            case MediaPanelConfig.TITLE:
                title = text(scaleSp(23, element.scalePercent), color(config.titleColor,
                        Color.WHITE), true);
                title.setContentDescription("Название композиции");
                return title;
            case MediaPanelConfig.ARTIST:
                artist = text(scaleSp(18, element.scalePercent), color(config.secondaryColor,
                        Color.LTGRAY), false);
                artist.setContentDescription("Исполнитель");
                return artist;
            case MediaPanelConfig.APPLICATION:
                application = text(scaleSp(13, element.scalePercent),
                        withAlpha(color(config.secondaryColor, Color.LTGRAY), 190), false);
                application.setContentDescription("Музыкальное приложение");
                return application;
            case MediaPanelConfig.PREVIOUS:
                return button(android.R.drawable.ic_media_previous, "Предыдущий трек",
                        controls == null ? null : v -> controls.previous(), element.scalePercent);
            case MediaPanelConfig.PLAY_PAUSE:
                playPause = button(android.R.drawable.ic_media_play, "Играть или поставить на паузу",
                        controls == null ? null : v -> controls.playPause(), element.scalePercent);
                return playPause;
            case MediaPanelConfig.NEXT:
                return button(android.R.drawable.ic_media_next, "Следующий трек",
                        controls == null ? null : v -> controls.next(), element.scalePercent);
            default:
                TextView fallback = text(14, Color.WHITE, false);
                fallback.setText(spec.label);
                return fallback;
        }
    }

    @NonNull
    private LayoutParams elementLayout(@NonNull MediaPanelConfig.Spec spec, int scalePercent) {
        int width = Math.max(dp(28), Math.round(dp(spec.baseWidthDp) * scalePercent / 100f));
        int height = Math.max(dp(28), Math.round(dp(spec.baseHeightDp) * scalePercent / 100f));
        return new LayoutParams(width, height);
    }

    @NonNull
    private TextView text(float sizeSp, int textColor, boolean bold) {
        TextView value = new TextView(getContext());
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setTextSize(sizeSp);
        value.setTextColor(textColor);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        value.setPadding(dp(6), 0, dp(6), 0);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    @NonNull
    private ImageButton button(int icon, @NonNull String description,
                               @Nullable OnClickListener listener, int scalePercent) {
        ImageButton value = new ImageButton(getContext());
        value.setImageResource(icon);
        value.setColorFilter(color(config.controlColor, Color.WHITE));
        value.setBackgroundColor(Color.TRANSPARENT);
        value.setContentDescription(description);
        int padding = Math.max(dp(4), Math.round(dp(13) * scalePercent / 100f));
        value.setPadding(padding, padding, padding, padding);
        value.setOnClickListener(listener);
        value.setClickable(listener != null);
        value.setFocusable(listener != null);
        return value;
    }

    private void applySnapshot() {
        if (title != null) title.setText(titleValue);
        if (artist != null) {
            artist.setText(artistValue);
            artist.setVisibility(artistValue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (application != null) {
            application.setText(applicationValue);
            application.setVisibility(applicationValue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (artwork != null) {
            if (artworkBitmap != null) artwork.setImageBitmap(artworkBitmap);
            else artwork.setImageResource(android.R.drawable.ic_media_play);
            if (artworkBitmap == null) {
                artwork.setColorFilter(color(config.controlColor, Color.WHITE));
            } else {
                artwork.clearColorFilter();
            }
        }
        if (playPause != null) {
            playPause.setImageResource(playing
                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        }
    }

    private void applySurface() {
        int base = color(config.backgroundColor, Color.rgb(18, 25, 35));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(config.backgroundAlpha, Color.red(base),
                Color.green(base), Color.blue(base)));
        background.setCornerRadius(config.cornerRadiusPx);
        setBackground(background);
    }

    private static int color(String value, int fallback) {
        try { return Color.parseColor(value); }
        catch (IllegalArgumentException ignored) { return fallback; }
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static float scaleSp(float base, int scalePercent) {
        return Math.max(8f, base * scalePercent / 100f);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
