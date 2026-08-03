/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.car;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Locks passenger presence to the same dedicated occupation event used by MConfig+ v45. */
public final class GeelyPassengerConditionContractTest {
    @Test public void passengerPresenceUsesOccupationRatherThanSeatBelt() throws Exception {
        Path path = Paths.get("app", "src", "geely", "java", "dezz", "status", "widget",
                "car", "GeelyCarIntegration.java");
        if (!Files.exists(path)) {
            path = Paths.get("src", "geely", "java", "dezz", "status", "widget", "car",
                    "GeelyCarIntegration.java");
        }
        String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        assertTrue(source.contains("SENSOR_TYPE_SEAT_OCCUPATION_STATUS_PASSENGER"));
        assertTrue(source.contains("SEAT_OCCUPATION_STATUS_OCCUPIED) return 1"));
        assertTrue(source.contains("SEAT_OCCUPATION_STATUS_NONE) return 0"));
        assertTrue(source.contains("return Integer.MIN_VALUE"));
        assertFalse(source.contains("SENSOR_TYPE_SAFE_BELT_PASSENGER"));
    }
}
