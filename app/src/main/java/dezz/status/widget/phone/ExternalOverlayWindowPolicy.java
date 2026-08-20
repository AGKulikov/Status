/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package dezz.status.widget.phone;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/** Pure package policy for Android 9 accessibility window add/remove events. */
public final class ExternalOverlayWindowPolicy {
    private ExternalOverlayWindowPolicy() {}

    /**
     * A distinct application-owned window may cover the current app without changing the task
     * reported by UsageStats (the KX11 360° camera is the important example). Framework chrome,
     * input methods and Natro's own windows are deliberately excluded.
     */
    public static boolean isCandidate(@NonNull String ownPackage,
                                      @Nullable String foregroundPackage,
                                      @Nullable CharSequence eventPackage) {
        String value = eventPackage == null ? "" : eventPackage.toString().trim();
        if (value.isEmpty() || ownPackage.equals(value)
                || value.equals(foregroundPackage)) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return !"android".equals(lower)
                && !lower.startsWith("com.android.systemui")
                && !lower.startsWith("com.ecarx.systemui")
                && !lower.startsWith("com.android.inputmethod")
                && !lower.startsWith("com.google.android.inputmethod")
                && !lower.startsWith("com.android.permissioncontroller");
    }
}
