/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;

import dezz.status.widget.phone.transport.v2.BleRouteToken;

/**
 * Narrow platform seam for adopting the one reverse ANCS client created by the exact Helper
 * Central connection. The observer may use a platform-specific facility, but it may not create a
 * public fallback owner or compare a rotating address with the selected Classic bond address.
 */
public interface ReverseGattObserverV2 {
    interface Listener {
        void onObserved(BleRouteToken token, BluetoothGatt gatt,
                        boolean sameCapturedInboundPhysicalFacade,
                        boolean exactlyOneOwner);

        void onUnavailable(BleRouteToken token, String detail);
    }

    /** Starts one bounded observation for the exact captured inbound physical facade. */
    void observe(BleRouteToken token, BluetoothDevice capturedInboundPhysicalFacade,
                 BluetoothGattCallback callback, Listener listener);

    /** Close-only retirement. This must never disconnect the Helper's physical link. */
    void closeOnly(BleRouteToken token, BluetoothGatt gatt);

    /** Stops a pending observation that never yielded an owner. */
    void cancel(BleRouteToken token);
}
