/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

/** Process boundaries used to keep optional HUD/native work away from the status-row process. */
public final class AppProcessPolicy {
    private static final String HUD_SUFFIX = ":hud";

    private AppProcessPolicy() {}

    public static boolean isHudProcess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        String name = Application.getProcessName();
        return name != null && name.endsWith(HUD_SUFFIX);
    }

    /**
     * API 28 still supports the platform's cross-process reload flag. The main process keeps the
     * normal fast mode; only the isolated HUD process asks SharedPreferences to recheck disk when
     * a new wrapper is constructed after an explicit configuration command.
     */
    @SuppressWarnings("deprecation")
    public static int preferenceMode() {
        return Context.MODE_PRIVATE
                | (isHudProcess() ? Context.MODE_MULTI_PROCESS : 0);
    }

    @NonNull
    public static String currentProcessLabel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "main";
        String name = Application.getProcessName();
        return name == null || name.trim().isEmpty() ? "unknown" : name.trim();
    }
}
