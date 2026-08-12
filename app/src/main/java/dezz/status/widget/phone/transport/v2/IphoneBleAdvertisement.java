/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;
import java.util.UUID;

/**
 * UUID-only pre-connect advertisement classification. Local name, claimed identity, nonce, and
 * rotating BLE address are intentionally absent; identity is established by encrypted H proof.
 */
public final class IphoneBleAdvertisement {
    public final UUID serviceUuid;
    public final int protocolVersion;
    public final BlePeerRole advertiserRole;
    public final boolean connectable;
    /** Adapter proof that this result maps to the already selected system bond. */
    public final boolean attributableToSelectedBond;

    public IphoneBleAdvertisement(UUID serviceUuid, int protocolVersion,
                                 BlePeerRole advertiserRole, boolean connectable) {
        this(serviceUuid, protocolVersion, advertiserRole, connectable, false);
    }

    public IphoneBleAdvertisement(UUID serviceUuid, int protocolVersion,
                                 BlePeerRole advertiserRole, boolean connectable,
                                 boolean attributableToSelectedBond) {
        this.serviceUuid = Objects.requireNonNull(serviceUuid, "serviceUuid");
        this.protocolVersion = protocolVersion;
        this.advertiserRole = Objects.requireNonNull(advertiserRole, "advertiserRole");
        this.connectable = connectable;
        this.attributableToSelectedBond = attributableToSelectedBond;
    }

    /** Strict service/role match plus adapter-provided exact selected-bond attribution. */
    public boolean matchesForAndroidCentral() {
        return connectable
                && attributableToSelectedBond
                && protocolVersion == IphoneBleProtocolV2.VERSION
                && advertiserRole == BlePeerRole.IPHONE_HELPER_PERIPHERAL
                && IphoneBleProtocolV2.HELPER_PERIPHERAL_SERVICE.equals(serviceUuid);
    }

    static String normalizePeerId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
    }
}
