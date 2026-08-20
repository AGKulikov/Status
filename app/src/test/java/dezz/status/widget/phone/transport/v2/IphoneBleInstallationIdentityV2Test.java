/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.security.SecureRandom;
import java.util.UUID;
import org.junit.Test;

public final class IphoneBleInstallationIdentityV2Test {
    @Test public void generatedIdentityIsCanonicalNonZeroV4() {
        UUID generated = IphoneBleInstallationIdentityV2.generate(new SecureRandom());
        String canonical = IphoneBleInstallationIdentityV2.canonical(generated);

        assertNotEquals(new UUID(0L, 0L), generated);
        assertEquals(4, generated.version());
        assertEquals(2, generated.variant());
        assertEquals(generated,
                IphoneBleInstallationIdentityV2.parseCanonical(canonical));
    }

    @Test public void strictParserRejectsAmbiguousOrInvalidPersistence() {
        assertNull(IphoneBleInstallationIdentityV2.parseCanonical(null));
        assertNull(IphoneBleInstallationIdentityV2.parseCanonical(""));
        assertNull(IphoneBleInstallationIdentityV2.parseCanonical(
                "00000000-0000-0000-0000-000000000000"));
        assertNull(IphoneBleInstallationIdentityV2.parseCanonical(
                "8F04FE8D-11C2-4B3A-9AB7-F4512AA2A21D"));
        assertNull(IphoneBleInstallationIdentityV2.parseCanonical(
                "8f04fe8d11c24b3a9ab7f4512aa2a21d"));
        assertNotNull(IphoneBleInstallationIdentityV2.parseCanonical(
                "8f04fe8d-11c2-4b3a-9ab7-f4512aa2a21d"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroCannotBeSerialized() {
        IphoneBleInstallationIdentityV2.canonical(new UUID(0L, 0L));
    }
}
