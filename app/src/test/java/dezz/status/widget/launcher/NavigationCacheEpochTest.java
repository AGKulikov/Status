/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NavigationCacheEpochTest {
    @Test
    public void invalidationRejectsAnOlderDecodeToken() {
        NavigationCacheEpoch epoch = new NavigationCacheEpoch();
        long firstDecode = epoch.capture();
        assertTrue(epoch.isCurrent(firstDecode));

        epoch.invalidate();
        assertFalse(epoch.isCurrent(firstDecode));
        assertTrue(epoch.isCurrent(epoch.capture()));
    }
}
