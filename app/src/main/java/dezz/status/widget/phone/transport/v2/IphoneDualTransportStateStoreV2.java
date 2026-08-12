/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/**
 * Crash-safe local state required before either v2 route performs a Bluetooth side effect.
 * Implementations must synchronously commit and reread switch snapshots.
 */
public interface IphoneDualTransportStateStoreV2 extends IphoneBleIdentityRegistryV2.Store {
    /** Distinguishes first migration from a present-but-empty/corrupt snapshot. */
    boolean hasSwitchSnapshot();

    String switchSnapshot();

    void persistSwitchSnapshot(String encodedSnapshot);
}
