/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.MediaTimeline;

/** Responsive HOME media surface whose contents are fully driven by {@link MediaPanelConfig}. */
public final class MediaPanelView extends FrameLayout {
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    public interface Controls {
        void previous();
        void playPause();
        void next();
    }

    /** Direct manipulation callback used only by the settings preview. */
    public interface LayoutEditor {
        void onLayoutChanged(@NonNull MediaPanelConfig value, @NonNull String movedId,
                             boolean finished);
    }

    private final MediaPanelConfigStore store;
    @Nullable private final Controls controls;
    private final Map<String, View> elementViews = new LinkedHashMap<>();
    private final int[] dragGridLocation = new int[2];
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable delayedVolumeSync = this::syncSystemVolume;
    private boolean volumeReceiverRegistered;
    private final BroadcastReceiver volumeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int stream = intent == null ? AudioManager.STREAM_MUSIC : intent.getIntExtra(
                    EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC);
            if (stream == AudioManager.STREAM_MUSIC) syncSystemVolume();
        }
    };
    private MediaPanelConfig config;
    private MediaElementGridLayout grid;
    @Nullable private LayoutEditor layoutEditor;
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
    private boolean restartTextMarquee;

    public MediaPanelView(@NonNull Context context, @NonNull MediaPanelConfigStore store,
                          @Nullable Controls controls) {
        super(context);
        this.store = store;
        this.controls = controls;
        config = store.load();
        if (controls != null) volumePercent = readSystemVolume();
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

    /** Enables drag-to-place, slot highlighting and disables media actions in the preview. */
    public void setLayoutEditor(@Nullable LayoutEditor editor) {
        setInPlaceEditMode(editor != null, editor);
    }

    /**
     * Uses this exact rendered panel as the editor, so grid placement cannot differ from HOME.
     * A drag moves an element; dragging its bottom-right 30dp corner resizes its cell span.
     */
    public void setInPlaceEditMode(boolean enabled) {
        setInPlaceEditMode(enabled, enabled
                ? (updated, movedId, finished) -> {
                    if (finished) store.save(updated);
                }
                : null);
    }

    public void setInPlaceEditMode(boolean enabled, @Nullable LayoutEditor editor) {
        layoutEditor = enabled ? editor : null;
        rebuild();
    }

    @NonNull
    public MediaPanelConfig currentConfig() {
        return config.copy();
    }

    /** Existing Android MediaSession state remains owned by LauncherMediaController. */
    public void setSnapshot(@NonNull LauncherMediaController.Snapshot state) {
        restartTextMarquee |= !titleValue.equals(state.title)
                || !artistValue.equals(state.artist)
                || !albumValue.equals(state.album)
                || !applicationValue.equals(state.application);
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
        String previewAlbum = "Очень длинное название альбома для проверки прокрутки";
        restartTextMarquee |= !titleValue.equals(title)
                || !artistValue.equals(artist)
                || !albumValue.equals(previewAlbum)
                || !applicationValue.equals(application);
        titleValue = title;
        artistValue = artist;
        albumValue = previewAlbum;
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
        configurePanelClick();

        grid = new MediaElementGridLayout(getContext());
        grid.setSpacing(config.spacingPx);
        grid.setGridSize(config.gridColumns, config.gridRows);
        grid.setGridVisible(layoutEditor != null);
        grid.setPadding(config.contentPaddingPx, config.contentPaddingPx,
                config.contentPaddingPx, config.contentPaddingPx);
        for (MediaPanelConfig.Element element : config.orderedElements()) {
            if (!element.enabled) continue;
            MediaPanelConfig.Spec spec = MediaPanelConfig.spec(element.id);
            if (spec == null) continue;
            View view = buildElement(element, spec);
            elementViews.put(element.id, view);
            if (layoutEditor != null) attachEditorDrag(view, element.id);
            grid.addView(view, elementLayout(element));
        }
        addView(grid, new FrameLayout.LayoutParams(
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    artwork.setClipToOutline(true);
                    artwork.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                }
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
                        controls == null || layoutEditor != null
                                ? null : v -> controls.previous(), element.scalePercent);
            case MediaPanelConfig.PLAY_PAUSE:
                playPause = button(android.R.drawable.ic_media_play, "Играть или поставить на паузу",
                        controls == null || layoutEditor != null
                                ? null : v -> controls.playPause(), element.scalePercent);
                return playPause;
            case MediaPanelConfig.NEXT:
                return button(android.R.drawable.ic_media_next, "Следующий трек",
                        controls == null || layoutEditor != null
                                ? null : v -> controls.next(), element.scalePercent);
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
        root.setPadding(dp(10), dp(4), dp(10), dp(7));
        root.setBackground(glassBackground(false, Math.max(dp(10), config.cornerRadiusPx / 2)));
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
        root.setPadding(dp(8), 0, dp(8), 0);
        root.setBackground(glassBackground(false, Math.max(dp(10), config.cornerRadiusPx / 2)));
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
        icon.setColorFilter(color(config.controlColor, Color.WHITE));
        int iconSize = Math.max(dp(22), dp(30) * scalePercent / 100);
        root.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        volume = new SeekBar(getContext());
        volume.setMax(100);
        volume.setEnabled(controls != null && layoutEditor == null);
        volume.setProgressTintList(ColorStateList.valueOf(color(config.controlColor, Color.WHITE)));
        volume.setThumbTintList(ColorStateList.valueOf(color(config.controlColor, Color.WHITE)));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (volumeLabel != null) volumeLabel.setText(value + "%");
                if (fromUser && controls != null && layoutEditor == null) setSystemVolume(value);
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
            int selected = MediaVolumeMath.stepForPercent(percent, maximum);
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, selected, 0);
            // ECARX applies an absolute volume asynchronously on some firmware. Reflect the
            // actual discrete stream step immediately and verify it once the vendor mixer settles.
            volumePercent = MediaVolumeMath.percentForStep(
                    manager.getStreamVolume(AudioManager.STREAM_MUSIC), maximum);
            updateVolumeUi();
            mainHandler.removeCallbacks(delayedVolumeSync);
            mainHandler.postDelayed(delayedVolumeSync, 180L);
            mainHandler.postDelayed(delayedVolumeSync, 650L);
        } catch (RuntimeException ignored) {}
    }

    private int readSystemVolume() {
        AudioManager manager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return volumePercent;
        try {
            return MediaVolumeMath.percentForStep(
                    manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (RuntimeException ignored) {
            return volumePercent;
        }
    }

    private void syncSystemVolume() {
        if (controls == null) return;
        volumePercent = readSystemVolume();
        updateVolumeUi();
    }

    private void updateVolumeUi() {
        if (volume != null && !volume.isPressed()) volume.setProgress(volumePercent);
        if (volumeLabel != null) volumeLabel.setText(volumePercent + "%");
    }

    private void configurePanelClick() {
        if (controls == null || layoutEditor != null) {
            setOnClickListener(null);
            setClickable(false);
            return;
        }
        setClickable(true);
        setOnClickListener(view -> {
            if (!MediaAppLauncher.launchYandexMusic(getContext())) {
                Toast.makeText(getContext(), "Яндекс Музыка не найдена",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    @NonNull
    private MediaElementGridLayout.ElementLayoutParams elementLayout(
            @NonNull MediaPanelConfig.Element element) {
        return new MediaElementGridLayout.ElementLayoutParams(element.column, element.row,
                element.columnSpan, element.rowSpan);
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
        value.setScaleType(ImageView.ScaleType.FIT_CENTER);
        value.setColorFilter(color(config.controlColor, Color.WHITE));
        // The whole grid cell remains the touch target; only the glyph is drawn.
        value.setBackgroundColor(Color.TRANSPARENT);
        value.setContentDescription(description);
        // A larger content scale must make the glyph larger, not add more padding around it.
        int padding = Math.max(dp(3),
                Math.round(dp(16) * 100f / Math.max(45, scalePercent)));
        value.setPadding(padding, padding, padding, padding);
        value.setOnClickListener(listener);
        value.setClickable(listener != null);
        value.setFocusable(listener != null);
        return value;
    }

    private void applySnapshot() {
        if (title != null) {
            applyMediaText(title, titleValue,
                    config.element(MediaPanelConfig.TITLE).marqueeEnabled,
                    restartTextMarquee);
        }
        if (artist != null) {
            applyMediaText(artist, artistValue,
                    config.element(MediaPanelConfig.ARTIST).marqueeEnabled,
                    restartTextMarquee);
            artist.setVisibility(artistValue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (album != null) {
            applyMediaText(album, albumValue,
                    config.element(MediaPanelConfig.ALBUM).marqueeEnabled,
                    restartTextMarquee);
            album.setVisibility(albumValue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (application != null) {
            application.setText(applicationValue);
            application.setVisibility(applicationValue.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (artwork != null) {
            if (artworkBitmap == null) {
                // Clear both the previous track bitmap and its backing shape. Missing artwork is
                // deliberately an empty transparent grid cell, never a media-icon placeholder.
                artwork.setImageDrawable(null);
                artwork.setBackground(null);
                artwork.clearColorFilter();
            } else {
                artwork.setBackground(glassBackground(false, config.cornerRadiusPx / 2));
                artwork.setImageBitmap(artworkBitmap);
                artwork.clearColorFilter();
            }
        }
        if (playPause != null) {
            playPause.setImageResource(playing
                    ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            playPause.setColorFilter(color(playing ? config.accentColor : config.controlColor,
                    Color.WHITE));
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
        updateVolumeUi();
        restartTextMarquee = false;
    }

    /**
     * Toggling selected around a changed value resets Android's marquee immediately, so a new
     * track can never continue scrolling pixels/text from the previous one. Android itself starts
     * marquee only when the laid-out line overflows, therefore short strings remain stationary.
     */
    private static void applyMediaText(@NonNull TextView view, @NonNull String value,
                                       boolean marqueeEnabled, boolean forceRestart) {
        boolean changed = !TextUtils.equals(view.getText(), value);
        if (changed || forceRestart) view.setSelected(false);
        view.setSingleLine(true);
        view.setEllipsize(marqueeEnabled
                ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
        view.setMarqueeRepeatLimit(-1);
        if (changed || forceRestart) view.setText(value);
        view.setSelected(marqueeEnabled && !value.isEmpty());
    }

    private void applySurface() {
        int base = color(config.backgroundColor, Color.rgb(18, 25, 35));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(config.backgroundAlpha, Color.red(base),
                Color.green(base), Color.blue(base)));
        background.setCornerRadius(config.cornerRadiusPx);
        int outline = color(config.outlineColor, Color.WHITE);
        background.setStroke(config.outlineWidthPx, Color.argb(config.outlineAlpha,
                Color.red(outline), Color.green(outline), Color.blue(outline)));
        setBackground(background);
    }

    @NonNull
    private GradientDrawable glassBackground(boolean active, int radius) {
        int source = color(active ? config.accentColor : config.glassColor,
                active ? Color.rgb(134, 183, 255) : Color.WHITE);
        GradientDrawable value = new GradientDrawable();
        int alpha = active ? Math.max(92, config.glassAlpha * 3) : config.glassAlpha;
        value.setColor(Color.argb(Math.min(220, alpha), Color.red(source), Color.green(source),
                Color.blue(source)));
        int outline = color(config.outlineColor, Color.WHITE);
        value.setStroke(config.outlineWidthPx, Color.argb(config.outlineAlpha,
                Color.red(outline), Color.green(outline), Color.blue(outline)));
        value.setCornerRadius(Math.max(0, radius));
        return value;
    }

    private void attachEditorDrag(@NonNull View child, @NonNull String id) {
        child.setClickable(true);
        child.setOnTouchListener(new OnTouchListener() {
            private float touchOffsetX;
            private float touchOffsetY;
            private boolean resizing;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchOffsetX = event.getX();
                        touchOffsetY = event.getY();
                        int handle = dp(30);
                        resizing = event.getX() >= view.getWidth() - handle
                                && event.getY() >= view.getHeight() - handle;
                        if (view.getParent() != null) {
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                        view.bringToFront();
                        view.setScaleX(1.035f);
                        view.setScaleY(1.035f);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.setElevation(dp(10));
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        grid.getLocationOnScreen(dragGridLocation);
                        if (resizing) {
                            int right = Math.round(event.getRawX() - dragGridLocation[0]);
                            int bottom = Math.round(event.getRawY() - dragGridLocation[1]);
                            if (grid.resizeToPixel(view, right, bottom)) {
                                MediaElementGridLayout.ElementLayoutParams lp =
                                        (MediaElementGridLayout.ElementLayoutParams)
                                                view.getLayoutParams();
                                config.setSpan(id, lp.columnSpan, lp.rowSpan);
                                syncGridPlacements();
                                LayoutEditor editor = layoutEditor;
                                if (editor != null) {
                                    editor.onLayoutChanged(config.copy(), id, false);
                                }
                            }
                            return true;
                        }
                        int left = Math.round(event.getRawX() - dragGridLocation[0] - touchOffsetX);
                        int top = Math.round(event.getRawY() - dragGridLocation[1] - touchOffsetY);
                        if (grid.moveToPixel(view, left, top)) {
                            MediaElementGridLayout.ElementLayoutParams lp =
                                    (MediaElementGridLayout.ElementLayoutParams) view.getLayoutParams();
                            config.setPosition(id, lp.column, lp.row);
                            syncGridPlacements();
                            LayoutEditor editor = layoutEditor;
                            if (editor != null) editor.onLayoutChanged(config.copy(), id, false);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (view.getParent() != null) {
                            view.getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        view.setScaleX(1f);
                        view.setScaleY(1f);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            view.setElevation(0f);
                        }
                        LayoutEditor editor = layoutEditor;
                        if (editor != null) editor.onLayoutChanged(config.copy(), id, true);
                        view.performClick();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    private void syncGridPlacements() {
        for (Map.Entry<String, View> entry : elementViews.entrySet()) {
            MediaPanelConfig.Element element = config.element(entry.getKey());
            View view = entry.getValue();
            MediaElementGridLayout.ElementLayoutParams lp =
                    (MediaElementGridLayout.ElementLayoutParams) view.getLayoutParams();
            lp.column = element.column;
            lp.row = element.row;
            lp.columnSpan = element.columnSpan;
            lp.rowSpan = element.rowSpan;
            view.setLayoutParams(lp);
        }
        grid.requestLayout();
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

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (controls == null || volumeReceiverRegistered) return;
        try {
            ContextCompat.registerReceiver(getContext(), volumeReceiver,
                    new IntentFilter(ACTION_VOLUME_CHANGED), ContextCompat.RECEIVER_EXPORTED);
            volumeReceiverRegistered = true;
        } catch (RuntimeException ignored) {
            volumeReceiverRegistered = false;
        }
        syncSystemVolume();
    }

    @Override protected void onDetachedFromWindow() {
        mainHandler.removeCallbacks(delayedVolumeSync);
        if (volumeReceiverRegistered) {
            volumeReceiverRegistered = false;
            try { getContext().unregisterReceiver(volumeReceiver); }
            catch (RuntimeException ignored) {}
        }
        super.onDetachedFromWindow();
    }
}
