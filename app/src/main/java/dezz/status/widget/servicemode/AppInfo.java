/* Copyright © 2025-2026 Dezz; adapted for Natro. SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.servicemode;

import dezz.status.widget.R;

import android.graphics.drawable.Drawable;

public class AppInfo {
    private final String packageName;
    private final String appName;
    private final Drawable icon;
    private boolean isChecked;

    public AppInfo(String packageName, String appName, Drawable icon, boolean isChecked) {
        this.packageName = packageName;
        this.appName = appName;
        this.icon = icon;
        this.isChecked = isChecked;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getAppName() {
        return appName;
    }

    public Drawable getIcon() {
        return icon;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setChecked(boolean checked) {
        isChecked = checked;
    }
}
