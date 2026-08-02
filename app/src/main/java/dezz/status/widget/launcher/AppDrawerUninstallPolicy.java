/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.content.Context;

import androidx.annotation.NonNull;

/** Shared safety policy for the edit/uninstall mode used by every all-apps drawer. */
public final class AppDrawerUninstallPolicy {
    private AppDrawerUninstallPolicy() {
    }

    /**
     * OEM/system packages and the currently running launcher are deliberately not offered for
     * removal. Android's package installer remains the final authority and shows its own
     * confirmation for every eligible user application.
     */
    public static boolean canUninstall(@NonNull Context context,
                                       @NonNull String packageName,
                                       boolean systemApp) {
        String target = packageName.trim();
        return !target.isEmpty()
                && !systemApp
                && !context.getPackageName().equals(target);
    }
}
