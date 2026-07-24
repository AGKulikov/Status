/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards against overwriting edits made by the real-HOME media content editor. */
public final class MediaPanelSettingsLifecycleContractTest {
    @Test
    public void returningFromHomeEditorReloadsStoreBeforeRebuildingUi() throws IOException {
        String source = source("dezz/status/widget/MediaPanelSettingsActivity.java");
        int resume = source.indexOf("protected void onResume()");
        int reload = source.indexOf("config = store.load();", resume);
        int rebuild = source.indexOf("installContent();", reload);

        assertTrue("Media settings must have a repeat-resume guard", resume >= 0
                && source.indexOf("if (resumedOnce)", resume) > resume);
        assertTrue("Store must be reloaded on repeat resume", reload > resume);
        assertTrue("Controls must be rebuilt from the reloaded config", rebuild > reload);
        assertTrue("Rebuilt content must reinstall visible back navigation",
                source.contains("SettingsBackNavigation.install(this, content);"));
        assertTrue("Opening the real HOME editor must remain available",
                source.contains("EXTRA_EDIT_MEDIA_CONTENT"));
    }

    @Test
    public void repeatResumeDoesNotDiscardLivePreviewOrStopPersistence() throws IOException {
        String source = source("dezz/status/widget/MediaPanelSettingsActivity.java");

        assertTrue(source.contains("updatePreviewNow();"));
        assertTrue(source.contains("editScheduler.flush();"));
        assertTrue(source.contains("saveNow();"));
    }

    @Test
    public void settingsPreviewIsAlsoADirectGridEditor() throws IOException {
        String source = source("dezz/status/widget/MediaPanelSettingsActivity.java");

        assertTrue(source.contains("preview.setInPlaceEditMode(true"));
        assertTrue(source.contains("config = updated.copy();"));
        assertTrue(source.contains("store.save(config);"));
        assertTrue(source.contains("ЖИВОЙ РЕДАКТОР"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
