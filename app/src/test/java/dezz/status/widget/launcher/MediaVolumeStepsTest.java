/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.*;
import org.junit.Test;

public final class MediaVolumeStepsTest {
    @Test public void snapshotKeepsExactStepsEvenWhenPercentRoundsToSameValue() {
        LauncherMediaController.Snapshot a = snapshot(50, 100, 200);
        LauncherMediaController.Snapshot b = snapshot(50, 101, 200);
        assertEquals(a.volumePercent, b.volumePercent);
        assertEquals(100, a.volumeSteps);
        assertEquals(101, b.volumeSteps);
        assertEquals(200, b.volumeMaximum);
    }

    @Test public void oldPercentOnlySnapshotCannotInventSystemSteps() {
        LauncherMediaController.Snapshot old = new LauncherMediaController.Snapshot(
                "", "", "", "", null, 0, 0, false, false, false, null, 35);
        assertEquals(-1, old.volumeSteps);
        assertEquals(-1, old.volumeMaximum);
        assertEquals(0, snapshot(0, 0, 40).volumeSteps);
    }

    private static LauncherMediaController.Snapshot snapshot(int percent, int steps, int maximum) {
        return new LauncherMediaController.Snapshot("", "", "", "", null, 0, 0,
                false, false, false, null, percent, steps, maximum);
    }
}
