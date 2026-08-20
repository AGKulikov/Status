/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/**
 * Structured recovery meaning of a route status.
 *
 * <p>The controller must not infer ownership from a localized/detail string.  In particular,
 * {@link #WAIT_SERVICE_CHANGED} and {@link #WAIT_AUTHORIZATION} retain one live GATT owner and
 * therefore forbid a reconnect storm.</p>
 */
public enum IphoneTransportRecoveryStateV2 {
    /** No route owner has been published yet. */
    NO_OWNER,
    /** The route owns or is acquiring framework resources and its own deadlines are active. */
    PROGRESSING,
    /** Exact LE owner is alive; ANCS is absent and GATT Service Changed is armed. */
    WAIT_SERVICE_CHANGED,
    /** Exact encrypted owner is alive but iOS authorization/user action is required. */
    WAIT_AUTHORIZATION,
    /** ANCS subscriptions are ready on the exact selected-bond route. */
    READY,
    /** Route-local owner is terminal and a fresh same-topology generation is allowed. */
    OWNER_DOWN
}
