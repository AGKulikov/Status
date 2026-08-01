/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaPlaybackTargetPolicyTest {
    @Test public void fixedPackageAlwaysWinsOverLastPlayer() {
        assertEquals("ru.yandex.music", MediaPlaybackTargetPolicy.resolve(
                true, " ru.yandex.music ", "com.android.bluetooth"));
    }

    @Test public void lastPlayerIsUsedWhenFixedModeIsOffOrIncomplete() {
        assertEquals("com.spotify.music", MediaPlaybackTargetPolicy.resolve(
                false, "ru.yandex.music", " com.spotify.music "));
        assertEquals("com.spotify.music", MediaPlaybackTargetPolicy.resolve(
                true, " ", "com.spotify.music"));
    }

    @Test public void fixedPlayerResumesEveryBootButLastPlayerRespectsPausedState() {
        assertTrue(MediaPlaybackTargetPolicy.shouldAutoResume(
                true, "ru.yandex.music", "com.spotify.music", false));
        assertFalse(MediaPlaybackTargetPolicy.shouldAutoResume(
                false, "", "com.spotify.music", false));
        assertTrue(MediaPlaybackTargetPolicy.shouldAutoResume(
                false, "", "com.spotify.music", true));
    }
}
