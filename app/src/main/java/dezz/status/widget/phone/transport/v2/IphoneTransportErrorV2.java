/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.util.Objects;

public final class IphoneTransportErrorV2 {
    public enum Kind {
        TIMEOUT,
        RADIO,
        ADVERTISEMENT_MISMATCH,
        PEER_PROOF_REJECTED,
        GATT,
        PROTOCOL,
        TEARDOWN
    }

    public final IphoneBleMode mode;
    public final BleRouteEpoch epoch;
    public final Kind kind;
    public final String detail;
    public final boolean retryable;

    public IphoneTransportErrorV2(IphoneBleMode mode, BleRouteEpoch epoch, Kind kind,
                                  String detail, boolean retryable) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.epoch = Objects.requireNonNull(epoch, "epoch");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.detail = detail == null ? "" : detail;
        this.retryable = retryable;
    }
}
