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

/** Guards the source-level wiring behind the no-flicker media arbitration policy. */
public final class MediaSourceStabilityContractTest {
    @Test public void activeSessionTokenIsStickyAcrossVendorListReordering() throws IOException {
        String source = controllerSource();
        assertTrue(source.contains("MediaStateFreshness.shouldKeepCurrentSession("));
        assertTrue(source.contains("sameSession(current, candidate)"));
        assertTrue(source.contains("MediaController selected = keepCurrent ? retained"));
    }

    @Test public void correlatedBroadcastOwnsConflictingTrackAsOneAtomicSnapshot()
            throws IOException {
        String source = controllerSource();
        assertTrue(source.contains("private boolean sessionBroadcastCorrelated"));
        assertTrue(source.contains("sessionBroadcastCorrelated = false"));
        assertTrue(source.contains("MediaStateFreshness.broadcastContentWins("));
        assertTrue(source.contains("if (!sameCorrelatedTrack(content, playback))"));
        assertTrue(source.contains("if (!sameCorrelatedTrack(content, timeline))"));
        assertTrue(source.contains("if (!sameCorrelatedTrack(content, supplement))"));
    }

    @Test public void clearExpiryPublisherHandoffAndStopResetCorrelation() throws IOException {
        String source = controllerSource();
        int replaceStart = source.indexOf("private void replaceBroadcastState(");
        int replaceEnd = source.indexOf("private void select(", replaceStart);
        String replace = source.substring(replaceStart, replaceEnd);
        int resetPolicy = replace.indexOf(
                "MediaStateFreshness.shouldResetBroadcastCorrelation(");
        int resetFlag = replace.indexOf(
                "if (resetCorrelation) sessionBroadcastCorrelated = false");
        int installState = replace.indexOf("broadcastState = next");
        assertTrue(resetPolicy >= 0);
        assertTrue(resetPolicy < resetFlag);
        assertTrue(resetFlag < installState);

        int stopStart = source.indexOf("public void stop()");
        int stopEnd = source.indexOf("public void refresh()", stopStart);
        assertTrue(source.substring(stopStart, stopEnd).contains("replaceBroadcastState(null)"));
    }

    @Test public void equivalentCacheArtworkRetainsPublishedBitmapOwnership() throws IOException {
        String source = controllerSource();
        int replaceStart = source.indexOf("private void replaceBroadcastState(");
        int replaceEnd = source.indexOf("private void select(", replaceStart);
        String replace = source.substring(replaceStart, replaceEnd);
        assertTrue(replace.contains("MediaStateFreshness.shouldReuseBroadcastArtwork("));
        assertTrue(replace.contains("next.application, previousArtwork"));
        assertTrue(replace.contains("duplicateArtwork.recycle()"));
        assertTrue(replace.indexOf("broadcastState = next")
                < replace.indexOf("duplicateArtwork.recycle()"));
    }

    private static String controllerSource() throws IOException {
        Path fromRoot = Paths.get("app", "src", "main", "java", "dezz", "status", "widget",
                "launcher", "LauncherMediaController.java");
        Path fromApp = Paths.get("src", "main", "java", "dezz", "status", "widget",
                "launcher", "LauncherMediaController.java");
        Path file = Files.isRegularFile(fromRoot) ? fromRoot : fromApp;
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }
}
