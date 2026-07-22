/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SingleFlightRefreshTest {
    @Test
    public void burstCollapsesIntoOneTrailingJob() {
        SingleFlightRefresh refresh = new SingleFlightRefresh();
        assertTrue(refresh.request());
        assertFalse(refresh.request());
        assertFalse(refresh.request());
        assertTrue(refresh.complete());
        assertFalse(refresh.complete());
        assertTrue(refresh.request());
    }

    @Test
    public void cancelAllowsCleanRestart() {
        SingleFlightRefresh refresh = new SingleFlightRefresh();
        assertTrue(refresh.request());
        assertFalse(refresh.request());
        refresh.cancel();
        assertTrue(refresh.request());
    }
}
