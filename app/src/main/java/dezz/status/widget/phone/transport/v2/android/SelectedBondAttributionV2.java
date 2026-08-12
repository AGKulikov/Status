/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2.android;

import android.bluetooth.BluetoothDevice;

import dezz.status.widget.phone.transport.v2.SelectedBondIdentityResolverV2;

/**
 * Platform seam for proving that a foreground bootstrap result belongs to the selected system
 * bond.  Implementations must never use local name as evidence.
 */
public interface SelectedBondAttributionV2 {
    SelectedBondIdentityResolverV2.Candidate begin(
            BluetoothDevice observed,
            String selectedSystemBondAddress,
            int selectedSystemBondMatches,
            String anchoredHelperInstallationId);

    SelectedBondIdentityResolverV2.Decision complete(
            SelectedBondIdentityResolverV2.Candidate candidate,
            String observedHelperInstallationId,
            int selectedSystemBondMatchesNow,
            int activeEncryptedOwnerMatches,
            boolean encryptedAttributeAccessProven);

    /**
     * Android-9 public-API implementation. It requires direct address equality with the unique
     * selected bond. A different private/RPA facade is rejected even when H UUID matches because
     * public APIs expose no binding from that facade to the selected bond.
     */
    SelectedBondAttributionV2 STRICT_PUBLIC_API = new SelectedBondAttributionV2() {
        @Override public SelectedBondIdentityResolverV2.Candidate begin(
                BluetoothDevice observed, String selectedAddress,
                int selectedBondMatches, String anchoredHelperInstallationId) {
            return SelectedBondIdentityResolverV2.begin(
                    selectedAddress, selectedBondMatches,
                    observed == null ? null : observed.getAddress(),
                    observed != null && observed.getBondState() == BluetoothDevice.BOND_BONDED,
                    anchoredHelperInstallationId);
        }

        @Override public SelectedBondIdentityResolverV2.Decision complete(
                SelectedBondIdentityResolverV2.Candidate candidate,
                String observedHelperInstallationId,
                int selectedBondMatchesNow,
                int activeEncryptedOwnerMatches,
                boolean encryptedAttributeAccessProven) {
            return SelectedBondIdentityResolverV2.complete(candidate,
                    observedHelperInstallationId, selectedBondMatchesNow,
                    activeEncryptedOwnerMatches, encryptedAttributeAccessProven);
        }
    };
}
