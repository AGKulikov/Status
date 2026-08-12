/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/**
 * Decoded {@code H}/PEER_PROOF control frame plus link authority established by the adapter.
 * Its authority comes from the exact encrypted system-bond owner; the frame is not a
 * cryptographic proof by itself.
 */
public final class IphoneBlePeerProof {
    public final int protocolVersion;
    public final IphoneBleMode routeMode;
    public final BlePeerRole peerRole;
    public final String peerId;
    public final boolean telemetrySupported;
    public final boolean ancsSupported;
    public final boolean encryptedBondedLink;

    public IphoneBlePeerProof(int protocolVersion, IphoneBleMode routeMode,
                             BlePeerRole peerRole, String peerId,
                             boolean telemetrySupported,
                             boolean ancsSupported, boolean encryptedBondedLink) {
        this.protocolVersion = protocolVersion;
        this.routeMode = routeMode;
        this.peerRole = peerRole;
        this.peerId = IphoneBleAdvertisement.normalizePeerId(peerId);
        this.telemetrySupported = telemetrySupported;
        this.ancsSupported = ancsSupported;
        this.encryptedBondedLink = encryptedBondedLink;
    }

    public boolean matches(String selectedPeerId, BlePeerRole expectedPeerRole,
                           IphoneBleMode expectedRouteMode) {
        return protocolVersion == IphoneBleProtocolV2.VERSION
                && routeMode == expectedRouteMode
                && peerRole == expectedPeerRole
                && !peerId.isEmpty()
                && (IphoneBleAdvertisement.normalizePeerId(selectedPeerId).isEmpty()
                    || peerId.equals(IphoneBleAdvertisement.normalizePeerId(selectedPeerId)))
                && ancsSupported
                && encryptedBondedLink;
    }
}
