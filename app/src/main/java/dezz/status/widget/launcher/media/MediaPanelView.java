/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Map;

import dezz.status.widget.R;
import dezz.status.widget.MarqueeOutlineTextView;
import dezz.status.widget.launcher.LauncherGlobalElementTag;
import dezz.status.widget.launcher.LauncherLayoutStore;
import dezz.status.widget.launcher.LauncherMediaController;
import dezz.status.widget.launcher.MediaTimeline;
import dezz.status.widget.launcher.panels.PanelContentResizeMath;

/** Responsive HOME media surface whose contents are fully driven by {@link MediaPanelConfig}. */
public final class MediaPanelView extends FrameLayout {
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    public interface Controls {
        void previous();
        void playPause();
        void next();
        boolean openPlayer();
    }

    /** Direct manipulation callback used only by the settings preview. */
    public interface LayoutEditor {
        void onLayoutChanged(@NonNull MediaPanelConfig value, @NonNull String movedId,
                             boolean finished);
    }

    private interface VolumeChangeListener {
        void onProgressChanged(int value, boolean fromUser);
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
    private boolean globalEditPreview;
    private ImageView artwork;
    private MarqueeOutlineTextView title;
    private MarqueeOutlineTextView artist;
    private MarqueeOutlineTextView album;
    private TextView application;
    private ResponsiveProgressBar progress;
    private TextView timeline;
    private ResponsiveVolumeBar volume;
    private TextView volumeLabel;
    private ImageButton playPause;
    @Nullable private android.graphics.Bitmap artworkBitmap;
    @Nullable private android.graphics.Bitmap observedArtworkBitmap;
    private long observedArtworkFingerprint;
    @Nullable private android.graphics.Bitmap renderedArtworkBitmap;
    private long renderedArtworkFingerprint;
    private boolean renderedArtworkOwned;
    private boolean artworkRenderInitialized;
    private boolean artworkTrackChanged;
    @NonNull private String artworkTrackKey = "";
    @NonNull private String artworkTrackApplication = "";
    @NonNull private String artworkTrackTitle = "";
    @NonNull private String artworkTrackArtist = "";
    @NonNull private String artworkTrackAlbum = "";
    @NonNull private String previousArtworkTrackAlbum = "";
    private long rejectedArtworkFingerprint;
    private boolean playPauseRenderInitialized;
    private boolean renderedPlaying;
    private int renderedPlayPauseTint;
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
     * A drag moves an element; dragging any of its four 30dp corners resizes its cell rectangle.
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

    /** Keeps every configured live element measurable while the screen-wide HOME editor is on. */
    public void setGlobalEditPreview(boolean enabled) {
        if (globalEditPreview == enabled) return;
        globalEditPreview = enabled;
        applySnapshot();
    }

    @NonNull
    public MediaPanelConfig currentConfig() {
        return config.copy();
    }

    /** Existing Android MediaSession state remains owned by LauncherMediaController. */
    public void setSnapshot(@NonNull LauncherMediaController.Snapshot state) {
        boolean sameTrack = MediaArtworkBindingPolicy.sameTrack(
                artworkTrackApplication, artworkTrackTitle, artworkTrackArtist,
                artworkTrackAlbum, state.application, state.title, state.artist, state.album);
        artworkTrackChanged = !artworkTrackKey.isEmpty() && !sameTrack;
        previousArtworkTrackAlbum = artworkTrackAlbum;
        artworkTrackApplication = state.application;
        artworkTrackTitle = state.title;
        artworkTrackArtist = state.artist;
        artworkTrackAlbum = state.album;
        artworkTrackKey = MediaArtworkBindingPolicy.rejectionKey(
                state.application, state.title, state.artist, state.album);
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
        releaseRenderedArtwork();
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
        observedArtworkBitmap = null;
        observedArtworkFingerprint = 0L;
        renderedArtworkBitmap = null;
        renderedArtworkFingerprint = 0L;
        renderedArtworkOwned = false;
        artworkRenderInitialized = false;
        artworkTrackChanged = false;
        // Keep a rejected previous-track fingerprint across a settings/layout rebuild. The
        // MediaSession may keep publishing that same stale bitmap for minutes; rebuilding the
        // panel must not admit it as the new track's cover on the next one-second snapshot.
        // A genuinely different fingerprint clears the rejection in applyArtwork().
        playPauseRenderInitialized = false;
        renderedPlayPauseTint = 0;
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
            LauncherGlobalElementTag.attach(view, LauncherLayoutStore.MEDIA,
                    element.id, spec.label);
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
                // Artwork is an independent HOME widget. Show the complete cover by default;
                // users who deliberately want a crop can choose CROP in the widget's long-press
                // settings without permanently losing pixels at every other size.
                artwork.setScaleType(ImageView.ScaleType.FIT_CENTER);
                artwork.setAdjustViewBounds(false);
                artwork.setContentDescription("Обложка");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    artwork.setClipToOutline(true);
                    artwork.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                }
                return artwork;
            case MediaPanelConfig.TITLE:
                title = marqueeText(scaleSp(23, element.scalePercent), color(config.titleColor,
                        Color.WHITE), true);
                title.setContentDescription("Название композиции");
                return title;
            case MediaPanelConfig.ARTIST:
                artist = marqueeText(scaleSp(18, element.scalePercent), color(config.secondaryColor,
                        Color.LTGRAY), false);
                artist.setContentDescription("Исполнитель");
                return artist;
            case MediaPanelConfig.ALBUM:
                album = marqueeText(scaleSp(15, element.scalePercent),
                        withAlpha(color(config.secondaryColor, Color.LTGRAY), 210), false);
                album.setContentDescription("Альбом");
                return album;
            case MediaPanelConfig.APPLICATION:
                application = text(scaleSp(13, element.scalePercent),
                        withAlpha(color(config.secondaryColor, Color.LTGRAY), 190), false);
                application.setContentDescription("Музыкальное приложение");
                return application;
            case MediaPanelConfig.PROGRESS:
                return progressElement(element.scalePercent, element.progressBarHeightDp);
            case MediaPanelConfig.PREVIOUS:
                return button(R.drawable.ic_media_previous, "Предыдущий трек",
                        controls == null || layoutEditor != null
                                ? null : v -> controls.previous(), element.scalePercent);
            case MediaPanelConfig.PLAY_PAUSE:
                playPause = button(R.drawable.ic_media_play, "Играть или поставить на паузу",
                        controls == null || layoutEditor != null
                                ? null : v -> controls.playPause(), element.scalePercent);
                return playPause;
            case MediaPanelConfig.NEXT:
                return button(R.drawable.ic_media_next, "Следующий трек",
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
    private View progressElement(int scalePercent, int progressBarHeightDp) {
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(0, 0, 0, 0);
        root.setBackground(null);
        timeline = text(scaleSp(13, scalePercent), color(config.secondaryColor, Color.LTGRAY),
                false);
        timeline.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL);
        timeline.setContentDescription("Позиция и длительность трека");
        root.addView(timeline, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progress = new ResponsiveProgressBar(getContext(),
                color(config.controlColor, Color.WHITE),
                withAlpha(color(config.secondaryColor, Color.LTGRAY), 95));
        progress.setMax(1_000);
        progress.setMinimumHeight(dp(Math.max(2, Math.min(40, progressBarHeightDp))));
        root.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @NonNull
    private View volumeElement(int scalePercent) {
        LinearLayout root = new LinearLayout(getContext());
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setContentDescription("Громкость музыки");
        root.setPadding(0, 0, 0, 0);
        root.setBackground(null);
        ImageView icon = new ImageView(getContext());
        icon.setImageResource(R.drawable.ic_media_volume);
        icon.setColorFilter(color(config.controlColor, Color.WHITE));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(icon, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, .18f));
        volume = new ResponsiveVolumeBar(getContext(),
                color(config.controlColor, Color.WHITE),
                withAlpha(color(config.secondaryColor, Color.LTGRAY), 95));
        volume.setMax(100);
        volume.setEnabled(controls != null && layoutEditor == null);
        volume.setOnProgressChanged((value, fromUser) -> {
            if (volumeLabel != null) setTextIfChanged(volumeLabel, value + "%");
            if (fromUser && controls != null && layoutEditor == null) setSystemVolume(value);
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        root.addView(volume, seekLp);
        volumeLabel = text(scaleSp(13, scalePercent),
                color(config.secondaryColor, Color.LTGRAY), false);
        volumeLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeLabel.setMinWidth(dp(46));
        root.addView(volumeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
        if (volume != null && !volume.isPressed() && volume.getProgress() != volumePercent) {
            volume.setProgress(volumePercent);
        }
        if (volumeLabel != null) setTextIfChanged(volumeLabel, volumePercent + "%");
    }

    private void configurePanelClick() {
        if (controls == null || layoutEditor != null) {
            setOnClickListener(null);
            setClickable(false);
            return;
        }
        setClickable(true);
        setOnClickListener(view -> {
            if (!controls.openPlayer()) {
                Toast.makeText(getContext(), "Выбранный музыкальный плеер не найден",
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
        value.setPadding(0, 0, 0, 0);
        if (bold) value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return value;
    }

    @NonNull
    private MarqueeOutlineTextView marqueeText(float sizeSp, int textColor, boolean bold) {
        MarqueeOutlineTextView value = new MarqueeOutlineTextView(getContext());
        value.setGravity(Gravity.CENTER_VERTICAL);
        value.setTextSize(sizeSp);
        value.setTextColor(textColor);
        value.setSingleLine(true);
        value.setPadding(0, 0, 0, 0);
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
                    config.element(MediaPanelConfig.TITLE).marqueeEnabled);
        }
        if (artist != null) {
            applyMediaText(artist, artistValue,
                    config.element(MediaPanelConfig.ARTIST).marqueeEnabled);
            setVisibilityIfChanged(artist, globalEditPreview || !artistValue.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
        if (album != null) {
            applyMediaText(album, albumValue,
                    config.element(MediaPanelConfig.ALBUM).marqueeEnabled);
            setVisibilityIfChanged(album, globalEditPreview || !albumValue.isEmpty()
                    ? View.VISIBLE : View.GONE);
        }
        if (application != null) {
            setTextIfChanged(application, applicationValue);
            setVisibilityIfChanged(application,
                    globalEditPreview || !applicationValue.isEmpty()
                            ? View.VISIBLE : View.GONE);
        }
        if (artwork != null) {
            applyArtwork();
        }
        if (playPause != null) {
            applyPlayPause();
        }
        if (timeline != null) {
            setTextIfChanged(timeline, MediaTimeline.format(positionMs) + " / "
                    + MediaTimeline.format(durationMs));
        }
        if (progress != null) {
            int nextProgress = MediaTimeline.progress(positionMs, durationMs, 1_000);
            if (progress.getProgress() != nextProgress) progress.setProgress(nextProgress);
            View progressRoot = elementViews.get(MediaPanelConfig.PROGRESS);
            if (progressRoot != null) {
                setVisibilityIfChanged(progressRoot,
                        globalEditPreview || durationMs > 0L
                                ? View.VISIBLE : View.GONE);
            }
        }
        updateVolumeUi();
    }

    /**
     * Resets only the line whose value or marquee mode actually changed. Position ticks must not
     * restart all long-title animations because Android treats setText/setEllipsize/setSelected as
     * a new marquee even when the resulting visual value is identical.
     */
    private static void applyMediaText(@NonNull MarqueeOutlineTextView view,
                                       @NonNull String value,
                                       boolean marqueeEnabled) {
        view.setMarqueeEnabled(marqueeEnabled);
        view.setMarqueeText(value);
    }

    /** Applies an expensive bitmap/background change only when the rendered pixels changed. */
    private void applyArtwork() {
        // Cache identity by the immutable metadata wrapper as well as by sampled pixels. The
        // controller can publish the same wrapper on every one-second position tick.
        long fingerprint;
        if (artworkBitmap == observedArtworkBitmap) {
            fingerprint = observedArtworkFingerprint;
        } else {
            fingerprint = artworkFingerprint(artworkBitmap);
            observedArtworkBitmap = artworkBitmap;
            observedArtworkFingerprint = fingerprint;
        }
        if (artworkTrackChanged) {
            // Capture the previous track's rendered pixels even when the first snapshot for the
            // new track already contains its correct new cover.  A vendor session can emit the
            // previous bitmap again after that correct frame; the rejection must survive it.
            rejectedArtworkFingerprint =
                    MediaArtworkBindingPolicy.previousTrackFingerprintToReject(
                            true, previousArtworkTrackAlbum, artworkTrackAlbum,
                            renderedArtworkFingerprint);
        }
        boolean previouslyRejected = MediaArtworkBindingPolicy.isRejectedForCurrentTrack(
                rejectedArtworkFingerprint, fingerprint);
        if (previouslyRejected) {
            // New metadata arrived with the old track's pixels: keep that fingerprint hidden.
            // It stays blocked for this entire track, including after the correct new cover has
            // already appeared, so a late old MediaSession/cache packet cannot flash on screen.
            artworkBitmap = null;
            fingerprint = 0L;
        }
        artworkTrackChanged = false;
        boolean same = artworkRenderInitialized
                && sameArtwork(artworkBitmap, fingerprint,
                renderedArtworkBitmap, renderedArtworkFingerprint);
        if (same && (renderedArtworkOwned || artworkBitmap == renderedArtworkBitmap)) return;

        boolean hadArtwork = artworkRenderInitialized && renderedArtworkBitmap != null;
        boolean hasArtwork = artworkBitmap != null;
        if (!hasArtwork) {
            // Clear both the previous track bitmap and its backing shape. Missing artwork is
            // deliberately an empty transparent grid cell, never a media-icon placeholder.
            artwork.setImageDrawable(null);
            if (hadArtwork || !artworkRenderInitialized) artwork.setBackground(null);
            artwork.clearColorFilter();
            renderedArtworkBitmap = null;
            renderedArtworkOwned = false;
        } else {
            if (!hadArtwork) artwork.setBackground(null);
            android.graphics.Bitmap displayBitmap = copyArtworkForDisplay(artworkBitmap);
            if (displayBitmap == null) {
                // Allocation can fail on very large or vendor hardware-backed bitmaps. In that
                // rare fallback, bind every new wrapper even when its pixels match: the controller
                // owns it and may recycle the previously published wrapper after replacement.
                displayBitmap = artworkBitmap;
                renderedArtworkOwned = false;
            } else {
                renderedArtworkOwned = true;
            }
            artwork.setImageBitmap(displayBitmap);
            artwork.clearColorFilter();
            renderedArtworkBitmap = displayBitmap;
        }
        renderedArtworkFingerprint = fingerprint;
        artworkRenderInitialized = true;
    }

    /**
     * The controller owns Snapshot artwork and recycles obsolete broadcast bitmaps. Keep a private
     * immutable copy in ImageView so skipping a pixel-equivalent replacement can never leave the
     * UI pointing at the controller's subsequently recycled wrapper.
     */
    @Nullable
    private static android.graphics.Bitmap copyArtworkForDisplay(
            @NonNull android.graphics.Bitmap source) {
        try {
            if (source.isRecycled()) return null;
            return source.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private void releaseRenderedArtwork() {
        if (artwork != null && renderedArtworkBitmap != null) {
            artwork.setImageDrawable(null);
        }
        renderedArtworkBitmap = null;
        renderedArtworkFingerprint = 0L;
        renderedArtworkOwned = false;
        artworkRenderInitialized = false;
    }

    /** Keeps vector decoding and tint invalidation out of the once-a-second position tick. */
    private void applyPlayPause() {
        int tint = color(playing ? config.accentColor : config.controlColor, Color.WHITE);
        if (!playPauseRenderInitialized || renderedPlaying != playing) {
            playPause.setImageResource(playing
                    ? R.drawable.ic_media_pause : R.drawable.ic_media_play);
            renderedPlaying = playing;
        }
        if (!playPauseRenderInitialized || renderedPlayPauseTint != tint) {
            playPause.setColorFilter(tint);
            renderedPlayPauseTint = tint;
        }
        playPauseRenderInitialized = true;
    }

    private static boolean sameArtwork(@Nullable android.graphics.Bitmap first,
                                       long firstFingerprint,
                                       @Nullable android.graphics.Bitmap second,
                                       long secondFingerprint) {
        if (first == null || second == null) return first == second;
        try {
            if (first.isRecycled() || second.isRecycled()) return false;
        } catch (RuntimeException ignored) {
            return first == second;
        }
        if (firstFingerprint != 0L && secondFingerprint != 0L) {
            return firstFingerprint == secondFingerprint;
        }
        return first == second;
    }

    /**
     * Binder may return a fresh Bitmap wrapper for unchanged cover pixels. A bounded sample keeps
     * UI identity stable without comparing/allocating the full artwork on the weak head unit.
     */
    private static long artworkFingerprint(@Nullable android.graphics.Bitmap bitmap) {
        if (bitmap == null) return 0L;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0 || bitmap.isRecycled()) return 0L;
            long value = 0xcbf29ce484222325L;
            value = mixArtworkFingerprint(value, width);
            value = mixArtworkFingerprint(value, height);
            int columns = Math.min(9, width);
            int rows = Math.min(9, height);
            for (int row = 0; row < rows; row++) {
                int y = rows == 1 ? 0
                        : (int) ((long) row * (height - 1) / (rows - 1));
                for (int column = 0; column < columns; column++) {
                    int x = columns == 1 ? 0
                            : (int) ((long) column * (width - 1) / (columns - 1));
                    value = mixArtworkFingerprint(value, bitmap.getPixel(x, y));
                }
            }
            return value == 0L ? 1L : value;
        } catch (RuntimeException ignored) {
            try {
                long value = ((long) bitmap.getGenerationId()) & 0xffff_ffffL;
                value = value * 31L + bitmap.getWidth();
                value = value * 31L + bitmap.getHeight();
                return value == 0L ? 1L : value;
            } catch (RuntimeException invalid) {
                return 0L;
            }
        }
    }

    private static long mixArtworkFingerprint(long value, int sample) {
        return (value ^ (((long) sample) & 0xffff_ffffL)) * 0x100000001b3L;
    }

    private static void setTextIfChanged(@NonNull TextView view, @NonNull String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private static void setVisibilityIfChanged(@NonNull View view, int visibility) {
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }

    private void applySurface() {
        // A launcher widget is only content plus its free geometry. Decorative surfaces are
        // independent BACKDROP elements and therefore never appear implicitly behind media.
        setBackground(null);
    }

    private void attachEditorDrag(@NonNull View child, @NonNull String id) {
        child.setClickable(true);
        child.setOnTouchListener(new OnTouchListener() {
            private float touchOffsetX;
            private float touchOffsetY;
            private float downRawX;
            private float downRawY;
            private int startColumn;
            private int startRow;
            private int startColumnSpan;
            private int startRowSpan;
            @NonNull private PanelContentResizeMath.Corner resizeCorner =
                    PanelContentResizeMath.Corner.NONE;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        touchOffsetX = event.getX();
                        touchOffsetY = event.getY();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        MediaElementGridLayout.ElementLayoutParams start =
                                (MediaElementGridLayout.ElementLayoutParams)
                                        view.getLayoutParams();
                        startColumn = start.column;
                        startRow = start.row;
                        startColumnSpan = start.columnSpan;
                        startRowSpan = start.rowSpan;
                        int handle = dp(30);
                        resizeCorner = PanelContentResizeMath.hitCorner(
                                event.getX(), event.getY(), 0f, 0f,
                                view.getWidth(), view.getHeight(), handle);
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
                        if (resizeCorner != PanelContentResizeMath.Corner.NONE) {
                            float cellWidth = grid.getWidth()
                                    / (float) Math.max(1, config.gridColumns);
                            float cellHeight = grid.getHeight()
                                    / (float) Math.max(1, config.gridRows);
                            int deltaColumn = Math.round(
                                    (event.getRawX() - downRawX) / Math.max(1f, cellWidth));
                            int deltaRow = Math.round(
                                    (event.getRawY() - downRawY) / Math.max(1f, cellHeight));
                            PanelContentResizeMath.Result result =
                                    PanelContentResizeMath.resize(resizeCorner,
                                            startColumn, startRow,
                                            startColumnSpan, startRowSpan,
                                            deltaColumn, deltaRow,
                                            config.gridColumns, config.gridRows);
                            MediaPanelConfig.Element current = config.element(id);
                            boolean changed = current.column != result.column
                                    || current.row != result.row
                                    || current.columnSpan != result.columnSpan
                                    || current.rowSpan != result.rowSpan;
                            if (changed && config.setPlacement(id,
                                    result.column, result.row,
                                    result.columnSpan, result.rowSpan)) {
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
                            boolean accepted = config.setPosition(id, lp.column, lp.row);
                            // Config owns collision resolution. Always reapply it so a rejected
                            // drag or a displaced neighbour cannot diverge from the saved grid.
                            syncGridPlacements();
                            LayoutEditor editor = layoutEditor;
                            if (accepted && editor != null) {
                                editor.onLayoutChanged(config.copy(), id, false);
                            }
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
                        resizeCorner = PanelContentResizeMath.Corner.NONE;
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

    /**
     * A real responsive progress bar. Android's stock horizontal ProgressBar keeps a fixed
     * intrinsic track height even when its frame is stretched, which made the resize handles look
     * broken. This drawable uses the full frame assigned below the independently sized text.
     */
    private final class ResponsiveProgressBar extends View {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private int maximum = 100;
        private int value;

        ResponsiveProgressBar(@NonNull Context context, int progressColor, int backgroundColor) {
            super(context);
            progressPaint.setColor(progressColor);
            backgroundPaint.setColor(backgroundColor);
        }

        void setMax(int maximum) {
            this.maximum = Math.max(1, maximum);
            setProgress(value);
        }

        void setProgress(int progress) {
            int next = Math.max(0, Math.min(maximum, progress));
            if (value == next) return;
            value = next;
            invalidate();
        }

        int getProgress() {
            return value;
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) return;
            float radius = height / 2f;
            bounds.set(0f, 0f, width, height);
            canvas.drawRoundRect(bounds, radius, radius, backgroundPaint);
            float progressWidth = width * value / Math.max(1f, maximum);
            if (progressWidth <= 0f) return;
            bounds.set(0f, 0f, Math.max(Math.min(width, progressWidth), height), height);
            canvas.save();
            canvas.clipRect(0f, 0f, progressWidth, height);
            canvas.drawRoundRect(bounds, radius, radius, progressPaint);
            canvas.restore();
        }
    }

    /** Touch-capable volume track whose visual height follows its free frame. */
    private final class ResponsiveVolumeBar extends View {
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private int maximum = 100;
        private int value;
        @Nullable private VolumeChangeListener listener;

        ResponsiveVolumeBar(@NonNull Context context, int progressColor, int backgroundColor) {
            super(context);
            progressPaint.setColor(progressColor);
            backgroundPaint.setColor(backgroundColor);
            setClickable(true);
            setFocusable(true);
        }

        void setMax(int maximum) {
            this.maximum = Math.max(1, maximum);
            setProgress(value);
        }

        void setProgress(int progress) {
            setProgress(progress, false);
        }

        int getProgress() {
            return value;
        }

        void setOnProgressChanged(@Nullable VolumeChangeListener listener) {
            this.listener = listener;
        }

        private void setProgress(int progress, boolean fromUser) {
            int next = Math.max(0, Math.min(maximum, progress));
            if (value == next) return;
            value = next;
            invalidate();
            VolumeChangeListener callback = listener;
            if (callback != null) callback.onProgressChanged(value, fromUser);
        }

        @Override protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0f || height <= 0f) return;
            float thumbRadius = Math.max(dp(4), height * .34f);
            thumbRadius = Math.min(thumbRadius, Math.min(height / 2f, width / 2f));
            float left = thumbRadius;
            float right = Math.max(left, width - thumbRadius);
            float centerY = height / 2f;
            float trackHeight = Math.max(dp(3), height * .28f);
            trackHeight = Math.min(trackHeight, height);
            float trackRadius = trackHeight / 2f;
            bounds.set(left, centerY - trackHeight / 2f,
                    right, centerY + trackHeight / 2f);
            canvas.drawRoundRect(bounds, trackRadius, trackRadius, backgroundPaint);
            float x = left + (right - left) * value / Math.max(1f, maximum);
            bounds.right = x;
            canvas.drawRoundRect(bounds, trackRadius, trackRadius, progressPaint);
            canvas.drawCircle(x, centerY, thumbRadius, progressPaint);
        }

        @Override public boolean onTouchEvent(@NonNull MotionEvent event) {
            if (!isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    setPressed(true);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    updateFromTouch(event.getX());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    updateFromTouch(event.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    updateFromTouch(event.getX());
                    setPressed(false);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    setPressed(false);
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromTouch(float x) {
            float thumbRadius = Math.max(dp(4), getHeight() * .34f);
            thumbRadius = Math.min(thumbRadius,
                    Math.min(getHeight() / 2f, getWidth() / 2f));
            float available = Math.max(1f, getWidth() - thumbRadius * 2f);
            float fraction = Math.max(0f, Math.min(1f, (x - thumbRadius) / available));
            setProgress(Math.round(fraction * maximum), true);
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
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
