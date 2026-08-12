/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.switching;

/** Which endpoint committed the current wire C/A role-switch handshake. */
public enum BleRoleSwitchOrigin {
    LOCAL,
    REMOTE,
    /** Process restart or same-role recovery drains locally while the peer retains its role. */
    LOCAL_ONLY_RESTORE
}
