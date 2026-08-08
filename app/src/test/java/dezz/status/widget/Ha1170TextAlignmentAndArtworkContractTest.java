/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/** Release barriers for HA1170 text-field alignment and stale artwork suppression. */
public final class Ha1170TextAlignmentAndArtworkContractTest {
    @Test public void alignmentControlsActualTextInsideTheWholeConfiguredField()
            throws Exception {
        String card = source("phone/PhoneNotificationCardView.java");
        String editor = source("PhoneNotificationLayoutEditorActivity.java");

        assertTrue(editor.contains("Текст по центру поля по вертикали"));
        assertTrue(card.contains("? Gravity.TOP : Gravity.CENTER_VERTICAL"));
        assertTrue(card.contains("PhoneNotificationLayoutConfig.TEXT_VERTICAL_CENTER.equals("));
        assertFalse(card.contains("element.maxLines <= 1"));
    }

    @Test public void previousCoverRemainsRejectedForTheWholeCurrentTrack()
            throws Exception {
        String panel = source("launcher/media/MediaPanelView.java");
        String policy = source("launcher/media/MediaArtworkBindingPolicy.java");

        assertTrue(panel.contains("previousTrackFingerprintToReject("));
        assertTrue(panel.contains("isRejectedForCurrentTrack("));
        assertTrue(panel.contains("including after the correct new cover has"));
        assertFalse(panel.contains("rejectedArtworkFingerprint = 0L;"));
        assertTrue(policy.contains("Keep A blocked until another real track boundary"));
    }

    @Test public void releaseIdentityIsHa1170() throws Exception {
        String build = project("build.gradle");
        if (!build.contains("String getVersionName()")) build = project("../build.gradle");
        assertTrue(build.contains("return 'v2.8.2-ha1192'"));
    }

    private static String source(String relative) throws Exception {
        return project("app/src/main/java/dezz/status/widget/" + relative);
    }

    private static String project(String relative) throws Exception {
        Path direct = Paths.get(relative);
        Path parent = Paths.get("..").resolve(relative).normalize();
        Path file = Files.isRegularFile(direct) ? direct : parent;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
