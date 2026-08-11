/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

/** Pure wire-format and lineage rules for the stable F04 managed-incoming beacon. */
final class ManagedIncomingPublicationPolicy {
    enum AdvertisingCallbackAction {
        OBSERVE_STALE,
        IGNORE_DUPLICATE,
        ACCEPT_SUCCESS,
        ACCEPT_FAILURE
    }

    static final int LEGACY_NAMESPACE_PROTOCOL = 1;
    static final int PUBLICATION_NONCE_PROTOCOL = 2;
    static final int MIN_PUBLICATION_NONCE = 1;
    static final int MAX_PUBLICATION_NONCE = 0xFFFFFE;
    static final int RESERVED_PUBLICATION_NONCE = 0xFFFFFF;

    private ManagedIncomingPublicationPolicy() {
    }

    /**
     * Returns the next durable 24-bit publication incarnation. Zero and all-ones remain reserved
     * so a decoder can distinguish missing/corrupt data from a committed F04 publication.
     */
    static int nextPublicationNonce(int persistedNonce) {
        if (!isValidPublicationNonce(persistedNonce)
                || persistedNonce == MAX_PUBLICATION_NONCE) {
            return MIN_PUBLICATION_NONCE;
        }
        return persistedNonce + 1;
    }

    static boolean isValidPublicationNonce(int nonce) {
        return nonce >= MIN_PUBLICATION_NONCE && nonce <= MAX_PUBLICATION_NONCE;
    }

    /** A framework callback owns state only for its exact live publication/nonce tuple. */
    static AdvertisingCallbackAction advertisingCallbackAction(
            boolean callbackIsActive,
            long callbackPublicationToken,
            int callbackPublicationNonce,
            long currentPublicationToken,
            int currentPublicationNonce,
            boolean outcomeAlreadyHandled,
            boolean success) {
        boolean exactCurrent = callbackIsActive
                && callbackPublicationToken != 0L
                && callbackPublicationToken == currentPublicationToken
                && callbackPublicationNonce == currentPublicationNonce;
        if (!exactCurrent) return AdvertisingCallbackAction.OBSERVE_STALE;
        if (outcomeAlreadyHandled) return AdvertisingCallbackAction.IGNORE_DUPLICATE;
        return success
                ? AdvertisingCallbackAction.ACCEPT_SUCCESS
                : AdvertisingCallbackAction.ACCEPT_FAILURE;
    }

    /** Legacy scan-response frame retained for Helper v42 and older decoders. */
    static byte[] legacyNamespaceFrame(int generation) {
        requireValidGeneration(generation);
        return new byte[]{
                (byte) LEGACY_NAMESPACE_PROTOCOL,
                (byte) ((generation >>> 8) & 0xFF),
                (byte) (generation & 0xFF)
        };
    }

    /**
     * Six-byte manufacturer payload. Together with Flags, one 128-bit service UUID and company
     * ID this exactly fits the 31-byte legacy advertising packet used by Android 9.
     */
    static byte[] publicationNonceFrame(int generation, int publicationNonce) {
        requireValidGeneration(generation);
        if (!isValidPublicationNonce(publicationNonce)) {
            throw new IllegalArgumentException("Invalid publication nonce: "
                    + publicationNonce);
        }
        return new byte[]{
                (byte) PUBLICATION_NONCE_PROTOCOL,
                (byte) ((generation >>> 8) & 0xFF),
                (byte) (generation & 0xFF),
                (byte) ((publicationNonce >>> 16) & 0xFF),
                (byte) ((publicationNonce >>> 8) & 0xFF),
                (byte) (publicationNonce & 0xFF)
        };
    }

    private static void requireValidGeneration(int generation) {
        if (generation <= 0 || generation >= 0xFFFF) {
            throw new IllegalArgumentException("Invalid namespace generation: " + generation);
        }
    }
}
