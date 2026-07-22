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
import java.util.concurrent.atomic.AtomicInteger;

/** Durable cache for media broadcasts received while HOME is not on screen. */
final class MediaBroadcastRepository {
    static final String ACTION_CACHE_UPDATED =
            "dezz.status.widget.launcher.MEDIA_BROADCAST_CACHE_UPDATED";

    private static final String PREFS = "launcher_media_broadcast_v1";
    private static final String ARTWORK_FILE = "launcher_media_broadcast_artwork.png";
    private static final int MAX_TEXT_LENGTH = 500;
    private static final int MAX_ARTWORK_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PENDING_UPDATES = 4;
    private static final long BOOT_EPOCH_TOLERANCE_MS = 5L * 60L * 1_000L;
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static final Object WRITE_SUBMIT_LOCK = new Object();
    private static final ThreadPoolExecutor WRITE_EXECUTOR = createWriteExecutor();
    private static final ThreadPoolExecutor READ_EXECUTOR = createReadExecutor();

    private static ThreadPoolExecutor createWriteExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_UPDATES), runnable -> {
                Thread thread = new Thread(runnable, "media-broadcast-cache");
                thread.setDaemon(true);
                return thread;
            });
        executor.allowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler((newest, owner) -> {
            Runnable oldest = owner.getQueue().poll();
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
                    Thread thread = new Thread(runnable, "media-broadcast-read");
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        // Reads are snapshots and never carry BroadcastReceiver PendingResults. Retain only the
        // newest request without allowing a UI refresh to evict the latest UPDATE/CLEAR write.
        executor.setRejectedExecutionHandler((newest, owner) -> {
            owner.getQueue().poll();
            if (!owner.isShutdown()) owner.getQueue().offer(newest);
        });
        return executor;
    }

    /** Ensures every manifest receiver PendingResult is finished, including dropped burst jobs. */
    private static final class CacheTask implements Runnable {
        @NonNull private final Runnable work;
        @Nullable private Runnable finished;

        CacheTask(@NonNull Runnable work, @Nullable Runnable finished) {
            this.work = work;
            this.finished = finished;
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

        State(@NonNull String title, @NonNull String artist, @NonNull String album,
              @NonNull String packageName, @Nullable Bitmap artwork, long durationMs,
              long positionMs, boolean playing, long timestampWallMs, long receivedElapsedMs) {
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

        PendingUpdate(@NonNull Intent intent) {
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
        READ_EXECUTOR.execute(new CacheTask(() -> callback.onRead(read(targetContext)), null));
    }

    /** Copies all extras synchronously, then performs bitmap and disk work off the main thread. */
    static void processAsync(@NonNull Context rawContext, @NonNull Intent intent,
                             @Nullable Runnable finished) {
        Context context = rawContext.getApplicationContext();
        String action = intent.getAction();
        if (LauncherMediaController.ACTION_MEDIA_CLEAR.equals(action)
                || LauncherMediaController.ACTION_MEDIA_CLEAR_DEBUG.equals(action)) {
            synchronized (WRITE_SUBMIT_LOCK) {
                final int generation = GENERATION.incrementAndGet();
                WRITE_EXECUTOR.execute(new CacheTask(() -> {
                    if (generation == GENERATION.get()) clearNow(context);
                }, finished));
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
            final int generation = GENERATION.incrementAndGet();
            WRITE_EXECUTOR.execute(new CacheTask(() -> {
                Bitmap artwork = null;
                try {
                    // A manifest and a dynamic receiver can observe the same broadcast while HOME
                    // is visible. Skip superseded work before expensive bitmap processing.
                    if (generation != GENERATION.get()) return;
                    artwork = update.artworkExpected ? decodeArtwork(context, update) : null;
                    if (generation == GENERATION.get()) saveNow(context, update, artwork);
                } finally {
                    if (artwork != null && !artwork.isRecycled()) artwork.recycle();
                }
            }, finished));
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
        if (prefs.getBoolean("hasArtwork", false) && image.isFile()
                && image.length() > 0L && image.length() <= MAX_ARTWORK_BYTES) {
            try {
                artwork = BitmapFactory.decodeFile(image.getAbsolutePath());
            } catch (RuntimeException | OutOfMemoryError ignored) {}
        }
        return new State(prefs.getString("title", ""), prefs.getString("artist", ""),
                prefs.getString("album", ""), prefs.getString("package", ""), artwork,
                duration, position,
                playing, prefs.getLong("timestamp", 0L),
                Math.max(0L, SystemClock.elapsedRealtime() - age));
    }

    private static void saveNow(@NonNull Context context, @NonNull PendingUpdate update,
                                @Nullable Bitmap artwork) {
        context = storage(context);
        File image = new File(context.getFilesDir(), ARTWORK_FILE);
        boolean artworkStored = false;
        if (artwork == null) {
            if (!update.artworkExpected) {
                // A new track explicitly carrying no artwork must not inherit the previous one.
                //noinspection ResultOfMethodCallIgnored
                image.delete();
            }
        } else {
            File temporary = new File(context.getFilesDir(), ARTWORK_FILE + ".tmp");
            boolean compressed = false;
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (artwork.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    output.flush();
                    output.getFD().sync();
                    compressed = true;
                }
            } catch (IOException | RuntimeException ignored) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            if (!compressed) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
            if (compressed && temporary.isFile() && temporary.length() > 0L) {
                try {
                    try {
                        Files.move(temporary.toPath(), image.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException ignored) {
                        Files.move(temporary.toPath(), image.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    artworkStored = true;
                } catch (IOException | RuntimeException ignored) {
                    // Keep the prior file intact. hasArtwork=false below prevents it being paired
                    // with the new track if replacement failed.
                    //noinspection ResultOfMethodCallIgnored
                    temporary.delete();
                }
            }
        }
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
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
                .putBoolean("hasArtwork", artworkStored);
        int bootCount = bootCount(context);
        if (bootCount >= 0) editor.putInt("bootCount", bootCount);
        else editor.remove("bootCount");
        editor.apply();
        notifyChanged(context);
    }

    private static void clearNow(@NonNull Context context) {
        context = storage(context);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
        //noinspection ResultOfMethodCallIgnored
        new File(context.getFilesDir(), ARTWORK_FILE).delete();
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
        return context.getApplicationContext().createDeviceProtectedStorageContext();
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
                            if (decoded != null) return decoded;
                        }
                    }
                }
            } catch (IOException | RuntimeException | OutOfMemoryError ignored) {}
        }
        return LauncherMediaController.decodeArtwork(update.artworkBytes);
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
