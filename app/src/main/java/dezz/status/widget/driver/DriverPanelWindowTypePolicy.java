/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.driver;

import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Ordered ECARX system-bar window types with a normal overlay fallback. */
final class DriverPanelWindowTypePolicy {
    private static final String[] VENDOR_FIELDS = {
            "TYPE_CODE_NAVIGATION_BAR",
            "TYPE_NAVIGATION_BAR",
            "TYPE_CODE_STATUS_BAR"
    };

    private DriverPanelWindowTypePolicy() {
    }

    @NonNull
    static List<Integer> candidates() {
        LinkedHashSet<Integer> values = new LinkedHashSet<>();
        for (String field : VENDOR_FIELDS) {
            try {
                int value = WindowManager.LayoutParams.class.getField(field).getInt(null);
                // ECARX vendor codes are authoritative even when they sit outside AOSP's public
                // system-window interval. MonjaroPanel accepts every non-zero reflected
                // value and lets WindowManager reject unsupported types during the ordered retry.
                if (value != 0) values.add(value);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        // Always provide a portable path for normal Android and restrictive vendor builds.
        values.add(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        return new ArrayList<>(values);
    }
}
