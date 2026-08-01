package dezz.status.widget.driver;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DriverClimateIgnitionPolicyTest {
    @Test public void lockAndOffForceTheClimateTileOff() {
        assertEquals(DriverClimateIgnitionPolicy.State.OFF,
                DriverClimateIgnitionPolicy.fromRaw(DriverClimateIgnitionPolicy.LOCK));
        assertEquals(DriverClimateIgnitionPolicy.State.OFF,
                DriverClimateIgnitionPolicy.fromRaw(DriverClimateIgnitionPolicy.OFF));
    }

    @Test public void everyPoweredIgnitionStateIsActive() {
        for (long raw : new long[]{
                DriverClimateIgnitionPolicy.ACC,
                DriverClimateIgnitionPolicy.ON,
                DriverClimateIgnitionPolicy.START,
                DriverClimateIgnitionPolicy.DRIVE}) {
            assertEquals(DriverClimateIgnitionPolicy.State.ACTIVE,
                    DriverClimateIgnitionPolicy.fromRaw(raw));
        }
    }

    @Test public void missingOrUnexpectedTelemetryDoesNotInventPowerState() {
        assertEquals(DriverClimateIgnitionPolicy.State.UNKNOWN,
                DriverClimateIgnitionPolicy.fromRaw(Double.NaN));
        assertEquals(DriverClimateIgnitionPolicy.State.UNKNOWN,
                DriverClimateIgnitionPolicy.fromRaw(0));
    }
}
