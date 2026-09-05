/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Stable, non-zero 128-bit installation identity carried by the encrypted v2 H frame. */
public final class IphoneBleInstallationIdentityV2 {
    private static final UUID ZERO = new UUID(0L, 0L);

    private IphoneBleInstallationIdentityV2() {
    }

    /** Generates a UUIDv4-shaped identity from the caller-owned cryptographic RNG. */
    public static UUID generate(SecureRandom random) {
        Objects.requireNonNull(random, "random");
        byte[] bytes = new byte[IphoneBleControlProtocolV2.PAYLOAD_BYTES];
        do {
            random.nextBytes(bytes);
        } while (allZero(bytes));
        // Keep the conventional UUIDv4 variant bits without reducing the identity's stability.
        bytes[6] = (byte) ((bytes[6] & 0x0f) | 0x40);
        bytes[8] = (byte) ((bytes[8] & 0x3f) | 0x80);
        long most = 0L;
        long least = 0L;
        for (int index = 0; index < 8; index++) {
            most = (most << 8) | (bytes[index] & 0xffL);
            least = (least << 8) | (bytes[index + 8] & 0xffL);
        }
        return new UUID(most, least);
    }

    /** Strict parser used for persisted identities. Empty, non-canonical, and zero fail closed. */
    public static UUID parseCanonical(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        String candidate = trimmed.toLowerCase(Locale.US);
        if (!value.equals(trimmed) || !trimmed.equals(candidate)) return null;
        if (candidate.length() != 36) return null;
        final UUID parsed;
        try {
            parsed = UUID.fromString(candidate);
        } catch (IllegalArgumentException malformed) {
            return null;
        }
        return !ZERO.equals(parsed) && parsed.toString().equals(candidate) ? parsed : null;
    }

    public static String canonical(UUID identity) {
        Objects.requireNonNull(identity, "identity");
        if (ZERO.equals(identity)) {
            throw new IllegalArgumentException("installation identity must be non-zero");
        }
        return identity.toString().toLowerCase(Locale.US);
    }

    private static boolean allZero(byte[] bytes) {
        int combined = 0;
        for (byte value : bytes) combined |= value & 0xff;
        return combined == 0;
    }
}
