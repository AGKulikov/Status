/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Android-central acquisition is chosen explicitly; one never falls back to the other. */
public enum IphoneAcquisitionModeV2 {
    /** Production bootstrap/recovery: open one public GATT owner for the selected system bond. */
    SELECTED_BOND,
    /** Routine route after authenticated enrollment: connect saved LE locator, then prove C4/H. */
    ENROLLED_LE_IDENTITY,
    /** Foreground diagnostics only: scan v2 and still require selected-bond attribution. */
    EXPLICIT_BOOTSTRAP_SCAN
}
