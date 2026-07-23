/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Protects compatibility with standalone and unified Yandex builds found on ECARX units. */
public final class YandexWindowLauncherContractTest {
    @Test
    public void navigatorUsesTheProvenVendorAndActionlessFloatingEntryPoints() throws IOException {
        String source = source();

        assertTrue(source.contains("new Intent()"));
        assertTrue(source.contains("new Intent(\"navi_win/\" + target.packageName)"));
        assertTrue(source.contains("ru.yandex.yandexmaps.app.TransparentSplashActivity"));
        assertTrue(source.contains("putExtra(\"ddnavwin\", true)"));
        assertFalse(source.contains("new Intent(\"navi_win/ru.yandex.yandexmaps\")"));
    }

    @Test
    public void launchAlsoSupportsUnifiedMapsAndDeepLinks() throws IOException {
        String source = source();

        assertTrue(source.contains("ru.yandex.yandexmaps.TransparentSplashActivity"));
        assertTrue(source.contains("ru.yandex.yandexnavi.core.NavigatorActivity"));
        assertTrue(source.contains("launchDeepLink"));
        assertTrue(source.contains("YANGO_PACKAGE"));
    }

    private static String source() throws IOException {
        String relative = "dezz/status/widget/launcher/YandexWindowLauncher.java";
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
