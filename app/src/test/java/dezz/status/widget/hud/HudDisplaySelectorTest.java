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
            new HudDisplaySelector.Candidate(2, "passenger", "Passenger",
                    1760, 720, true, false),
            new HudDisplaySelector.Candidate(4, "hud", "HUD",
                    728, 910, true, false));

    @Test public void numericIdIsAuthoritativeEvenWhenUniqueIdPointsElsewhere() {
        assertEquals(2, HudDisplaySelector.preferredIndex(DISPLAYS, "passenger", 4));
    }

    @Test public void missingConfiguredIdNeverFallsThroughToAnotherScreen() {
        assertEquals(-1, HudDisplaySelector.preferredIndex(DISPLAYS, "passenger", 7));
    }

    @Test public void noConfiguredIdentityNeverGuessesByArrayPosition() {
        assertEquals(-1, HudDisplaySelector.preferredIndex(DISPLAYS, "", -1));
    }

    @Test public void legacyUniqueIdWorksOnlyWithoutNumericId() {
        assertEquals(2, HudDisplaySelector.preferredIndex(DISPLAYS, "hud", -1));
    }
}
