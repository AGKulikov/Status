/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

public final class IphoneTransportStatusV2 {
    public final IphoneBleMode mode;
    public final BleRouteEpoch epoch;
    public final IphoneTransportLifecycle lifecycle;
    public final String selectedSystemBondAddress;
    public final String helperInstallationId;
    public final String detail;
    public final int consecutiveFailures;

    public IphoneTransportStatusV2(IphoneBleMode mode, BleRouteEpoch epoch,
                                   IphoneTransportLifecycle lifecycle,
                                   String selectedSystemBondAddress,
                                   String helperInstallationId,
                                   String detail, int consecutiveFailures) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.selectedSystemBondAddress =
                IphoneBleAdvertisement.normalizePeerId(selectedSystemBondAddress);
        this.helperInstallationId =
                IphoneBleAdvertisement.normalizePeerId(helperInstallationId);
        this.detail = detail == null ? "" : detail;
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
    }
}
