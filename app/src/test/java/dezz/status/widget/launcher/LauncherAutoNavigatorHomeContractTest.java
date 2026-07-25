/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locks the opt-in HOME -> launcher -> windowed Navigator order. */
public final class LauncherAutoNavigatorHomeContractTest {
    @Test public void settingIsVisibleAndDefaultsToOptIn() throws IOException {
        String preferences = source("dezz/status/widget/Preferences.java");
        String settings = source("dezz/status/widget/LauncherSettingsActivity.java");
        assertTrue(preferences.contains(
                "\"launcherAutoWindowedNavigatorOnHome\", false"));
        assertTrue(settings.contains(
                "preferences.launcherAutoWindowedNavigatorOnHome"));
    }

    @Test public void homeIntentUsesGuardedWindowedNavigatorWithoutRecursion()
            throws IOException {
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        assertTrue(launcher.contains("scheduleAutoNavigatorForHomeIntent(getIntent())"));
        assertTrue(launcher.contains("intent.hasCategory(Intent.CATEGORY_HOME)"));
        assertTrue(launcher.contains("EXTRA_WINDOW_BACKGROUND_GUARD"));
        assertTrue(launcher.contains(
                "launchYandex(YandexWindowLauncher.Product.NAVIGATOR, false)"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
