/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.MediaTimeline;

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
    private TextView album;
    private TextView application;
    private ProgressBar progress;
    private TextView timeline;
    private SeekBar volume;
    private TextView volumeLabel;
    private ImageButton playPause;
    @Nullable private android.graphics.Bitmap artworkBitmap;
    @NonNull private String titleValue = "Музыка не воспроизводится";
    @NonNull private String artistValue = "";
    @NonNull private String albumValue = "";
    @NonNull private String applicationValue = "";
    private long durationMs;
    private long positionMs;
    private int volumePercent;
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
        albumValue = state.album;
        applicationValue = state.application;
        artworkBitmap = state.artwork;
        durationMs = state.durationMs;
        positionMs = state.positionMs;
        volumePercent = state.volumePercent;
        playing = state.playing;
        applySnapshot();
    }

    /** Realistic content for the settings screen without requiring notification access there. */
    public void setPreviewContent(@NonNull String title, @NonNull String artist,
                                  @NonNull String application, boolean playing) {
        titleValue = title;
        artistValue = artist;
        albumValue = "Альбом";
        applicationValue = application;
        artworkBitmap = null;
        durationMs = 4L * 60L * 1_000L + 12_000L;
        positionMs = 1L * 60L * 1_000L + 24_000L;
        volumePercent = 42;
        this.playing = playing;
        applySnapshot();
    }

    private void rebuild() {
        removeAllViews();
        elementViews.clear();
        artwork = null;
        title = null;
        artist = null;
        album = null;
        application = null;
        progress = null;
        timeline = null;
        volume = null;
        volumeLabel = null;
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
            case MediaPanelConfig.ALBUM:
                album = text(scaleSp(15, element.scalePercent),
                        withAlpha(color(config.secondaryColor, Color.LTGRAY), 210), false);
                album.setContentDescription("Альбом");
                return album;
            case MediaPanelConfig.APPLICATION:
                application = text(scaleSp(13, element.scalePercent),
                        withAlpha(color(config.secondaryColor, Color.LTGRAY), 190), false);
                application.setContentDescription("Музыкальное приложение");
                return application;
            case MediaPanelConfig.PROGRESS:
                return progressElement(element.scalePercent);
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
            case MediaPanelConfig.VOLUME:
                return volumeElement(element.scalePercent);
            default:
                TextView fallback = text(14, Color.WHITE, false);
                fallback.setText(spec.label);
                return fallback;
        }
    }

    @NonNull
    private View progressElement(int scalePercent) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        timeline = text(scaleSp(13, scalePercent), color(config.secondaryColor, Color.LTGRAY),
                false);
        timeline.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        timeline.setContentDescription("Позиция и длительность трека");
        root.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1_000);
        progress.setProgressTintList(ColorStateList.valueOf(
                color(config.controlColor, Color.WHITE)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(
                withAlpha(color(config.secondaryColor, Color.LTGRAY), 95)));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(dp(5), dp(7) * scalePercent / 100)));
        return root;
    }

    @NonNull
    private View volumeElement(int scalePercent) {
        LinearLayout root = new LinearLayout(getContext());
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setContentDescription("Громкость музыки");
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
        icon.setColorFilter(color(config.controlColor, Color.WHITE));
        int iconSize = Math.max(dp(22), dp(30) * scalePercent / 100);
        root.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        volume = new SeekBar(getContext());
        volume.setMax(100);
        volume.setEnabled(controls != null);
        volume.setProgressTintList(ColorStateList.valueOf(color(config.controlColor, Color.WHITE)));
        volume.setThumbTintList(ColorStateList.valueOf(color(config.controlColor, Color.WHITE)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (volumeLabel != null) volumeLabel.setText(value + "%");
                if (fromUser && controls != null) setSystemVolume(value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        seekLp.leftMargin = dp(4);
        root.addView(volume, seekLp);
        volumeLabel = text(scaleSp(13, scalePercent),
                color(config.secondaryColor, Color.LTGRAY), false);
        volumeLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        root.addView(volumeLabel, new LinearLayout.LayoutParams(
                Math.max(dp(46), dp(58) * scalePercent / 100),
                ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private void setSystemVolume(int percent) {
        AudioManager manager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        try {
            int maximum = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int selected = Math.round(Math.max(0, Math.min(100, percent)) * maximum / 100f);
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, selected, 0);
            volumePercent = percent;
        } catch (RuntimeException ignored) {}
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
        if (album != null) {
            album.setText(albumValue);
            album.setVisibility(albumValue.isEmpty() ? View.GONE : View.VISIBLE);
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
        if (timeline != null) {
            timeline.setText(MediaTimeline.format(positionMs) + " / "
                    + MediaTimeline.format(durationMs));
        }
        if (progress != null) {
            progress.setProgress(MediaTimeline.progress(positionMs, durationMs, 1_000));
            View progressRoot = elementViews.get(MediaPanelConfig.PROGRESS);
            if (progressRoot != null) progressRoot.setVisibility(
                    durationMs > 0L ? View.VISIBLE : View.GONE);
        }
        if (volume != null && !volume.isPressed()) volume.setProgress(volumePercent);
        if (volumeLabel != null) volumeLabel.setText(volumePercent + "%");
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
