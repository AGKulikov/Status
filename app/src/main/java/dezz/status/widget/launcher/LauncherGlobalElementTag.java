/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dezz.status.widget.R;

/** Stable identity attached to a live child view that can be placed anywhere on HOME. */
public final class LauncherGlobalElementTag {
    @NonNull public final String id;
    @NonNull public final String label;

    private LauncherGlobalElementTag(@NonNull String id, @NonNull String label) {
        this.id = id;
        this.label = label;
    }

    public static void attach(@NonNull View view, @NonNull String panelId,
                              @NonNull String elementId, @NonNull String label) {
        String stableId = panelId.trim() + "/" + elementId.trim();
        view.setTag(R.id.launcher_global_element_id,
                new LauncherGlobalElementTag(stableId, label.trim()));
    }

    @Nullable
    public static LauncherGlobalElementTag from(@NonNull View view) {
        Object value = view.getTag(R.id.launcher_global_element_id);
        return value instanceof LauncherGlobalElementTag
                ? (LauncherGlobalElementTag) value : null;
    }
}
