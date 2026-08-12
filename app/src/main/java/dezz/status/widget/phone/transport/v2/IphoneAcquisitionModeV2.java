/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Android-central acquisition is chosen explicitly; one never falls back to the other. */
public enum IphoneAcquisitionModeV2 {
    /** Normal recovery: open one public GATT owner for the already selected system bond. */
    SELECTED_BOND,
    /** User-driven bootstrap only: scan the v2 service and require selected-bond attribution. */
    EXPLICIT_BOOTSTRAP_SCAN
}
