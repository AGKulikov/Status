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

    @Test
    public void serviceLaunchStagesOurLauncherUnderTheWindowAndHomeChainIsOptional()
            throws IOException {
        String window = source();
        String launcher = source("dezz/status/widget/LauncherActivity.java");
        String settings = source("dezz/status/widget/LauncherSettingsActivity.java");
        String preferences = source("dezz/status/widget/Preferences.java");

        assertTrue(window.contains("launchOverLauncher("));
        assertTrue(window.contains(
                "new Intent(context, dezz.status.widget.LauncherActivity.class)"));
        assertTrue(window.contains("EXTRA_STAGED_PRODUCT"));
        assertTrue(launcher.contains("handleStagedOrHomeNavigation("));
        assertTrue(launcher.contains("intent.hasCategory(Intent.CATEGORY_HOME)"));
        assertTrue(launcher.contains("launcherHomeOpensWindowedNavigator.get()"));
        assertTrue(settings.contains("HOME → наш лаунчер → оконный Навигатор"));
        assertTrue(preferences.contains("launcherHomeOpensWindowedNavigator"));
    }

    @Test
    public void freeformNavigatorIsAnExternalEcarxWindowSoWeDoNotOfferFakeRounding()
            throws IOException {
        String window = source();
        String settings = source("dezz/status/widget/LauncherSettingsActivity.java");

        assertTrue(window.contains("context.startActivity(intent)"));
        assertTrue(window.contains("intent.setComponent(new ComponentName("));
        assertTrue(window.contains("TransparentSplashActivity"));
        assertFalse(window.contains("SurfaceView"));
        assertFalse(window.contains("SurfaceControl"));
        assertFalse(window.contains("setCornerRadius"));
        assertTrue(settings.contains("не владеет его Window/Surface"));
    }

    private static String source() throws IOException {
        return source("dezz/status/widget/launcher/YandexWindowLauncher.java");
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
