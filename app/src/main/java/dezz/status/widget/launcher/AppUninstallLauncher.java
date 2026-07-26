/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.NonNull;

/** Opens Android's standard package-removal confirmation from every runtime app drawer. */
public final class AppUninstallLauncher {
    private AppUninstallLauncher() {
    }

    public static boolean request(@NonNull Context context,
                                  @NonNull LauncherAppCatalog.App app) {
        return request(context, app.packageName, app.label);
    }

    public static boolean request(@NonNull Context context,
                                  @NonNull String packageName,
                                  @NonNull String label) {
        String target = packageName.trim();
        if (target.isEmpty()) return false;
        Intent uninstall = new Intent(Intent.ACTION_DELETE,
                Uri.fromParts("package", target, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(uninstall);
            return true;
        } catch (RuntimeException failure) {
            Toast.makeText(context,
                    "Удаление недоступно для «" + label + "»",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
