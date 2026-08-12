/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.bluetooth.BluetoothDevice;

/**
 * Platform seam for proving that a foreground bootstrap result belongs to the selected system
 * bond.  Implementations must never use local name as evidence.
 */
public interface SelectedBondAttributionV2 {
    boolean isSelectedBond(BluetoothDevice observed, String selectedSystemBondAddress);

    /** Conservative public-API implementation.  A vendor resolver may prove an RPA separately. */
    SelectedBondAttributionV2 STRICT_PUBLIC_ADDRESS = (observed, selectedAddress) ->
            observed != null
                    && observed.getBondState() == BluetoothDevice.BOND_BONDED
                    && observed.getAddress() != null
                    && observed.getAddress().equalsIgnoreCase(selectedAddress);
}
