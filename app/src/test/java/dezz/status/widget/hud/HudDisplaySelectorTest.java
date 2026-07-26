/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.hud;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class HudDisplaySelectorTest {
    private static final List<HudDisplaySelector.Candidate> DISPLAYS = Arrays.asList(
            new HudDisplaySelector.Candidate(0, "main", "Main",
                    1760, 720, false, true),
            new HudDisplaySelector.Candidate(2, "local:2", "ECARX composite HUD",
                    1920, 1080, true, false),
            new HudDisplaySelector.Candidate(4, "local:4", "Passenger",
                    1920, 720, true, false));

    @Test public void verifiedIdIsAuthoritativeEvenWhenSavedIdentityPointsElsewhere() {
        assertEquals(1, HudDisplaySelector.preferredIndex(DISPLAYS, "local:4", 2));
    }

    @Test public void importedOtherIdStillResolvesOnlyVerifiedHud() {
        assertEquals(1, HudDisplaySelector.preferredIndex(DISPLAYS, "local:4", 7));
    }

    @Test public void noSavedIdentityStillUsesVerifiedVehicleConstant() {
        assertEquals(1, HudDisplaySelector.preferredIndex(DISPLAYS, "", -1));
    }

    @Test public void matchingLegacyIdentityAlsoResolvesVerifiedHud() {
        assertEquals(1, HudDisplaySelector.preferredIndex(DISPLAYS, "local:2", -1));
    }

    @Test public void absenceOfDisplayTwoNeverFallsThroughToPassengerScreen() {
        assertEquals(-1, HudDisplaySelector.preferredIndex(
                Arrays.asList(DISPLAYS.get(0), DISPLAYS.get(2)), "local:4", 4));
    }
}
