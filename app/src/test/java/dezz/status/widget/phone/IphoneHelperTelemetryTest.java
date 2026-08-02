/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class IphoneHelperTelemetryTest {
    @Test public void parsesExactPowerStateWithoutInference() {
        IphoneHelperTelemetry value = parse("TEL2;P;60;1;C;42");
        assertEquals(IphoneHelperTelemetry.Kind.POWER, value.kind);
        assertEquals(Integer.valueOf(60), value.batteryLevel);
        assertEquals(Boolean.TRUE, value.externalPower);
        assertEquals("charging", value.chargeState);
        assertEquals(42, value.sequence);

        IphoneHelperTelemetry unplugged = parse("TEL2;P;-;0;U;43");
        assertNull(unplugged.batteryLevel);
        assertFalse(unplugged.externalPower);
        assertEquals("unplugged", unplugged.chargeState);
    }

    @Test public void parsesCanonicalAppleNetworkLabels() {
        assertEquals("LTE", parse("TEL2;N;LTE;1").networkType);
        assertEquals("5G_UW", parse("TEL2;N;5G_UW;2").networkType);
        assertEquals("", parse("TEL2;N;-;3").networkType);
    }

    @Test public void rejectsMalformedOrUntrustedVocabulary() {
        assertNull(IphoneHelperTelemetry.parse(null));
        assertNull(raw("TEL1;N;LTE;1"));
        assertNull(raw("TEL2;P;101;1;C;1"));
        assertNull(raw("TEL2;P;50;maybe;C;1"));
        assertNull(raw("TEL2;N;WIFI;1"));
        assertNull(raw("TEL2;N;LTE;10000"));
    }

    private static IphoneHelperTelemetry parse(String value) {
        IphoneHelperTelemetry parsed = raw(value);
        assertTrue(parsed != null);
        return parsed;
    }

    private static IphoneHelperTelemetry raw(String value) {
        return IphoneHelperTelemetry.parse(value.getBytes(StandardCharsets.UTF_8));
    }
}
