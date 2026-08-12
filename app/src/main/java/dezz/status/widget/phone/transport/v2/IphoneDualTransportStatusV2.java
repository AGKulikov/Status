/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Failure;
import dezz.status.widget.phone.transport.switching.BleRoleSwitchReducer.Phase;
import java.util.Objects;

/** Immutable user-visible ownership state of the two-route v2 runtime. */
public final class IphoneDualTransportStatusV2 {
    public final IphoneBleMode desiredMode;
    public final IphoneBleMode activeMode;
    public final Phase switchPhase;
    public final Failure switchFailure;
    public final IphoneTransportStatusV2 routeStatus;
    public final String detail;

    public IphoneDualTransportStatusV2(
            IphoneBleMode desiredMode,
            IphoneBleMode activeMode,
            Phase switchPhase,
            Failure switchFailure,
            IphoneTransportStatusV2 routeStatus,
            String detail
    ) {
        this.desiredMode = Objects.requireNonNull(desiredMode, "desiredMode");
        this.activeMode = activeMode;
        this.switchPhase = Objects.requireNonNull(switchPhase, "switchPhase");
        this.switchFailure = Objects.requireNonNull(switchFailure, "switchFailure");
        this.routeStatus = routeStatus;
        this.detail = detail == null ? "" : detail;
    }
}
