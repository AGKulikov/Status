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

/** Prevents favorite navigation from regressing to the slower Alice hand-off. */
public final class YandexDirectRouteContractTest {
    @Test public void navigatorUsesOfficialDirectRouteParameters() throws IOException {
        String source = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");
        assertTrue(source.contains("yandexnavi://build_route_on_map"));
        assertTrue(source.contains("\"lat_to\""));
        assertTrue(source.contains("\"lon_to\""));
        assertTrue(source.contains("\"lat_via_\" + index"));
        assertFalse(source.contains("ask_alice"));
    }

    @Test public void driverActionPickerOffersSavedRoutes() throws IOException {
        String picker = source("dezz/status/widget/launcher/ShortcutActionPicker.java");
        String executor = source("dezz/status/widget/driver/DriverPanelActionExecutor.java");
        assertTrue(picker.contains("Избранная точка навигации"));
        assertTrue(picker.contains("LauncherShortcutStore.Kind.ROUTE"));
        assertTrue(executor.contains("YandexRouteLauncher.launch(context, route)"));
    }

    @Test public void optionalOfficialAccessKeySignsNavigatorUrls() throws IOException {
        String launcher = source("dezz/status/widget/launcher/routes/YandexRouteLauncher.java");
        String signer = source(
                "dezz/status/widget/launcher/routes/YandexNavigatorUrlSigner.java");
        String settings = source("dezz/status/widget/FavoriteRoutesSettingsActivity.java");
        assertTrue(launcher.contains("authenticatedDeepLink"));
        assertTrue(signer.contains("SHA256withRSA"));
        assertTrue(signer.contains("appendQueryParameter(\"client\""));
        assertTrue(signer.contains("appendQueryParameter(\"signature\""));
        assertTrue(settings.contains("Ключ прямого запуска Яндекс Навигатора"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
