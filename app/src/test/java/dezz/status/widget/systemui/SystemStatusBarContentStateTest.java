/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.systemui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression for the KX11 firmware blacklist syntax seen on the physical head unit. */
public final class SystemStatusBarContentStateTest {
    @Test public void printableFirmwareTokensAreAcceptedWithoutInventingARestrictedGrammar() {
        assertTrue(SystemStatusBarContentState.isSafeExplicitRaw(
                "clock,bluetooth,ecarx:vehicle status,vendor/icon@right"));
    }

    @Test public void shellBreakingAndControlCharactersRemainRejected() {
        assertFalse(SystemStatusBarContentState.isSafeExplicitRaw("clock,'bluetooth"));
        assertFalse(SystemStatusBarContentState.isSafeExplicitRaw("clock,bluetooth\n"));
    }
}
