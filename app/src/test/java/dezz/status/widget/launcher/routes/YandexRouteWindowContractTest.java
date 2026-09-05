/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.routes;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Keeps favorite routes on the same proven ECARX floating-window path as the HOME shortcut. */
public final class YandexRouteWindowContractTest {
    @Test
    public void floatingRouteUsesSharedYandexWindowLauncher() throws IOException {
        String source = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");

        assertTrue(source.contains("YandexWindowLauncher.launchOverLauncher("));
        assertTrue(source.contains("windowProduct(route.product), false"));
        assertTrue(source.contains("postDelayed"));
        assertTrue(source.contains("alternateDeepLink, true)"));
        assertTrue(source.contains("ROUTE_WINDOW_REASSERT_DELAY_MS = 220L"));
        assertTrue(source.contains("ROUTE_WINDOW_VERIFY_DELAY_MS = 900L"));
        assertTrue(occurrences(source,
                "NavigationHudEndpointService.requestMainWindowMode(") == 2);
    }

    @Test
    public void everyRouteDeepLinkCarriesItsExplicitSurface() throws IOException {
        String route = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");
        String launcher = source("dezz/status/widget/launcher/YandexWindowLauncher.java");

        // Nonfloating and failed-window fallback are full-screen. Only the delayed handoff into
        // an already-open floating task may assert the window surface.
        assertTrue(occurrences(route, "alternateDeepLink, false)") == 2);
        assertTrue(occurrences(route, "alternateDeepLink, true)") == 1);
        assertTrue(route.contains("deepLink, windowed)"));
        assertTrue(route.contains("alternateDeepLink, windowed)"));
        assertTrue(occurrences(launcher, "public static boolean launchDeepLink(") == 1);
        assertTrue(launcher.contains("@NonNull Uri deepLink,"));
        assertTrue(launcher.contains("boolean windowed)"));
        assertTrue(launcher.contains(
                "new Target(NAVIGATOR_PACKAGE, \"ru.yandex.yandexmaps.app.MapActivity\")"));
        assertTrue(launcher.contains("intent.setComponent(new ComponentName(packageName, className))"));
        assertTrue(launcher.contains(".putExtra(\"ddnavwin\", windowed)"));
        assertTrue(launcher.contains(
                "if (!windowed) intent.putExtra(\"ddnavforcewinfull\", true)"));
    }

    @Test
    public void routeDoesNotRecreateVendorWindowWithActivityOptions() throws IOException {
        String source = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");

        assertFalse(source.contains("import android.app.ActivityOptions"));
        assertFalse(source.contains("ActivityOptions.makeBasic()"));
        assertFalse(source.contains("setLaunchDisplayId"));
        assertFalse(source.contains("bundle.putInt(\"android.activity.windowingMode\""));
        assertFalse(source.contains("startFreeformWindow"));
    }

    @Test
    public void textDestinationUsesDirectRouteAndNeverAlice() throws IOException {
        String source = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");

        assertTrue(source.contains("Uri.encode(\"~\" + addressValue"));
        assertTrue(source.contains("&rtt=auto"));
        assertFalse(source.contains("ask_alice"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
