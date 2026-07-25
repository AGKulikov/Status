package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClimateFanIndicatorPolicyTest {
    @Test
    public void manualModeUsesFiveVisibleFanPositions() {
        ClimateFanIndicatorPolicy.Indicator off =
                ClimateFanIndicatorPolicy.fromConfirmedState("Выкл", 0);
        assertFalse(off.automatic);
        assertEquals(5, off.totalSegments);
        assertEquals(0, off.activeSegments);

        ClimateFanIndicatorPolicy.Indicator level =
                ClimateFanIndicatorPolicy.fromConfirmedState("5", 5);
        assertFalse(level.automatic);
        assertEquals(5, level.totalSegments);
        assertEquals(5, level.activeSegments);
    }

    @Test
    public void automaticModeMapsEveryRussianFivePositionLabel() {
        assertAutoProfile("AUTO · тише", 3, 1);
        assertAutoProfile("AUTO · тихо", 0, 2);
        assertAutoProfile("AUTO · обычно", 1, 3);
        assertAutoProfile("AUTO · интенсивно", 2, 4);
        assertAutoProfile("AUTO · выше", 4, 5);
        assertTrue(ClimateFanIndicatorPolicy.isAutomaticLabel("AUTO · обычно"));
    }

    @Test
    public void automaticModeMapsEveryEnglishFivePositionLabel() {
        // These two pairs also prove that the longer comparative tokens win over substrings.
        assertAutoProfile("AUTO · quieter", 3, 1);
        assertAutoProfile("AUTO · quiet", 0, 2);
        assertAutoProfile("AUTO · silent", 0, 2);
        assertAutoProfile("AUTO · normal", 1, 3);
        assertAutoProfile("AUTO · high", 2, 4);
        assertAutoProfile("AUTO · higher", 4, 5);
    }

    @Test
    public void automaticUnknownLabelFallsBackToClampedOrdinal() {
        assertAutoProfile("AUTO", -1, 1);
        assertAutoProfile("AUTO", 0, 1);
        assertAutoProfile("AUTO", 2, 3);
        assertAutoProfile("AUTO", 4, 5);
        assertAutoProfile("AUTO", 99, 5);
    }

    private static void assertAutoProfile(String label, int index, int expected) {
        ClimateFanIndicatorPolicy.Indicator value =
                ClimateFanIndicatorPolicy.fromConfirmedState(label, index);
        assertTrue(value.automatic);
        assertEquals(5, value.totalSegments);
        assertEquals(expected, value.activeSegments);
    }
}
