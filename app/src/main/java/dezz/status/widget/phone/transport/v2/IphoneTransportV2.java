/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/**
 * Platform-neutral contract implemented by the two clean-room Android BLE adapters.
 *
 * <p>No Android object and no type nested in the legacy transport crosses this boundary.  Calls
 * are serialized by the owner, while implementations deliver listener callbacks on the owner's
 * executor.</p>
 */
public interface IphoneTransportV2 extends AutoCloseable {
    IphoneBleMode mode();

    void start(IphoneTransportStartRequest request, IphoneTransportSessionListenerV2 listener);

    /** Begins bounded teardown of the exact active epoch. */
    void stop(BleRouteEpoch epoch, IphoneTransportStopReason reason);

    IphoneTransportStatusV2 status();

    @Override void close();
}
