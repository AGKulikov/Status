/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

/** Exact owner/operation identity attached to every asynchronous platform callback. */
public final class BleRouteToken {
    public final IphoneBleMode mode;
    public final BleRouteEpoch epoch;
    public final long ownerId;
    public final long operationId;

    public BleRouteToken(IphoneBleMode mode, BleRouteEpoch epoch,
                         long ownerId, long operationId) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        if (ownerId <= 0L) throw new IllegalArgumentException("ownerId must be positive");
        if (operationId <= 0L) throw new IllegalArgumentException("operationId must be positive");
        this.ownerId = ownerId;
        this.operationId = operationId;
    }

    public boolean sameOwner(BleRouteToken other) {
        return other != null && mode == other.mode && epoch.equals(other.epoch)
                && ownerId == other.ownerId;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BleRouteToken)) return false;
        BleRouteToken that = (BleRouteToken) other;
        return ownerId == that.ownerId && operationId == that.operationId
                && mode == that.mode && epoch.equals(that.epoch);
    }

    @Override public int hashCode() {
        return Objects.hash(mode, epoch, ownerId, operationId);
    }

    @Override public String toString() {
        return mode.stableKey + "/" + epoch + "/" + ownerId + "/" + operationId;
    }
}
