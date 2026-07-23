/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.app.Application;
import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Loads Status Widget resources (package id 0x80) beside Navigator's original 0x7f table.
 *
 * <p>The resource APK is copied out of the merged base because Android 9 AssetManager accepts an
 * APK path, not a nested ZIP entry. Installation is idempotent and runs from the merged
 * Application's {@code attachBaseContext}, before any Status Activity or provider is created.</p>
 */
public final class MergedResourceInstaller {
    public static final String ASSET_NAME = "status_widget_resources.apk";
    private static final String TAG = "MergedResources";
    private static final Map<AssetManager, Boolean> ATTACHED_ASSET_MANAGERS =
            new WeakHashMap<>();

    private MergedResourceInstaller() {}

    public static synchronized boolean install(@NonNull Application application) {
        return installContext(application);
    }

    /**
     * Adds the nested table to this exact Context's AssetManager.
     *
     * <p>Android 9 can create a distinct ResourcesImpl for a landscape Activity. The merger calls
     * this method at the start of every imported Status Activity's onCreate, so those contexts do
     * not depend on sharing the Application's AssetManager by accident.</p>
     */
    public static synchronized boolean installContext(@NonNull Context context) {
        AssetManager assets = context.getAssets();
        if (ATTACHED_ASSET_MANAGERS.containsKey(assets)) return true;
        Context storageContext = context.getApplicationContext();
        if (storageContext == null) storageContext = context;
        File output = new File(storageContext.getCodeCacheDir(),
                resourceApkName(storageContext));
        try {
            if (!output.isFile() || output.length() == 0L) {
                copyAsset(storageContext, output);
            }
            Method addAssetPath = AssetManager.class.getDeclaredMethod(
                    "addAssetPath", String.class);
            addAssetPath.setAccessible(true);
            Object result = addAssetPath.invoke(assets, output.getAbsolutePath());
            if (!(result instanceof Integer) || ((Integer) result) == 0) return false;
            Resources resources = context.getResources();
            resources.updateConfiguration(resources.getConfiguration(),
                    resources.getDisplayMetrics());
            ATTACHED_ASSET_MANAGERS.put(assets, true);
            Log.i(TAG, "Status resource package attached: " + output.length() + " bytes");
            return true;
        } catch (Throwable failure) {
            // Ordinary non-merged builds intentionally have no nested resource APK.
            Log.d(TAG, "Separate Status resources are not present", failure);
            return false;
        }
    }

    @NonNull
    private static String resourceApkName(@NonNull Context context) {
        long installedAt = 0L;
        try {
            installedAt = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
        } catch (RuntimeException | android.content.pm.PackageManager.NameNotFoundException
                 ignored) {
        }
        return "status_widget_resources-" + installedAt + ".apk";
    }

    private static void copyAsset(@NonNull Context context, @NonNull File output)
            throws Exception {
        File parent = output.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create " + parent);
        }
        File temporary = new File(output.getAbsolutePath() + ".tmp");
        try (InputStream input = context.getAssets().open(ASSET_NAME);
             FileOutputStream sink = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) sink.write(buffer, 0, read);
            sink.getFD().sync();
        }
        if (!temporary.renameTo(output)) {
            throw new IllegalStateException("Could not publish " + output);
        }
    }
}
