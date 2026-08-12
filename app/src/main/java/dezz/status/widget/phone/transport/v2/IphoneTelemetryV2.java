/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

/** Atomic helper telemetry already decoded and CRC-validated by the wire adapter. */
public final class IphoneTelemetryV2 {
    public final Integer batteryPercent;
    public final Boolean externalPower;
    public final String chargeState;
    public final String networkType;
    public final Boolean phoneLocked;
    public final int sequence;

    public IphoneTelemetryV2(Integer batteryPercent, Boolean externalPower, String chargeState,
                             String networkType, Boolean phoneLocked, int sequence) {
        if (batteryPercent != null && (batteryPercent < 0 || batteryPercent > 100)) {
            throw new IllegalArgumentException("batteryPercent out of range");
        }
        if (sequence < 0 || sequence > 0xffff) {
            throw new IllegalArgumentException("sequence out of range");
        }
        this.batteryPercent = batteryPercent;
        this.externalPower = externalPower;
        this.chargeState = chargeState == null ? "unknown" : chargeState;
        this.networkType = networkType == null ? "" : networkType;
        this.phoneLocked = phoneLocked;
        this.sequence = sequence;
    }
}
