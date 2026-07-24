/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Loads the highest-density activity artwork available instead of the current-density bitmap.
 *
 * <p>The launcher deliberately lets the user grow an application icon far beyond Android's
 * normal 48 dp launcher size. On a mdpi head unit, {@link ActivityInfo#loadIcon(PackageManager)}
 * commonly decodes only a 48 px bitmap and ImageView then magnifies it to 120–180 px. Asking the
 * package resources for xxxhdpi keeps the original high-resolution pixels (or vector/adaptive
 * drawable) while Android still reports the correct logical intrinsic size.</p>
 */
public final class HighResolutionAppIconLoader {
    private static final int SOURCE_DENSITY = DisplayMetrics.DENSITY_XXXHIGH;

    private HighResolutionAppIconLoader() {}

    @Nullable
    public static Drawable load(@NonNull Context context, @NonNull ComponentName component) {
        PackageManager manager = context.getPackageManager();
        try {
            return load(context, manager.getActivityInfo(component, 0));
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
            return null;
        }
    }

    @Nullable
    public static Drawable load(@NonNull Context context, @NonNull ActivityInfo activity) {
        PackageManager manager = context.getPackageManager();
        int iconResource = activity.getIconResource();
        if (iconResource == 0 && activity.applicationInfo != null) {
            iconResource = activity.applicationInfo.icon;
        }
        if (iconResource != 0 && activity.applicationInfo != null) {
            try {
                Resources resources = manager.getResourcesForApplication(activity.applicationInfo);
                Drawable highResolution = resources.getDrawableForDensity(
                        iconResource, SOURCE_DENSITY, null);
                if (highResolution != null) return highResolution;
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                // A vendor package can expose an invalid themed/alias resource. Fall through to
                // PackageManager's safe loader instead of dropping the application from the grid.
            }
        }
        try {
            return activity.loadIcon(manager);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
