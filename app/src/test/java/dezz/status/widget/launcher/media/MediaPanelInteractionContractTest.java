package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the touch split: surface opens the player, child controls keep their own actions. */
public final class MediaPanelInteractionContractTest {
    @Test public void panelSurfaceOpensYandexMusic() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("setOnClickListener(view ->"));
        assertTrue(source.contains("MediaAppLauncher.launchYandexMusic(getContext())"));
    }

    @Test public void mediaButtonsHaveTransparentBackgroundAndConsumeClick() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int buttonStart = source.indexOf("private ImageButton button(");
        int buttonEnd = source.indexOf("private void applySnapshot()", buttonStart);
        String button = source.substring(buttonStart, buttonEnd);
        assertTrue(button.contains("setBackgroundColor(Color.TRANSPARENT)"));
        assertTrue(button.contains("setOnClickListener(listener)"));
        assertFalse(button.contains("MediaAppLauncher.launchYandexMusic"));
    }

    @Test public void volumeWritesMusicStreamAndListensForSystemChanges() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("manager.setStreamVolume(AudioManager.STREAM_MUSIC"));
        assertTrue(source.contains("android.media.VOLUME_CHANGED_ACTION"));
        assertTrue(source.contains("syncSystemVolume()"));
    }

    @Test public void everySnapshotReappliesTrackArtworkAndPlayback() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int start = source.indexOf("public void setSnapshot(");
        int end = source.indexOf("/** Realistic content", start);
        String method = source.substring(start, end);
        assertTrue(method.contains("titleValue = state.title"));
        assertTrue(method.contains("artworkBitmap = state.artwork"));
        assertTrue(method.contains("playing = state.playing"));
        assertTrue(method.contains("applySnapshot()"));
    }

    @Test public void missingArtworkClearsOldBitmapWithoutPlaceholder() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int start = source.indexOf("private void applyArtwork()");
        int end = source.indexOf("private void applyPlayPause()", start);
        String artwork = source.substring(start, end);
        assertTrue(artwork.contains("artwork.setImageDrawable(null)"));
        assertTrue(artwork.contains("artwork.setBackground(null)"));
        assertFalse(artwork.contains("setImageResource"));
    }

    @Test public void transportControlsScheduleOnlyBoundedReconciliation() throws IOException {
        String source = source("dezz/status/widget/launcher/LauncherMediaController.java");
        assertTrue(source.contains("scheduleCommandReconcile()"));
        assertTrue(source.contains("COMMAND_RECONCILE_FAST_MS = 140L"));
        assertTrue(source.contains("COMMAND_RECONCILE_SETTLED_MS = 720L"));
        assertTrue(source.contains("COMMAND_RECONCILE_FINAL_MS = 2_400L"));
        assertTrue(source.contains("SESSION_REFRESH_PLAYING_MS = 2_500L"));
        assertTrue(source.contains("SESSION_REFRESH_PAUSED_MS = 20_000L"));
        assertTrue(source.contains(
                "mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_FAST_MS)"));
        assertTrue(source.contains(
                "mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_SETTLED_MS)"));
        assertTrue(source.contains(
                "mainHandler.postDelayed(commandReconcile, COMMAND_RECONCILE_FINAL_MS)"));
        assertTrue(source.contains("MediaStateFreshness.shouldRefreshSession("));
        assertTrue(source.contains("lastSessionRefreshElapsedMs = nowElapsed"));
        assertTrue(source.contains(
                "playing ? UI_TICK_MS : SESSION_REFRESH_PAUSED_MS"));
    }

    @Test public void asyncArtworkUsesIndependentClocksAndTrackGate() throws IOException {
        String source = source("dezz/status/widget/launcher/LauncherMediaController.java");
        assertTrue(source.contains("contentChangedElapsedMs"));
        assertTrue(source.contains("playbackChangedElapsedMs"));
        assertTrue(source.contains("artworkChangedElapsedMs"));
        assertTrue(source.contains("if (!sameTrack(content, playback)) playback = content"));
        assertTrue(source.contains("if (!sameTrack(content, artwork)) artwork = content"));
        assertTrue(source.contains(
                "MediaState sessionArtwork = sameCorrelatedTrack(content, session)"));
        assertTrue(source.indexOf("replaceBroadcastState(state)")
                < source.indexOf("MediaBroadcastRepository.processAsync(context, intent, null)",
                source.indexOf("private void receiveBroadcast")));
        assertTrue(source.contains("boolean artworkChanged = previous == null"));
        assertTrue(source.contains("MediaStateFreshness.artworkChanged("));
        assertTrue(source.contains("MediaStateFreshness.shouldDisplaySessionArtwork("));
        assertTrue(source.contains("MediaStateFreshness.incomingArtworkWins("));
        assertTrue(source.contains("mixArtworkFingerprint"));
        assertTrue(source.contains(
                "MediaStateFreshness.changedAt(artworkChanged, receivedElapsed"));
    }

    @Test public void marqueeIsOverflowOnlyAndResetsWhenTextChanges() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("TextUtils.TruncateAt.MARQUEE"));
        assertTrue(source.contains("view.setMarqueeRepeatLimit(-1)"));
        assertTrue(source.contains(
                "if ((textChanged || modeChanged) && view.isSelected()) view.setSelected(false)"));
        assertTrue(source.contains("if (textChanged) view.setText(value)"));
        assertTrue(source.contains(
                "if (view.isSelected() != selected) view.setSelected(selected)"));
        assertFalse(source.contains("restartTextMarquee"));
        assertFalse(source.contains("forceRestart"));
        String settings = source("dezz/status/widget/MediaPanelSettingsActivity.java");
        assertTrue(settings.contains("Прокручивать длинный текст"));
        assertTrue(settings.contains("config.setMarqueeEnabled(element.id, checked)"));
    }

    @Test public void positionTicksDoNotRebindStableArtworkOrPlaybackIcon()
            throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int artworkStart = source.indexOf("private void applyArtwork()");
        int artworkEnd = source.indexOf("private void applyPlayPause()", artworkStart);
        String artwork = source.substring(artworkStart, artworkEnd);
        assertTrue(artwork.contains(
                "if (same && (renderedArtworkOwned || artworkBitmap == renderedArtworkBitmap))"));
        assertTrue(artwork.contains("sameArtwork(artworkBitmap, fingerprint,"));
        assertTrue(artwork.contains("artwork.setImageBitmap(displayBitmap)"));

        int playbackStart = artworkEnd;
        int playbackEnd = source.indexOf("private static boolean sameArtwork", playbackStart);
        String playback = source.substring(playbackStart, playbackEnd);
        assertTrue(playback.contains(
                "if (!playPauseRenderInitialized || renderedPlaying != playing)"));
        assertTrue(playback.contains(
                "if (!playPauseRenderInitialized || renderedPlayPauseTint != tint)"));
        assertFalse(playback.contains("playPause.setImageResource(playing\n"
                + "                    ? R.drawable.ic_media_pause : R.drawable.ic_media_play);\n"
                + "        playPause.setColorFilter"));
    }

    @Test public void artworkViewOwnsStableCopyOrRebindsEveryExternalReplacement()
            throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int artworkStart = source.indexOf("private void applyArtwork()");
        int playbackStart = source.indexOf("private void applyPlayPause()", artworkStart);
        String artwork = source.substring(artworkStart, playbackStart);
        assertTrue(artwork.contains("copyArtworkForDisplay(artworkBitmap)"));
        assertTrue(artwork.contains("renderedArtworkOwned = true"));
        assertTrue(artwork.contains("renderedArtworkOwned = false"));
        assertTrue(artwork.contains(
                "renderedArtworkOwned || artworkBitmap == renderedArtworkBitmap"));
        assertTrue(artwork.contains("renderedArtworkBitmap = displayBitmap"));
        assertFalse(artwork.contains("if (same) return;"));

        int copyStart = source.indexOf("private static android.graphics.Bitmap "
                + "copyArtworkForDisplay(");
        int releaseStart = source.indexOf("private void releaseRenderedArtwork()", copyStart);
        String ownership = source.substring(copyStart, releaseStart);
        assertTrue(ownership.contains(
                "source.copy(android.graphics.Bitmap.Config.ARGB_8888, false)"));
        assertTrue(ownership.contains("if (source.isRecycled()) return null"));
    }

    @Test public void stableAuxiliaryValuesSkipTextProgressAndVisibilityWrites()
            throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains(
                "if (!TextUtils.equals(view.getText(), value)) view.setText(value)"));
        assertTrue(source.contains(
                "if (view.getVisibility() != visibility) view.setVisibility(visibility)"));
        assertTrue(source.contains(
                "if (progress.getProgress() != nextProgress) progress.setProgress(nextProgress)"));
        assertTrue(source.contains(
                "volume.getProgress() != volumePercent"));
    }

    @Test public void homePanelExposesSameGridForMoveAndResizeEditing() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("public void setInPlaceEditMode("));
        assertTrue(source.contains("grid.setGridSize(config.gridColumns, config.gridRows)"));
        assertTrue(source.contains("grid.moveToPixel(view, left, top)"));
        assertTrue(source.contains("grid.resizeToPixel(view, right, bottom)"));
        assertTrue(source.contains("config.setSpan(id, lp.columnSpan, lp.rowSpan)"));
    }

    @Test public void settingsOpenHomeContentEditorAndKeepPreviewReadOnly() throws IOException {
        String settings = source("dezz/status/widget/MediaPanelSettingsActivity.java");
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(settings.contains("LauncherActivity.EXTRA_EDIT_MODE"));
        assertTrue(settings.contains("LauncherActivity.EXTRA_EDIT_MEDIA_CONTENT"));
        assertTrue(settings.contains("Расположение элементов внутри панели на HOME"));
        assertTrue(settings.contains("ПРЕДПРОСМОТР · ТОЛЬКО ПРОСМОТР"));
        assertFalse(settings.contains("preview.setLayoutEditor"));
        assertTrue(launcher.contains("private void setMediaContentEditMode(boolean enabled)"));
        assertTrue(launcher.contains("mediaPanel.setInPlaceEditMode(enabled)"));
        assertTrue(launcher.contains("if (enabled && editMode) setEditMode(false)"));
    }

    @Test public void manifestExposesLeanbackCandidatesForDynamicDiscovery() throws IOException {
        String manifest = manifest();
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"));
        assertTrue(manifest.contains("android.intent.category.LEANBACK_LAUNCHER"));
    }

    @Test public void loadedBroadcastHasOneShotRuntimeExpiry() throws IOException {
        String source = source("dezz/status/widget/launcher/LauncherMediaController.java");
        assertTrue(source.contains("private final Runnable broadcastExpiry"));
        assertTrue(source.contains("MediaBroadcastFreshness.expired("));
        assertTrue(source.contains("MediaBroadcastFreshness.remaining("));
        assertTrue(source.contains("scheduleBroadcastExpiry()"));
        assertTrue(source.contains("mainHandler.removeCallbacks(broadcastExpiry)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String manifest() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "AndroidManifest.xml");
        Path fromApp = Paths.get("src", "main", "AndroidManifest.xml");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
