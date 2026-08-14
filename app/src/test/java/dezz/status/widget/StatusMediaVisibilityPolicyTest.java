/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StatusMediaVisibilityPolicyTest {
    @Test public void missingSessionNeverShowsMusic() {
        assertFalse(StatusMediaVisibilityPolicy.hasVisibleContent(false,
                false, false, false));
        assertFalse(StatusMediaVisibilityPolicy.hasVisibleContent(false,
                false, false, true));
    }

    @Test public void legacyModeKeepsPausedSessionVisible() {
        assertTrue(StatusMediaVisibilityPolicy.hasVisibleContent(false,
                true, false, false));
    }

    @Test public void playingOnlyModeRequiresExactPlayingState() {
        assertFalse(StatusMediaVisibilityPolicy.hasVisibleContent(false,
                true, false, true));
        assertTrue(StatusMediaVisibilityPolicy.hasVisibleContent(false,
                true, true, true));
    }

    @Test public void phonePresentationStillUsesConfiguredGeometry() {
        assertTrue(StatusMediaVisibilityPolicy.hasVisibleContent(true,
                false, false, true));
    }
}
