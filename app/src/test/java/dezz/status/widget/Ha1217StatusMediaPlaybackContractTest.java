/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Source-level wiring guard for the status-row media playback preferences. */
public final class Ha1217StatusMediaPlaybackContractTest {
    @Test public void preferencesAndEditorExposeIndependentControls() throws Exception {
        String preferences = source("Preferences.java");
        String adapter = source("BrickListAdapter.java");
        String layout = resource("layout/brick_item.xml");

        assertTrue(preferences.contains("mediaShowPlaybackStateIcon\", true"));
        assertTrue(preferences.contains("mediaOnlyWhilePlaying\", false"));
        assertTrue(adapter.contains("prefs.media.showPlaybackStateIcon.set(c)"));
        assertTrue(adapter.contains("prefs.media.onlyWhilePlaying.set(c)"));
        assertTrue(layout.contains("@+id/brickMediaShowPlaybackStateIcon"));
        assertTrue(layout.contains("@+id/brickMediaOnlyWhilePlaying"));
    }

    @Test public void mediaSessionCallbacksDriveVisibilityWithoutANewTimer() throws Exception {
        String service = source("WidgetService.java");
        String callback = between(service,
                "private final MediaController.Callback mediaControllerCallback",
                "private final MediaSessionManager.OnActiveSessionsChangedListener");
        assertTrue(callback.contains("onPlaybackStateChanged"));
        assertTrue(callback.contains("updateMediaInfo();"));
        assertFalse(callback.contains("postDelayed"));

        String update = between(service, "private void updateMediaInfo()",
                "private void syncMediaProgressWidth()");
        assertTrue(update.contains("prefs.media.onlyWhilePlaying.get()"));
        assertTrue(update.contains("isActuallyPlaying(playbackState)"));
        assertTrue(update.contains("musicPresentationVisible"));
        assertTrue(update.contains("binding.mediaContainer.setVisibility("));
    }

    @Test public void playbackIconVisibilityIsNotCoupledToSourceLine() throws Exception {
        String service = source("WidgetService.java");
        String icon = between(service, "private void applyMediaStateIcon(",
                "private static void applyMediaChildAlignment(");
        assertTrue(icon.contains("prefs.media.showPlaybackStateIcon.get()"));
        assertTrue(icon.contains("prefs.media.showSource.get()"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative,
                "src/main/java/dezz/status/widget/" + relative);
    }

    private static String resource(String relative) throws Exception {
        return project("app/src/main/res/" + relative, "src/main/res/" + relative);
    }

    private static String project(String rootRelative, String appRelative) throws Exception {
        Path root = Paths.get(rootRelative);
        Path path = Files.isRegularFile(root) ? root : Paths.get(appRelative);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static String between(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, Math.max(0, from + start.length()));
        if (from < 0 || to <= from) throw new AssertionError("Missing source range");
        return source.substring(from, to);
    }
}
