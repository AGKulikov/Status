/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget;
import org.junit.Test;
import static org.junit.Assert.*;
public class NavigatorFailureRecoveryTest {
    @Test public void confirmedDeathRecoversDuringBriefReturnToHome() {
        assertTrue(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,true,true,33700,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,true,true,33700,-1,false));
    }
    @Test public void field343FreshAbsenceFitsBeforeHomePausesAgain() {
        // HOME resumed at36817 and paused at37768. A fresh callback at37000
        // can pass; a1s poll from resume would arrive too late at37817.
        assertFalse(NavigatorAutoLaunchPolicy.retry(36817,15145,15146,1,true,true,14441,-1,true));
        assertTrue(NavigatorAutoLaunchPolicy.retry(37000,15145,15146,1,true,true,37000,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(37817,15145,15146,1,false,true,37000,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(37000,-1,15146,1,true,true,37000,-1,true));
    }
    @Test public void deathDoesNotOverrideSurfaceUserOrAttemptGuards() {
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,false,true,33700,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,true,false,33700,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,true,true,12000,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,2,true,true,33700,-1,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(33724,13598,13598,1,true,true,33700,14000,true));
        assertFalse(NavigatorAutoLaunchPolicy.retry(80000,13598,13598,1,true,true,79900,-1,true));
    }
}
