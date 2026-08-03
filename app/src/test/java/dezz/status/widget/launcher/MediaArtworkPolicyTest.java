/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class MediaArtworkPolicyTest {
    @Test
    public void identicalPayloadReusesExistingFile() {
        String track = MediaArtworkPolicy.trackSignature("player", "Song", "Artist", "Album");
        String source = MediaArtworkPolicy.bytesSignature(
                "same image".getBytes(StandardCharsets.UTF_8));

        assertEquals(MediaArtworkPolicy.Action.REUSE, MediaArtworkPolicy.decide(
                true, true, source, track, true, true, source, track));
    }

    @Test
    public void changedPayloadForSameTrackIsDecoded() {
        String track = MediaArtworkPolicy.trackSignature("player", "Song", "Artist", "Album");
        String before = MediaArtworkPolicy.bytesSignature(new byte[] {1, 2, 3});
        String after = MediaArtworkPolicy.bytesSignature(new byte[] {1, 2, 4});

        assertNotEquals(before, after);
        assertEquals(MediaArtworkPolicy.Action.DECODE, MediaArtworkPolicy.decide(
                true, true, after, track, true, true, before, track));
    }

    @Test
    public void positionPacketWithoutArtworkReusesOnlySameTrack() {
        String first = MediaArtworkPolicy.trackSignature("player", "First", "Artist", "Album");
        String second = MediaArtworkPolicy.trackSignature("player", "Second", "Artist", "Album");

        assertEquals(MediaArtworkPolicy.Action.REUSE, MediaArtworkPolicy.decide(
                true, false, "", first, true, true, "stored", first));
        assertEquals(MediaArtworkPolicy.Action.CLEAR, MediaArtworkPolicy.decide(
                true, false, "", second, true, true, "stored", first));
    }

    @Test
    public void missingOrInvalidFileCannotBeReused() {
        String track = MediaArtworkPolicy.trackSignature("player", "Song", "", "");
        assertEquals(MediaArtworkPolicy.Action.DECODE, MediaArtworkPolicy.decide(
                true, true, "new", track, true, false, "new", track));
        assertEquals(MediaArtworkPolicy.Action.CLEAR, MediaArtworkPolicy.decide(
                true, false, "", track, true, false, "old", track));
    }

    @Test
    public void explicitNoArtworkAlwaysClearsAndSignaturesHideMetadata() {
        assertEquals(MediaArtworkPolicy.Action.CLEAR, MediaArtworkPolicy.decide(
                false, true, "same", "same-track", true, true, "same", "same-track"));
        String signature = MediaArtworkPolicy.trackSignature(
                "secret.player", "Private title", "Artist", "Album");
        assertFalse(signature.contains("Private title"));
        assertEquals(70, signature.length()); // "track:" plus 64 hex characters.
    }

    @Test
    public void combinedSourceTracksUriAndFallbackBytesWithoutExposingEither() {
        String track = MediaArtworkPolicy.trackSignature("player", "Song", "Artist", "Album");
        String first = MediaArtworkPolicy.sourceSignature(
                "content://private/cover", new byte[] {1, 2, 3}, track);
        String second = MediaArtworkPolicy.sourceSignature(
                "content://private/cover", new byte[] {1, 2, 4}, track);

        assertNotEquals(first, second);
        assertFalse(first.contains("content://private/cover"));
        assertEquals(71, first.length()); // "source:" plus 64 hex characters.
    }
}
