package dezz.status.widget.launcher.climate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ClimateFanIndicatorPolicyTest {
    @Test
    public void manualModeUsesAllNineConfirmedFanLevels() {
        ClimateFanIndicatorPolicy.Indicator off =
                ClimateFanIndicatorPolicy.fromConfirmedState("Выкл", 0);
        assertFalse(off.automatic);
        assertEquals(9, off.totalSegments);
        assertEquals(0, off.activeSegments);

        ClimateFanIndicatorPolicy.Indicator level =
                ClimateFanIndicatorPolicy.fromConfirmedState("7", 7);
        assertFalse(level.automatic);
        assertEquals(9, level.totalSegments);
        assertEquals(7, level.activeSegments);
    }

    @Test
    public void automaticModeUsesFiveSegmentIntensityScale() {
        assertAutoProfile("AUTO · тихо", 0, 1);
        assertAutoProfile("AUTO · обычно", 1, 3);
        assertAutoProfile("AUTO · интенсивно", 2, 5);
        assertAutoProfile("AUTO · тише", 0, 1);
        assertAutoProfile("AUTO · выше", 1, 5);
        assertTrue(ClimateFanIndicatorPolicy.isAutomaticLabel("AUTO · обычно"));
    }

    private static void assertAutoProfile(String label, int index, int expected) {
        ClimateFanIndicatorPolicy.Indicator value =
                ClimateFanIndicatorPolicy.fromConfirmedState(label, index);
        assertTrue(value.automatic);
        assertEquals(5, value.totalSegments);
        assertEquals(expected, value.activeSegments);
    }
}
