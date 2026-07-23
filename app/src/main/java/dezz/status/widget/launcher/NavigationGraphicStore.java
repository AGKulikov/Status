/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Bounded, device-protected storage for graphics supplied by navigation broadcasts.
 *
 * <p>Broadcast parcelables are deliberately copied and down-sampled before they reach the HOME
 * view. This prevents a malformed or accidentally full-resolution image from retaining a large
 * Binder-backed bitmap in the launcher process. Files are used instead of SharedPreferences so
 * the scalar route state remains small and crash-safe.</p>
 */
final class NavigationGraphicStore {
    static final String MANEUVER = "maneuver";
    static final String LANES = "lanes";
    static final String JAM = "jam";
    static final String RAINBOW = "rainbow";

    private static final int MAX_ENCODED_BYTES = 2 * 1024 * 1024;
    // These graphics are rendered inside a launcher tile, not full-screen. Four 1280px ARGB
    // entries consumed about 25.6 MB and could kill the shared HOME/status process on the head
    // unit. 640px still exceeds the physical maneuver/lane view while bounding the set to ~6 MB.
    private static final int MAX_DIMENSION = 640;
    private static final long MAX_PIXELS = 409_600L;
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;
    private static final String DIRECTORY = "navigation_graphics";
    private static final Map<String, Cached> CACHE = new HashMap<>();
    /** Invalidates decoded results that finish after a trim, clear, or atomic file replacement. */
    private static final NavigationCacheEpoch CACHE_EPOCH = new NavigationCacheEpoch();

    private NavigationGraphicStore() {}

    /** Saves the first supported Bitmap/byte[] extra. Invalid data leaves the old image intact. */
    static boolean saveFromIntent(@NonNull Context context, @NonNull String slot,
            @NonNull Intent intent, @NonNull String... keys) {
        Incoming incoming = bitmapFromIntent(intent, keys);
        if (incoming == null) return false;
        Bitmap value = incoming.bitmap;
        Bitmap bounded = boundedCopy(value);
        if (bounded == null) {
            if (incoming.owned && !value.isRecycled()) value.recycle();
            return false;
        }
        try {
            return save(context, slot, bounded);
        } finally {
            if (bounded != value && !bounded.isRecycled()) bounded.recycle();
            if (incoming.owned && value != bounded && !value.isRecycled()) value.recycle();
        }
    }

    @Nullable
    static Bitmap load(@NonNull Context context, @NonNull String slot) {
        File file = file(context, slot);
        recoverBackup(file);
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_FILE_BYTES) return null;
        String key = file.getAbsolutePath();
        long modified = file.lastModified();
        long generation;
        synchronized (CACHE) {
            generation = CACHE_EPOCH.capture();
            Cached cached = CACHE.get(key);
            if (cached != null && cached.modified == modified && !cached.bitmap.isRecycled()) {
                return cached.bitmap;
            }
        }
        Bitmap value = decodeFileBounded(file);
        if (value != null) {
            synchronized (CACHE) {
                // A parallel evictAll()/save()/clear() deliberately wins over this old decode.
                // The caller may still render its immutable snapshot, but it must not resurrect a
                // cache entry after HOME released graphics or after a newer file was installed.
                if (CACHE_EPOCH.isCurrent(generation) && file.isFile()
                        && file.lastModified() == modified) {
                    CACHE.put(key, new Cached(modified, value));
                }
            }
        }
        return value;
    }

    static boolean exists(@NonNull Context context, @NonNull String slot) {
        File file = file(context, slot);
        recoverBackup(file);
        return file.isFile() && file.length() > 0 && file.length() <= MAX_FILE_BYTES;
    }

    /** Drops the decoded ARGB copy while retaining the bounded on-disk file for atomic replace. */
    static void evict(@NonNull Context context, @NonNull String slot) {
        File file = file(context, slot);
        synchronized (CACHE) {
            CACHE_EPOCH.invalidate();
            CACHE.remove(file.getAbsolutePath());
        }
    }

    /** Drops only decoded copies; bounded on-disk graphics remain available for the next HOME. */
    static void evictAll() {
        synchronized (CACHE) {
            CACHE_EPOCH.invalidate();
            CACHE.clear();
        }
    }

    static void clear(@NonNull Context context, @NonNull String slot) {
        File file = file(context, slot);
        // Do not recycle here: an ImageView may still render the previous immutable snapshot.
        // Removing our reference lets it be collected as soon as the view accepts the next state.
        evict(context, slot);
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        temporary.delete();
        File backup = new File(file.getParentFile(), file.getName() + ".bak");
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
    }

    private static boolean save(Context context, String slot, Bitmap bitmap) {
        File target = file(context, slot);
        recoverBackup(target);
        File parent = target.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
        File temporary = new File(parent, target.getName() + ".tmp");
        boolean encoded;
        try (FileOutputStream stream = new FileOutputStream(temporary, false)) {
            encoded = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            stream.getFD().sync();
        } catch (IOException | RuntimeException ignored) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return false;
        }
        if (!encoded || temporary.length() <= 0 || temporary.length() > MAX_FILE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return false;
        }
        // Rename within one directory is atomic on Android's app-data filesystem.
        File backup = new File(parent, target.getName() + ".bak");
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return false;
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) {
                // Restore the last known-good image when the final atomic rename fails.
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(target);
            }
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
        synchronized (CACHE) {
            CACHE_EPOCH.invalidate();
            CACHE.remove(target.getAbsolutePath());
        }
        return true;
    }

    @Nullable
    private static Incoming bitmapFromIntent(Intent intent, String[] keys) {
        Bundle extras = intent.getExtras();
        if (extras == null) return null;
        extras.setClassLoader(Bitmap.class.getClassLoader());
        for (String key : keys) {
            Object raw;
            try { raw = extras.get(key); }
            catch (RuntimeException ignored) { continue; }
            if (raw instanceof Bitmap) {
                Bitmap bitmap = (Bitmap) raw;
                if (!bitmap.isRecycled()) return new Incoming(bitmap, false);
            } else if (raw instanceof byte[]) {
                Bitmap bitmap = decodeBytesBounded((byte[]) raw);
                if (bitmap != null) return new Incoming(bitmap, true);
            }
        }
        return null;
    }

    @Nullable
    private static Bitmap decodeBytesBounded(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_ENCODED_BYTES) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            return validateDecoded(decoded);
        } catch (RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    @Nullable
    private static Bitmap decodeFileBounded(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try {
            Bitmap decoded = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            return validateDecoded(decoded);
        } catch (RuntimeException | OutOfMemoryError ignored) { return null; }
    }

    @Nullable
    private static Bitmap validateDecoded(@Nullable Bitmap value) {
        if (value == null) return null;
        int width = value.getWidth();
        int height = value.getHeight();
        if (width > 0 && height > 0 && width <= MAX_DIMENSION && height <= MAX_DIMENSION
                && (long) width * height <= MAX_PIXELS) return value;
        if (!value.isRecycled()) value.recycle();
        return null;
    }

    @Nullable
    private static Bitmap boundedCopy(Bitmap source) {
        if (source.isRecycled()) return null;
        int width;
        int height;
        try {
            width = source.getWidth();
            height = source.getHeight();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (width <= 0 || height <= 0) return null;
        double scale = Math.min(1d, Math.min(MAX_DIMENSION / (double) width,
                MAX_DIMENSION / (double) height));
        if ((long) width * height > MAX_PIXELS) {
            scale = Math.min(scale, Math.sqrt(MAX_PIXELS / ((double) width * height)));
        }
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        try {
            if (targetWidth != width || targetHeight != height) {
                return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
            }
            Bitmap.Config config = source.getConfig();
            if (config == null || config == Bitmap.Config.HARDWARE) config = Bitmap.Config.ARGB_8888;
            return source.copy(config, false);
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / sample > MAX_DIMENSION || height / sample > MAX_DIMENSION
                || ((long) (width / sample) * (height / sample)) > MAX_PIXELS) {
            if (sample > Integer.MAX_VALUE / 2) return Integer.MAX_VALUE;
            sample *= 2;
        }
        return sample;
    }

    private static File file(Context context, String slot) {
        Context storage = context.createDeviceProtectedStorageContext();
        File directory = new File(storage.getFilesDir(), DIRECTORY);
        String safe = Arrays.asList(MANEUVER, LANES, JAM, RAINBOW).contains(slot)
                ? slot : "unknown";
        return new File(directory, safe + ".png");
    }

    /** Restores the last complete file after a process death between the two atomic renames. */
    private static void recoverBackup(File target) {
        File parent = target.getParentFile();
        if (parent == null) return;
        File backup = new File(parent, target.getName() + ".bak");
        if (target.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            backup.delete();
        } else if (backup.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            backup.renameTo(target);
        }
    }

    private static final class Incoming {
        final Bitmap bitmap;
        final boolean owned;

        Incoming(Bitmap bitmap, boolean owned) {
            this.bitmap = bitmap;
            this.owned = owned;
        }
    }

    private static final class Cached {
        final long modified;
        final Bitmap bitmap;

        Cached(long modified, Bitmap bitmap) {
            this.modified = modified;
            this.bitmap = bitmap;
        }
    }
}
