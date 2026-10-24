/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.widget.Toast;
import dezz.status.widget.Preferences;

/** Shared ready-made actions for HOME and physical-button/driver-menu assignments. */
public final class MediaExtraActions {
    private MediaExtraActions() {}
    public static void like(Context context, Preferences preferences) {
        if (!LauncherMediaController.likeCurrent(context, preferences))
            Toast.makeText(context, "Плеер не предоставил действие «Нравится»", Toast.LENGTH_SHORT).show();
    }
    public static void alice(Context context) {
        NavigatorAliceAction.invoke(context);
    }
}
