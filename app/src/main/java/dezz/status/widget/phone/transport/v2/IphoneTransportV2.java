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

    /**
     * Liveness-only hint that the exact selected phone has just appeared on a Classic profile.
     *
     * <p>This is never identity or authorization evidence. Implementations may use it only to
     * prompt an already-owned route; they must not select a peer or allocate a second framework
     * owner from this signal.</p>
     */
    default void selectedPhonePresent() {
    }

    /** Best-effort enqueue to C5; implementations must preserve the platform GATT FIFO. */
    default void sendCarRemoteFrame(byte[] frame) {
    }

    @Override void close();
}
