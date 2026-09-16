/* Copyright © 2025-2026 Dezz; adapted for Natro. SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import dezz.status.widget.R;

import android.content.pm.ApplicationInfo;

public final class AlwaysIgnoreAppResolver {
    public static boolean alwaysIgnoreApp(ApplicationInfo appInfo, String stealthAppPackageName) {
        return ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) ||
                appInfo.packageName.equals(stealthAppPackageName) ||
                appInfo.packageName.startsWith("com.ecarx.");
    }
}
