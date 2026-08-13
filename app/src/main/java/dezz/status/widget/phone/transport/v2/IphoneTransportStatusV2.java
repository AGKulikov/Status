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
    public final IphoneTransportRecoveryStateV2 recoveryState;

    public IphoneTransportStatusV2(IphoneBleMode mode, BleRouteEpoch epoch,
                                   IphoneTransportLifecycle lifecycle,
                                   String selectedSystemBondAddress,
                                   String helperInstallationId,
                                   String detail, int consecutiveFailures) {
        this(mode, epoch, lifecycle, selectedSystemBondAddress, helperInstallationId,
                detail, consecutiveFailures, defaultRecoveryState(lifecycle));
    }

    public IphoneTransportStatusV2(IphoneBleMode mode, BleRouteEpoch epoch,
                                   IphoneTransportLifecycle lifecycle,
                                   String selectedSystemBondAddress,
                                   String helperInstallationId,
                                   String detail, int consecutiveFailures,
                                   IphoneTransportRecoveryStateV2 recoveryState) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.selectedSystemBondAddress =
                IphoneBleAdvertisement.normalizePeerId(selectedSystemBondAddress);
        this.helperInstallationId =
                IphoneBleAdvertisement.normalizePeerId(helperInstallationId);
        this.detail = detail == null ? "" : detail;
        this.consecutiveFailures = Math.max(0, consecutiveFailures);
        this.recoveryState = Objects.requireNonNull(recoveryState, "recoveryState");
    }

    private static IphoneTransportRecoveryStateV2 defaultRecoveryState(
            IphoneTransportLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (lifecycle == IphoneTransportLifecycle.READY) {
            return IphoneTransportRecoveryStateV2.READY;
        }
        if (lifecycle == IphoneTransportLifecycle.FAILED
                || lifecycle == IphoneTransportLifecycle.STOPPED) {
            return IphoneTransportRecoveryStateV2.OWNER_DOWN;
        }
        if (lifecycle == IphoneTransportLifecycle.WAIT_RADIO) {
            return IphoneTransportRecoveryStateV2.NO_OWNER;
        }
        return IphoneTransportRecoveryStateV2.PROGRESSING;
    }
}
