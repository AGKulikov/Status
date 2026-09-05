/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

import java.util.Objects;

import dezz.status.widget.phone.transport.v2.IphoneTransportLifecycle;

/**
 * Joins the independently serialized route lifecycle and dual-role coordinator lifecycle.
 *
 * <p>The route must report READY before the coordinator can commit its target, so callbacks are
 * normally observed as route READY followed by coordinator ACTIVE.  Delivery is allowed only
 * after both facts have arrived, regardless of their callback order.</p>
 */
final class AncsReadinessGateV2 {
    private IphoneTransportLifecycle routeLifecycle = IphoneTransportLifecycle.STOPPED;
    private boolean coordinatorActive;

    boolean onRouteLifecycle(IphoneTransportLifecycle lifecycle) {
        routeLifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        return isReady();
    }

    boolean onCoordinatorActive(boolean active) {
        coordinatorActive = active;
        return isReady();
    }

    boolean isReady() {
        return coordinatorActive && routeLifecycle == IphoneTransportLifecycle.READY;
    }

    void reset() {
        routeLifecycle = IphoneTransportLifecycle.STOPPED;
        coordinatorActive = false;
    }
}
