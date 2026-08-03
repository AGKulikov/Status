package dezz.status.widget.launcher.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MediaVolumeMathTest {
    @Test public void percentMapsToDiscreteAndroidVolumeSteps() {
        assertEquals(0, MediaVolumeMath.stepForPercent(-20, 15));
        assertEquals(8, MediaVolumeMath.stepForPercent(50, 15));
        assertEquals(15, MediaVolumeMath.stepForPercent(120, 15));
    }

    @Test public void actualStepIsReportedBackAsReachablePercent() {
        assertEquals(0, MediaVolumeMath.percentForStep(-1, 15));
        assertEquals(53, MediaVolumeMath.percentForStep(8, 15));
        assertEquals(100, MediaVolumeMath.percentForStep(99, 15));
        assertEquals(0, MediaVolumeMath.percentForStep(5, 0));
    }
}
