/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.*;
import org.junit.Test;

public final class HudMediaVisibilityTest {
    private static final HudElementType[] CONTENT = {
            HudElementType.MEDIA_ARTWORK, HudElementType.MEDIA_COMBINED,
            HudElementType.MEDIA_TITLE, HudElementType.MEDIA_ARTIST,
            HudElementType.MEDIA_ALBUM, HudElementType.MEDIA_APPLICATION,
            HudElementType.MEDIA_TIMER
    };

    @Test public void pauseForCallHidesEveryContentElementUntilPlaybackResumes() {
        for (HudElementType type : CONTENT) {
            assertTrue(HudMediaVisibility.visible(type, false, true, true, true, 1000, false));
            assertFalse(HudMediaVisibility.visible(type, false, true, false, true, 1000, false));
            // Metadata/artwork may keep arriving during a call or an ordinary pause.
            assertFalse(HudMediaVisibility.visible(type, false, true, false, true, 5000, true));
            assertTrue(HudMediaVisibility.visible(type, false, true, true, true, 5000, false));
        }
    }

    @Test public void missingSessionCannotDisplayRetainedContent() {
        for (HudElementType type : CONTENT)
            assertFalse(HudMediaVisibility.visible(type, false, false, true, true, 1000, true));
    }

    @Test public void unavailableArtworkAndUnknownDurationHideOnlyTheirElements() {
        assertFalse(HudMediaVisibility.visible(HudElementType.MEDIA_ARTWORK,
                false, true, true, false, 1000, false));
        assertFalse(HudMediaVisibility.visible(HudElementType.MEDIA_TIMER,
                false, true, true, true, 0, false));
        assertTrue(HudMediaVisibility.visible(HudElementType.MEDIA_TITLE,
                false, true, true, false, 0, false));
    }

    @Test public void editorKeepsEveryElementSelectableWithoutLiveData() {
        for (HudElementType type : HudElementType.values())
            assertTrue(HudMediaVisibility.visible(type, true, false, false, false, 0, false));
    }

    @Test public void volumeHasItsOwnTransientVisibilityEvenWhenMusicIsPaused() {
        assertTrue(HudMediaVisibility.visible(HudElementType.MEDIA_VOLUME,
                false, false, false, false, 0, true));
        assertFalse(HudMediaVisibility.visible(HudElementType.MEDIA_VOLUME,
                false, true, true, true, 1000, false));
    }

    @Test public void pauseDoesNotHideMapClockNavigationOrVehicleValues() {
        for (HudElementType type : HudElementType.values()) {
            if (type.name().startsWith("MEDIA_")) continue;
            assertTrue(HudMediaVisibility.visible(type, false, false, false, false, 0, false));
        }
    }
}
