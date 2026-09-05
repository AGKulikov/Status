/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.*;
import org.junit.Test;

public final class HudVolumeVisibilityTest {
    @Test public void firstSampleIsSilentIncludingZero() {
        for (int value : new int[]{0, 14, 40}) {
            HudVolumeVisibility visibility = new HudVolumeVisibility();
            assertFalse(visibility.sample(value, 100));
            assertFalse(visibility.visible(100));
        }
    }

    @Test public void changeShowsForTwoSecondsAndRepeatedMediaTicksDoNotExtendIt() {
        HudVolumeVisibility visibility = new HudVolumeVisibility();
        visibility.sample(14, 0);
        assertTrue(visibility.sample(15, 100));
        assertTrue(visibility.visible(100));
        for (long time : new long[]{500, 1000, 1500, 2000})
            assertFalse(visibility.sample(15, time));
        assertTrue(visibility.visible(2099));
        assertFalse(visibility.visible(2100));
    }

    @Test public void rapidChangesRestartOnlyFromLastRealChange() {
        HudVolumeVisibility visibility = new HudVolumeVisibility();
        visibility.sample(14, 0);
        visibility.sample(15, 100);
        visibility.sample(16, 300);
        assertTrue(visibility.sample(15, 600));
        assertTrue(visibility.visible(2599));
        assertFalse(visibility.visible(2600));
    }

    @Test public void zeroIsAnActualStepChangeAndCanShowMute() {
        HudVolumeVisibility visibility = new HudVolumeVisibility();
        visibility.sample(1, 0);
        assertTrue(visibility.sample(0, 100));
        assertTrue(visibility.visible(101));
        assertFalse(visibility.sample(0, 200));
    }

    @Test public void UnknownAndRestartCannotReplayOldChange() {
        HudVolumeVisibility visibility = new HudVolumeVisibility();
        visibility.sample(14, 0);
        visibility.sample(15, 100);
        visibility.sample(-1, 200);
        assertFalse(visibility.visible(201));
        assertFalse(visibility.sample(15, 300));
        visibility.sample(16, 400);
        visibility.reset();
        assertFalse(visibility.visible(500));
        assertFalse(visibility.sample(16, 500));
    }
}
