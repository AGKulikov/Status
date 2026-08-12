/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

/** Exact ANCS parser lifetime bound to one route epoch and one GATT owner. */
public final class AncsSessionTokenV2 {
    public final BleRouteEpoch routeEpoch;
    public final long ownerId;
    public final long sessionId;

    public AncsSessionTokenV2(BleRouteEpoch routeEpoch, long ownerId, long sessionId) {
        this.routeEpoch = Objects.requireNonNull(routeEpoch, "routeEpoch");
        if (ownerId <= 0L || sessionId <= 0L) {
            throw new IllegalArgumentException("ownerId and sessionId must be positive");
        }
        this.ownerId = ownerId;
        this.sessionId = sessionId;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof AncsSessionTokenV2)) return false;
        AncsSessionTokenV2 that = (AncsSessionTokenV2) other;
        return ownerId == that.ownerId && sessionId == that.sessionId
                && routeEpoch.equals(that.routeEpoch);
    }

    @Override public int hashCode() {
        return Objects.hash(routeEpoch, ownerId, sessionId);
    }

    @Override public String toString() {
        return routeEpoch + "/" + ownerId + "/ancs-" + sessionId;
    }
}
