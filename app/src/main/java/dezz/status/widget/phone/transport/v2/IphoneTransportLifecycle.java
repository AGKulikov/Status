/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

public enum IphoneTransportLifecycle {
    STOPPED,
    WAIT_RADIO,
    STARTING,
    CONNECTING,
    AUTHENTICATING,
    SUBSCRIBING,
    READY,
    RETRY_WAIT,
    STOPPING,
    FAILED
}
