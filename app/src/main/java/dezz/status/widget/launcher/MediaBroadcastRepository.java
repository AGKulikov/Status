/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Durable cache for media broadcasts received while HOME is not on screen. */
final class MediaBroadcastRepository {
    static final String ACTION_CACHE_UPDATED =
            "dezz.status.widget.launcher.MEDIA_BROADCAST_CACHE_UPDATED";

    private static final String PREFS = "launcher_media_broadcast_v1";
    private static final String ARTWORK_FILE = "launcher_media_broadcast_artwork.png";
    private static final String PREF_ARTWORK_SIGNATURE = "artworkSignature";
    private static final String PREF_ARTWORK_TRACK_SIGNATURE = "artworkTrackSignature";
    private static final String PREF_RECEIVED_ELAPSED = "receivedElapsed";
    private static final String PREF_CONTENT_CHANGED_ELAPSED = "contentChangedElapsed";
    private static final String PREF_PLAYBACK_CHANGED_ELAPSED = "playbackChangedElapsed";
    private static final String PREF_ARTWORK_CHANGED_ELAPSED = "artworkChangedElapsed";
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_ARTWORK_BYTES = 4 * 1024 * 1024;
    // Rich-media broadcasts may arrive every second on low-memory head units. The artwork shown
    // by the launcher is much smaller than a full-screen image, so retaining more pixels only
    // increases decode, PNG and GC cost without improving the UI.
    private static final int MAX_CACHED_ARTWORK_EDGE = 640;
    private static final long MAX_CACHED_ARTWORK_PIXELS =
            (long) MAX_CACHED_ARTWORK_EDGE * MAX_CACHED_ARTWORK_EDGE;
    private static final int MAX_PENDING_UPDATES = 4;
    private static final long BOOT_EPOCH_TOLERANCE_MS = 5L * 60L * 1_000L;
    private static final Object WRITE_SUBMIT_LOCK = new Object();
    private static final ThreadPoolExecutor WRITE_EXECUTOR = createWriteExecutor();
    private static final ThreadPoolExecutor READ_EXECUTOR = createReadExecutor();

    private static ThreadPoolExecutor createWriteExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_UPDATES), runnable -> {
                Thread thread = backgroundThread(runnable, "media-broadcast-cache");
                thread.setDaemon(true);
                return thread;
            });
        executor.allowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler((newest, owner) -> {
            Runnable oldest = null;
            for (Runnable queued : owner.getQueue()) {
                if (queued instanceof CacheTask && ((CacheTask) queued).supersedable
                        && owner.getQueue().remove(queued)) {
                    oldest = queued;
                    break;
                }
            }
            if (newest instanceof CacheTask && ((CacheTask) newest).supersedable
                    && oldest == null) {
                ((CacheTask) newest).finish();
                return;
            }
            if (oldest == null) oldest = owner.getQueue().poll();
            if (oldest instanceof CacheTask) ((CacheTask) oldest).finish();
            if (owner.isShutdown() || !owner.getQueue().offer(newest)) {
                if (newest instanceof CacheTask) ((CacheTask) newest).finish();
            }
        });
        return executor;
    }

    private static ThreadPoolExecutor createReadExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = backgroundThread(runnable, "media-broadcast-read");
                thread.setDaemon(true);
                return thread;
            });
        executor.allowCoreThreadTimeOut(true);
        // Reads are snapshots and never carry BroadcastReceiver PendingResults. Retain only the
        // newest request without allowing a UI refresh to evict the latest UPDATE/CLEAR write.
        executor.setRejectedExecutionHandler((newest, owner) -> {
            Runnable dropped = owner.getQueue().poll();
            if (dropped instanceof CacheReadTask) ((CacheReadTask) dropped).cancel();
            if (owner.isShutdown() || !owner.getQueue().offer(newest)) {
                if (newest instanceof CacheReadTask) ((CacheReadTask) newest).cancel();
            }
        });
        return executor;
    }

    @NonNull
    private static Thread backgroundThread(@NonNull Runnable runnable, @NonNull String name) {
        return new Thread(() -> {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); }
            catch (RuntimeException ignored) { }
            runnable.run();
        }, name);
    }

    /** Ensures every manifest receiver PendingResult is finished, including dropped burst jobs. */
    private static final class CacheTask implements Runnable {
        @NonNull private final Runnable work;
        @Nullable private Runnable finished;
        final boolean supersedable;

        CacheTask(@NonNull Runnable work, @Nullable Runnable finished, boolean supersedable) {
            this.work = work;
            this.finished = finished;
            this.supersedable = supersedable;
        }

        @Override public void run() {
            try {
                work.run();
            } catch (RuntimeException ignored) {
                // Exported media broadcasts are untrusted input. A malformed parcel or URI must
                // not terminate the launcher process through an uncaught worker exception.
            } finally {
                finish();
            }
        }

        synchronized void finish() {
            Runnable value = finished;
            finished = null;
            if (value != null) {
                try { value.run(); }
                catch (RuntimeException ignored) { }
            }
        }
    }

    /** A superseded read still completes its controller's single-flight lifecycle. */
    private static final class CacheReadTask implements Runnable {
        @NonNull private final Context context;
        @Nullable private StateCallback callback;

        CacheReadTask(@NonNull Context context, @NonNull StateCallback callback) {
            this.context = context;
            this.callback = callback;
        }

        @Override public void run() {
            StateCallback target;
            synchronized (this) {
                target = callback;
                callback = null;
            }
            if (target == null) return;
            State state = null;
            boolean delivered = false;
            try {
                state = read(context);
                target.onRead(state);
                delivered = true;
            } catch (RuntimeException ignored) {
                if (!delivered) {
                    try { target.onRead(null); }
                    catch (RuntimeException ignoredAgain) {}
                }
            } finally {
                if (!delivered && state != null && state.artwork != null
                        && !state.artwork.isRecycled()) state.artwork.recycle();
            }
        }

        void cancel() {
            StateCallback target;
            synchronized (this) {
                target = callback;
                callback = null;
            }
            if (target != null) {
                try { target.onRead(null); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    static final class State {
        @NonNull final String title;
        @NonNull final String artist;
        @NonNull final String album;
        @NonNull final String packageName;
        @Nullable final Bitmap artwork;
        final long durationMs;
        final long positionMs;
        final boolean playing;
        final long timestampWallMs;
        final long observedWallMs;
        final long receivedElapsedMs;
        final long contentChangedElapsedMs;
        final long playbackChangedElapsedMs;
        final long artworkChangedElapsedMs;

        State(@NonNull String title, @NonNull String artist, @NonNull String album,
              @NonNull String packageName, @Nullable Bitmap artwork, long durationMs,
              long positionMs, boolean playing, long timestampWallMs, long receivedElapsedMs,
              long contentChangedElapsedMs, long playbackChangedElapsedMs,
              long artworkChangedElapsedMs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.packageName = packageName;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.playing = playing;
            this.timestampWallMs = timestampWallMs;
            this.observedWallMs = System.currentTimeMillis()
                    - Math.max(0L, SystemClock.elapsedRealtime() - receivedElapsedMs);
            this.receivedElapsedMs = receivedElapsedMs;
            this.contentChangedElapsedMs = contentChangedElapsedMs;
            this.playbackChangedElapsedMs = playbackChangedElapsedMs;
            this.artworkChangedElapsedMs = artworkChangedElapsedMs;
        }
    }

    private static final class PendingUpdate {
        @NonNull final String title;
        @NonNull final String artist;
        @NonNull final String album;
        @NonNull final String packageName;
        @NonNull final String artworkUri;
        @Nullable final byte[] artworkBytes;
        final boolean artworkExpected;
        final long durationMs;
        final long positionMs;
        final boolean playing;
        final long timestampWallMs;
        final long observedWallMs;
        final long receivedElapsedMs;
        final boolean titlePresent;
        final boolean artistPresent;
        final boolean albumPresent;
        final boolean packagePresent;
        final boolean durationPresent;
        final boolean positionPresent;
        final boolean playingPresent;
        final boolean artworkDirectivePresent;

        PendingUpdate(@NonNull Intent intent) {
            titlePresent = hasExtra(intent, "title");
            artistPresent = hasExtra(intent, "artist");
            albumPresent = hasExtra(intent, "album");
            packagePresent = hasExtra(intent, "package_name");
            durationPresent = hasExtra(intent, "duration_ms");
            positionPresent = hasExtra(intent, "position_ms");
            playingPresent = hasExtra(intent, "is_playing");
            artworkDirectivePresent = hasExtra(intent, "artwork_uri")
                    || hasExtra(intent, "artwork_bytes") || hasExtra(intent, "has_artwork");
            title = clean(stringExtra(intent, "title"));
            artist = clean(stringExtra(intent, "artist"));
            album = clean(stringExtra(intent, "album"));
            packageName = clean(stringExtra(intent, "package_name"));
            artworkUri = clean(stringExtra(intent, "artwork_uri"));
            artworkBytes = byteArrayExtra(intent, "artwork_bytes");
            artworkExpected = !artworkUri.isEmpty()
                    || booleanExtra(intent, "has_artwork", artworkBytes != null);
            durationMs = Math.max(0L, longExtra(intent, "duration_ms", 0L));
            positionMs = Math.max(0L, longExtra(intent, "position_ms", 0L));
            playing = booleanExtra(intent, "is_playing", false);
            long now = System.currentTimeMillis();
            observedWallMs = now;
            long timestamp = longExtra(intent, "timestamp", now);
            if (timestamp <= 0L || timestamp > now + 60_000L
                    || timestamp < now - 24L * 60L * 60L * 1_000L) timestamp = now;
            timestampWallMs = timestamp;
            receivedElapsedMs = SystemClock.elapsedRealtime();
        }

        boolean isPositionOnly() {
            return !titlePresent && !artistPresent && !albumPresent && !packagePresent
                    && !durationPresent && !artworkDirectivePresent;
        }

        /** Presence-aware merge performed by the serialized writer against its latest state. */
        PendingUpdate(@NonNull PendingUpdate incoming,
                      @NonNull SharedPreferences previous) {
            title = incoming.titlePresent ? incoming.title : previous.getString("title", "");
            artist = incoming.artistPresent
                    ? incoming.artist : previous.getString("artist", "");
            album = incoming.albumPresent ? incoming.album : previous.getString("album", "");
            packageName = incoming.packagePresent
                    ? incoming.packageName : previous.getString("package", "");
            artworkUri = incoming.artworkUri;
            artworkBytes = incoming.artworkBytes;
            artworkExpected = incoming.artworkDirectivePresent
                    ? incoming.artworkExpected : previous.getBoolean("hasArtwork", false);
            durationMs = incoming.durationPresent ? incoming.durationMs
                    : Math.max(0L, previous.getLong("duration", 0L));
            positionMs = incoming.positionPresent ? incoming.positionMs
                    : Math.max(0L, previous.getLong("position", 0L));
            playing = incoming.playingPresent
                    ? incoming.playing : previous.getBoolean("playing", false);
            timestampWallMs = incoming.timestampWallMs;
            observedWallMs = incoming.observedWallMs;
            receivedElapsedMs = incoming.receivedElapsedMs;
            titlePresent = artistPresent = albumPresent = packagePresent = true;
            durationPresent = positionPresent = playingPresent = true;
            artworkDirectivePresent = incoming.artworkDirectivePresent;
        }
    }

    private MediaBroadcastRepository() {}

    interface StateCallback {
        void onRead(@Nullable State state);
    }

    /** Reads preferences and decodes artwork on the cache worker, never on HOME's UI thread. */
    static void readAsync(@NonNull Context context, @NonNull StateCallback callback) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) applicationContext = context;
        Context targetContext = applicationContext;
        READ_EXECUTOR.execute(new CacheReadTask(targetContext, callback));
    }

    /** Copies all extras synchronously, then performs bitmap and disk work off the main thread. */
    static void processAsync(@NonNull Context rawContext, @NonNull Intent intent,
                             @Nullable Runnable finished) {
        Context applicationContext = rawContext.getApplicationContext();
        final Context context = applicationContext == null ? rawContext : applicationContext;
        String action = intent.getAction();
        if (LauncherMediaController.ACTION_MEDIA_CLEAR.equals(action)
                || LauncherMediaController.ACTION_MEDIA_CLEAR_DEBUG.equals(action)) {
            synchronized (WRITE_SUBMIT_LOCK) {
                WRITE_EXECUTOR.execute(new CacheTask(() -> clearNow(context),
                        finished, false));
            }
            return;
        }
        if (!LauncherMediaController.ACTION_MEDIA_UPDATE.equals(action)
                && !LauncherMediaController.ACTION_MEDIA_UPDATE_DEBUG.equals(action)) {
            if (finished != null) finished.run();
            return;
        }
        final PendingUpdate update;
        try {
            update = new PendingUpdate(intent);
        } catch (RuntimeException error) {
            if (finished != null) finished.run();
            return;
        }
        synchronized (WRITE_SUBMIT_LOCK) {
            WRITE_EXECUTOR.execute(new CacheTask(() -> {
                Bitmap artwork = null;
                try {
                    Context cacheContext = storage(context);
                    SharedPreferences preferences = cacheContext.getSharedPreferences(
                            PREFS, Context.MODE_PRIVATE);
                    File image = new File(cacheContext.getFilesDir(), ARTWORK_FILE);
                    PendingUpdate effective = new PendingUpdate(update, preferences);
                    String trackSignature = MediaArtworkPolicy.trackSignature(
                            effective.packageName, effective.title,
                            effective.artist, effective.album);
                    boolean sourceAvailable = !effective.artworkUri.isEmpty()
                            || (effective.artworkBytes != null
                            && effective.artworkBytes.length > 0);
                    String sourceSignature = MediaArtworkPolicy.sourceSignature(
                            effective.artworkUri, effective.artworkBytes, trackSignature);
                    MediaArtworkPolicy.Action artworkAction = MediaArtworkPolicy.decide(
                            effective.artworkExpected, sourceAvailable, sourceSignature,
                            trackSignature, preferences.getBoolean("hasArtwork", false),
                            isArtworkFileValid(image),
                            preferences.getString(PREF_ARTWORK_SIGNATURE, ""),
                            preferences.getString(PREF_ARTWORK_TRACK_SIGNATURE, ""));
                    artwork = artworkAction == MediaArtworkPolicy.Action.DECODE
                            ? decodeArtwork(context, effective) : null;
                    saveNow(context, effective, artworkAction, sourceSignature,
                            trackSignature, artwork);
                } finally {
                    if (artwork != null && !artwork.isRecycled()) artwork.recycle();
                }
            }, finished, update.isPositionOnly()));
        }
    }

    @Nullable
    static State read(@NonNull Context rawContext) {
        Context context = storage(rawContext);
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("available", false)) return null;
        long observedWall = prefs.getLong("observed", 0L);
        long nowWall = System.currentTimeMillis();
        if (observedWall <= 0L || observedWall > nowWall + BOOT_EPOCH_TOLERANCE_MS) return null;
        int currentBoot = bootCount(context);
        if (currentBoot >= 0 && prefs.contains("bootCount")) {
            if (prefs.getInt("bootCount", -1) != currentBoot) return null;
        } else {
            long savedEpoch = prefs.getLong("bootEpoch", Long.MIN_VALUE);
            if (savedEpoch == Long.MIN_VALUE
                    || Math.abs(savedEpoch - bootEpochMillis()) > BOOT_EPOCH_TOLERANCE_MS) {
                return null;
            }
        }
        boolean playing = prefs.getBoolean("playing", false);
        long age = Math.max(0L, nowWall - observedWall);
        boolean unknown = prefs.getString("title", "").trim().isEmpty()
                && prefs.getString("artist", "").trim().isEmpty()
                && prefs.getString("package", "").trim().isEmpty();
        long duration = Math.max(0L, prefs.getLong("duration", 0L));
        long position = Math.max(0L, prefs.getLong("position", 0L));
        long ttl = MediaBroadcastFreshness.ttl(!unknown, playing, duration, position);
        if (age > ttl) return null;
        Bitmap artwork = null;
        File image = new File(context.getFilesDir(), ARTWORK_FILE);
        if (prefs.getBoolean("hasArtwork", false) && isArtworkFileValid(image)) {
            artwork = decodeFileBounded(image);
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        long reconstructedElapsed = Math.max(0L, nowElapsed - age);
        long receivedElapsed = prefs.getLong(PREF_RECEIVED_ELAPSED, reconstructedElapsed);
        if (receivedElapsed <= 0L || receivedElapsed > nowElapsed + 60_000L) {
            receivedElapsed = reconstructedElapsed;
        }
        long contentChangedElapsed = prefs.getLong(
                PREF_CONTENT_CHANGED_ELAPSED, receivedElapsed);
        long playbackChangedElapsed = prefs.getLong(
                PREF_PLAYBACK_CHANGED_ELAPSED, receivedElapsed);
        long artworkChangedElapsed = prefs.getLong(
                PREF_ARTWORK_CHANGED_ELAPSED, contentChangedElapsed);
        return new State(prefs.getString("title", ""), prefs.getString("artist", ""),
                prefs.getString("album", ""), prefs.getString("package", ""), artwork,
                duration, position,
                playing, prefs.getLong("timestamp", 0L),
                receivedElapsed, contentChangedElapsed, playbackChangedElapsed,
                artworkChangedElapsed);
    }

    private static void saveNow(@NonNull Context context, @NonNull PendingUpdate update,
                                @NonNull MediaArtworkPolicy.Action artworkAction,
                                @NonNull String sourceSignature,
                                @NonNull String trackSignature,
                                @Nullable Bitmap artwork) {
        context = storage(context);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        File image = new File(context.getFilesDir(), ARTWORK_FILE);
        boolean oldArtworkAdvertised = preferences.getBoolean("hasArtwork", false);
        boolean oldHasArtwork = oldArtworkAdvertised && isArtworkFileValid(image);
        String oldArtworkSignature = preferences.getString(PREF_ARTWORK_SIGNATURE, "");

        boolean artworkStored;
        String storedArtworkSignature;
        String storedTrackSignature;
        switch (artworkAction) {
            case REUSE:
                artworkStored = oldHasArtwork && isArtworkFileValid(image);
                storedArtworkSignature = sourceSignature.isEmpty()
                        ? oldArtworkSignature : sourceSignature;
                storedTrackSignature = trackSignature;
                break;
            case DECODE:
                artworkStored = artwork != null && replaceArtwork(context, image, artwork);
                storedArtworkSignature = artworkStored ? sourceSignature : "";
                storedTrackSignature = artworkStored ? trackSignature : "";
                if (!artworkStored) deleteQuietly(image);
                break;
            case CLEAR:
            default:
                artworkStored = false;
                storedArtworkSignature = "";
                storedTrackSignature = "";
                deleteQuietly(image);
                deleteQuietly(new File(context.getFilesDir(), ARTWORK_FILE + ".tmp"));
                break;
        }

        boolean wasAvailable = preferences.getBoolean("available", false);
        boolean presentationChanged = !wasAvailable
                || !update.title.equals(preferences.getString("title", ""))
                || !update.artist.equals(preferences.getString("artist", ""))
                || !update.album.equals(preferences.getString("album", ""))
                || !update.packageName.equals(preferences.getString("package", ""))
                || update.durationMs != preferences.getLong("duration", 0L);
        boolean playbackChanged = !wasAvailable
                || update.playing != preferences.getBoolean("playing", false);
        boolean artworkChanged = oldArtworkAdvertised != artworkStored
                || (artworkStored && (!oldHasArtwork
                || !oldArtworkSignature.equals(storedArtworkSignature)));
        long contentChangedElapsed = MediaStateFreshness.changedAt(
                presentationChanged || artworkChanged, update.receivedElapsedMs,
                preferences.getLong(PREF_CONTENT_CHANGED_ELAPSED, 0L));
        long playbackChangedElapsed = MediaStateFreshness.changedAt(
                playbackChanged, update.receivedElapsedMs,
                preferences.getLong(PREF_PLAYBACK_CHANGED_ELAPSED, 0L));
        long artworkChangedElapsed = MediaStateFreshness.changedAt(
                presentationChanged || artworkChanged || update.artworkDirectivePresent,
                update.receivedElapsedMs,
                preferences.getLong(PREF_ARTWORK_CHANGED_ELAPSED, 0L));

        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("available", true)
                .putString("title", update.title)
                .putString("artist", update.artist)
                .putString("album", update.album)
                .putString("package", update.packageName)
                .putLong("duration", update.durationMs)
                .putLong("position", update.positionMs)
                .putBoolean("playing", update.playing)
                .putLong("timestamp", update.timestampWallMs)
                .putLong("observed", update.observedWallMs)
                .putLong("bootEpoch", bootEpochMillis())
                .putLong(PREF_RECEIVED_ELAPSED, update.receivedElapsedMs)
                .putLong(PREF_CONTENT_CHANGED_ELAPSED, contentChangedElapsed)
                .putLong(PREF_PLAYBACK_CHANGED_ELAPSED, playbackChangedElapsed)
                .putLong(PREF_ARTWORK_CHANGED_ELAPSED, artworkChangedElapsed)
                .putBoolean("hasArtwork", artworkStored);
        if (artworkStored) {
            editor.putString(PREF_ARTWORK_SIGNATURE, storedArtworkSignature)
                    .putString(PREF_ARTWORK_TRACK_SIGNATURE, storedTrackSignature);
        } else {
            editor.remove(PREF_ARTWORK_SIGNATURE).remove(PREF_ARTWORK_TRACK_SIGNATURE);
        }
        int bootCount = bootCount(context);
        if (bootCount >= 0) editor.putInt("bootCount", bootCount);
        else editor.remove("bootCount");
        editor.apply();
        // Position-only packets can arrive every second. The foreground receiver already applies
        // those scalar values immediately and advances them with its ticker; waking the cache
        // reader would decode the same PNG again and cause visible jank on slow head units.
        if (presentationChanged || playbackChanged || artworkChanged) notifyChanged(context);
    }

    private static boolean replaceArtwork(@NonNull Context context, @NonNull File image,
                                          @NonNull Bitmap artwork) {
        File temporary = new File(context.getFilesDir(), ARTWORK_FILE + ".tmp");
        deleteQuietly(temporary);
        boolean compressed = false;
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            if (artwork.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                output.flush();
                output.getFD().sync();
                compressed = true;
            }
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            deleteQuietly(temporary);
        }
        if (!compressed || !isArtworkFileValid(temporary)) {
            deleteQuietly(temporary);
            return false;
        }
        try {
            try {
                Files.move(temporary.toPath(), image.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary.toPath(), image.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            return isArtworkFileValid(image);
        } catch (IOException | RuntimeException ignored) {
            // The old image remains intact when the atomic replacement fails. The caller clears
            // hasArtwork so it can never be paired with metadata for the new track.
            deleteQuietly(temporary);
            return false;
        }
    }

    private static void clearNow(@NonNull Context context) {
        context = storage(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        deleteQuietly(new File(context.getFilesDir(), ARTWORK_FILE));
        deleteQuietly(new File(context.getFilesDir(), ARTWORK_FILE + ".tmp"));
        notifyChanged(context);
    }

    private static void notifyChanged(@NonNull Context context) {
        context.sendBroadcast(new Intent(ACTION_CACHE_UPDATED).setPackage(
                context.getPackageName()));
    }

    private static int bootCount(@NonNull Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | SecurityException ignored) {
            return -1;
        }
    }

    private static long bootEpochMillis() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    @NonNull
    private static Context storage(@NonNull Context context) {
        Context applicationContext = context.getApplicationContext();
        if (applicationContext == null) applicationContext = context;
        return applicationContext.createDeviceProtectedStorageContext();
    }

    @Nullable
    private static Bitmap decodeArtwork(@NonNull Context context,
                                        @NonNull PendingUpdate update) {
        if (!update.artworkUri.isEmpty()) {
            try {
                Uri uri = Uri.parse(update.artworkUri);
                if (uri.getScheme() != null) {
                    try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                        if (input != null) {
                            Bitmap decoded = LauncherMediaController.decodeArtwork(
                                    readBounded(input));
                            decoded = boundArtwork(decoded);
                            if (decoded != null) return decoded;
                        }
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {}
        }
        return boundArtwork(LauncherMediaController.decodeArtwork(update.artworkBytes));
    }

    @Nullable
    private static Bitmap decodeFileBounded(@NonNull File image) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            while (sample < 1 << 16
                    && exceedsArtworkBounds(bounds.outWidth, bounds.outHeight, sample)) {
                sample <<= 1;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return boundArtwork(BitmapFactory.decodeFile(image.getAbsolutePath(), options));
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static boolean exceedsArtworkBounds(int width, int height, int sample) {
        int sampledWidth = Math.max(1, width / sample);
        int sampledHeight = Math.max(1, height / sample);
        return sampledWidth > MAX_CACHED_ARTWORK_EDGE
                || sampledHeight > MAX_CACHED_ARTWORK_EDGE
                || (long) sampledWidth * sampledHeight > MAX_CACHED_ARTWORK_PIXELS;
    }

    /** Returns the same bitmap when already bounded; otherwise owns and recycles the input. */
    @Nullable
    private static Bitmap boundArtwork(@Nullable Bitmap decoded) {
        if (decoded == null) return null;
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        if (width <= 0 || height <= 0) {
            if (!decoded.isRecycled()) decoded.recycle();
            return null;
        }
        if (width <= MAX_CACHED_ARTWORK_EDGE && height <= MAX_CACHED_ARTWORK_EDGE
                && (long) width * height <= MAX_CACHED_ARTWORK_PIXELS) {
            return decoded;
        }
        double edgeScale = Math.min((double) MAX_CACHED_ARTWORK_EDGE / width,
                (double) MAX_CACHED_ARTWORK_EDGE / height);
        double pixelScale = Math.sqrt((double) MAX_CACHED_ARTWORK_PIXELS
                / ((double) width * height));
        double scale = Math.min(1.0d, Math.min(edgeScale, pixelScale));
        int targetWidth = Math.max(1, (int) Math.floor(width * scale));
        int targetHeight = Math.max(1, (int) Math.floor(height * scale));
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(
                    decoded, targetWidth, targetHeight, true);
            if (scaled != decoded && !decoded.isRecycled()) decoded.recycle();
            return scaled;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            if (!decoded.isRecycled()) decoded.recycle();
            return null;
        }
    }

    private static boolean isArtworkFileValid(@NonNull File image) {
        return image.isFile() && image.length() > 0L && image.length() <= MAX_ARTWORK_BYTES;
    }

    private static void deleteQuietly(@NonNull File file) {
        try {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        } catch (SecurityException ignored) {}
    }

    @Nullable
    private static byte[] readBounded(@NonNull InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_ARTWORK_BYTES) return null;
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    @NonNull
    private static String clean(@Nullable String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.equalsIgnoreCase("Unknown")) return "";
        if (result.length() > MAX_TEXT_LENGTH) result = result.substring(0, MAX_TEXT_LENGTH);
        return result;
    }

    @Nullable
    private static Object extra(@NonNull Intent intent, @NonNull String key) {
        Bundle extras = intent.getExtras();
        return extras == null ? null : extras.get(key);
    }

    private static boolean hasExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Bundle extras = intent.getExtras();
            return extras != null && extras.containsKey(key);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Nullable
    private static String stringExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Object value = extra(intent, key);
            return value instanceof CharSequence ? value.toString() : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static long longExtra(@NonNull Intent intent, @NonNull String key, long fallback) {
        try {
            Object value = extra(intent, key);
            if (value instanceof Number) return ((Number) value).longValue();
            if (value instanceof CharSequence) return Long.parseLong(value.toString().trim());
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    private static boolean booleanExtra(@NonNull Intent intent, @NonNull String key,
                                        boolean fallback) {
        try {
            Object value = extra(intent, key);
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).longValue() != 0L;
            if (value instanceof CharSequence) {
                String text = value.toString().trim();
                if ("1".equals(text) || "true".equalsIgnoreCase(text)) return true;
                if ("0".equals(text) || "false".equalsIgnoreCase(text)) return false;
            }
        } catch (RuntimeException ignored) {}
        return fallback;
    }

    @Nullable
    private static byte[] byteArrayExtra(@NonNull Intent intent, @NonNull String key) {
        try {
            Object value = extra(intent, key);
            if (!(value instanceof byte[])) return null;
            byte[] bytes = (byte[]) value;
            return bytes.length <= MAX_ARTWORK_BYTES ? bytes : null;
        } catch (RuntimeException ignored) { return null; }
    }
}
