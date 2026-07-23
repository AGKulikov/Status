/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaAppLauncherTest {
    @Test public void discoversVendorRepackByPackageName() {
        assertTrue(MediaAppLauncher.looksLikeYandexMusic(
                "com.vendor.yandex.music.auto", "Audio"));
    }

    @Test public void discoversLocalizedBuildByApplicationLabel() {
        assertTrue(MediaAppLauncher.looksLikeYandexMusic(
                "com.vendor.player", "Яндекс Музыка"));
        assertTrue(MediaAppLauncher.looksLikeYandexMusic(
                "com.vendor.player", "Yandex Music"));
    }

    @Test public void doesNotMistakeOtherYandexOrMusicApps() {
        assertFalse(MediaAppLauncher.looksLikeYandexMusic(
                "ru.yandex.yandexnavi", "Яндекс Навигатор"));
        assertFalse(MediaAppLauncher.looksLikeYandexMusic(
                "com.vendor.music", "Музыка"));
    }
}
