/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Defines HOME blocks whose children already have independent cell geometry.
 *
 * <p>These blocks may use the complete safe HOME canvas without losing their logical settings
 * group. Blocks that still use a linear/list layout keep their movable outer rectangle until their
 * children gain equivalent independent geometry.</p>
 */
public final class LauncherBlockCanvasPolicy {
    private static final Set<String> FREE_BLOCKS = Collections.unmodifiableSet(new HashSet<>(
            Arrays.asList(LauncherLayoutStore.MEDIA, LauncherLayoutStore.NAVIGATION,
                    LauncherLayoutStore.ACTIONS, LauncherLayoutStore.INFORMATION)));

    private LauncherBlockCanvasPolicy() {}

    public static boolean usesWholeHome(@NonNull String blockId, boolean enabled) {
        return enabled && FREE_BLOCKS.contains(blockId);
    }

    public static boolean supports(@NonNull String blockId) {
        return FREE_BLOCKS.contains(blockId);
    }
}
