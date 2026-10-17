/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;
import org.junit.Test;
import static org.junit.Assert.*;

public class MediaTitleDisplayTest {
    @Test public void removesOnlyLeadingInfoNoticesIncludingUnicodeSpacesAndVariants() {
        for (String prefix : new String[]{"ⓘ ", "ℹ", "ℹ️ ", "\u00a0ⓘ\u202f", " ⓘ ℹ︎ "}) {
            String expected = "Заходит Луна (feat. Принцип)";
            assertEquals(expected, MediaTitleDisplay.clean(prefix + expected));
            assertEquals(expected, MediaTitleDisplay.clean(MediaTitleDisplay.clean(prefix + expected)));
        }
        assertEquals("", MediaTitleDisplay.clean("ⓘ"));
        assertEquals("", MediaTitleDisplay.clean("\u00a0ℹ️ "));
        assertEquals("", MediaTitleDisplay.clean(null));
    }
    @Test public void ordinaryLettersWarningsPunctuationAndEmbeddedInfoStayUnchanged() {
        for (String title : new String[]{"i love you", " I ", "⚠ Название", "Песня ⓘ live", "(ⓘ) Песня", "", "  Песня  "})
            assertEquals(title, MediaTitleDisplay.clean(title));
    }
    @Test public void sharedDisplaySnapshotCleansTitleWithoutChangingArtistOrTimeline() {
        LauncherMediaController.Snapshot value = new LauncherMediaController.Snapshot(
                "ⓘ Заходит Луна", "ⓘ GUF, Принцип", "ⓘ Album", "Music", null,
                217000, 12345, true, true, true, false, 30);
        assertEquals("Заходит Луна", value.title);
        assertEquals("ⓘ GUF, Принцип", value.artist);
        assertEquals("ⓘ Album", value.album);
        assertEquals(217000, value.durationMs);
        assertEquals(12345, value.positionMs);
        assertTrue(value.playing);
    }
}
