/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://ask_alice"))
                .setPackage("ru.yandex.yandexnavi")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { context.startActivity(intent); }
        catch (android.content.ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(context, "Алиса в Навигаторе недоступна", Toast.LENGTH_SHORT).show();
        }
    }
}
