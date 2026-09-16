/* Copyright © 2025-2026 Dezz; adapted for Natro. SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import dezz.status.widget.R;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

public class VersionGetter {
    private static final String TAG = "VersionGetter";

    public static String getAppVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error getting app version name", e);
            return null;
        }
    }
}
