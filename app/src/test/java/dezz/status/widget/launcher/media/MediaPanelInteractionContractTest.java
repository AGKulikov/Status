package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Guards the touch split: surface opens the player, child controls keep their own actions. */
public final class MediaPanelInteractionContractTest {
    @Test public void panelSurfaceOpensYandexMusic() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("setOnClickListener(view ->"));
        assertTrue(source.contains("MediaAppLauncher.launchYandexMusic(getContext())"));
    }

    @Test public void mediaButtonsHaveTransparentBackgroundAndConsumeClick() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        int buttonStart = source.indexOf("private ImageButton button(");
        int buttonEnd = source.indexOf("private void applySnapshot()", buttonStart);
        String button = source.substring(buttonStart, buttonEnd);
        assertTrue(button.contains("setBackgroundColor(Color.TRANSPARENT)"));
        assertTrue(button.contains("setOnClickListener(listener)"));
        assertFalse(button.contains("MediaAppLauncher.launchYandexMusic"));
    }

    @Test public void volumeWritesMusicStreamAndListensForSystemChanges() throws IOException {
        String source = source("dezz/status/widget/launcher/media/MediaPanelView.java");
        assertTrue(source.contains("manager.setStreamVolume(AudioManager.STREAM_MUSIC"));
        assertTrue(source.contains("android.media.VOLUME_CHANGED_ACTION"));
        assertTrue(source.contains("syncSystemVolume()"));
    }

    private static String source(String relative) throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java").resolve(relative);
        Path fromApp = Paths.get("src", "main", "java").resolve(relative);
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
