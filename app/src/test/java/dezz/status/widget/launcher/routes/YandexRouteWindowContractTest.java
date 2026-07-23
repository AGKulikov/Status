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

        assertTrue(source.contains("YandexWindowLauncher.launch("));
        assertTrue(source.contains("windowProduct(route.product), false"));
        assertTrue(source.contains("postDelayed"));
        assertTrue(source.contains(
                "startDeepLink(context, route.product, deepLink, alternateDeepLink)"));
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

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
