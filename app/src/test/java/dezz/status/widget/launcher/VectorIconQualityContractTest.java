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

/** Prevents Android 9 framework bitmaps from returning to user-scalable icon surfaces. */
public final class VectorIconQualityContractTest {
    @Test public void scalableSurfacesUseLocalVectorDrawables() throws IOException {
        assertNoFrameworkDrawable("dezz/status/widget/launcher/LauncherIconResolver.java");
        assertNoFrameworkDrawable("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertNoFrameworkDrawable("dezz/status/widget/LauncherActivity.java");
        assertNoFrameworkDrawable("dezz/status/widget/MainActivity.java");
        assertNoFrameworkDrawable("dezz/status/widget/FavoriteAppsSettingsActivity.java");

        assertVector("drawable/ic_launcher_apps.xml");
        assertVector("drawable/ic_launcher_navigation.xml");
        assertVector("drawable/ic_launcher_home.xml");
        assertVector("drawable/ic_launcher_work.xml");
        assertVector("drawable/ic_media_play.xml");
        assertVector("drawable/ic_media_pause.xml");
        assertVector("drawable/ic_media_previous.xml");
        assertVector("drawable/ic_media_next.xml");
        assertVector("drawable/ic_media_volume.xml");
        assertVector("drawable/ic_add.xml");
        assertVector("mipmap-anydpi/ic_launcher.xml");
        assertVector("mipmap-anydpi/ic_launcher_round.xml");
        assertVector("mipmap-anydpi/ic_launcher_foreground.xml");
    }

    @Test public void installedApplicationIconsAreDecodedFromHighestDensity() throws IOException {
        String source = javaSource(
                "dezz/status/widget/launcher/HighResolutionAppIconLoader.java");
        assertTrue(source.contains("getDrawableForDensity("));
        assertTrue(source.contains("DisplayMetrics.DENSITY_XXXHIGH"));
        assertFalse(source.contains("createScaledBitmap"));
    }

    private static void assertNoFrameworkDrawable(String relative) throws IOException {
        assertFalse(relative + " must not use fixed-size Android framework icon bitmaps",
                javaSource(relative).contains("android.R.drawable"));
    }

    private static void assertVector(String relative) throws IOException {
        String source = resource(relative);
        assertTrue(relative + " must remain an Android VectorDrawable",
                source.contains("<vector"));
        assertFalse(relative + " must not embed a bitmap", source.contains("<bitmap"));
    }

    private static String javaSource(String relative) throws IOException {
        return read(Paths.get("app", "src", "main", "java").resolve(relative),
                Paths.get("src", "main", "java").resolve(relative));
    }

    private static String resource(String relative) throws IOException {
        return read(Paths.get("app", "src", "main", "res").resolve(relative),
                Paths.get("src", "main", "res").resolve(relative));
    }

    private static String read(Path fromRoot, Path fromApp) throws IOException {
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
