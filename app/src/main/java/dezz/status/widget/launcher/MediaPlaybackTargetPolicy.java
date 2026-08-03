/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Resolves the one application that owns every launcher media command.
 *
 * <p>A head unit may expose Bluetooth, radio and Android players as simultaneous media sessions.
 * Sending a global media key lets Android choose between them and is exactly how a HOME play tap
 * can accidentally start the paired phone. The policy therefore produces one explicit package:
 * either the user-selected player or the last Android player that actually published playback.</p>
 */
public final class MediaPlaybackTargetPolicy {
    private MediaPlaybackTargetPolicy() {}

    @NonNull
    public static String resolve(boolean fixedPlayerEnabled,
                                 @Nullable String fixedPackage,
                                 @Nullable String rememberedPackage) {
        String fixed = normalize(fixedPackage);
        if (fixedPlayerEnabled && !fixed.isEmpty()) return fixed;
        return normalize(rememberedPackage);
    }

    /**
     * A fixed player is an explicit instruction to start that application after boot. In
     * last-player mode the previous playing flag remains the safety gate, so a deliberately
     * paused session is not started after every ignition cycle.
     */
    public static boolean shouldAutoResume(boolean fixedPlayerEnabled,
                                           @Nullable String fixedPackage,
                                           @Nullable String rememberedPackage,
                                           boolean rememberedWasPlaying) {
        String target = resolve(fixedPlayerEnabled, fixedPackage, rememberedPackage);
        if (target.isEmpty()) return false;
        return fixedPlayerEnabled || rememberedWasPlaying;
    }

    @NonNull
    private static String normalize(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
