/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone.transport.v2;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Globally unique activation identity supplied by the switch coordinator.
 *
 * <p>{@code processNonce} must be regenerated for every process launch and {@code sequence} must
 * increase for every start, radio restart, or mode switch inside that process.  Consequently a
 * callback retained by Android's Bluetooth process cannot become current after an APK hot update
 * merely because a small integer generation was reused.</p>
 */
public final class BleRouteEpoch {
    public final long processNonce;
    /** Unbounded so a long-lived process can never make an old callback current by wraparound. */
    public final BigInteger sequence;

    public BleRouteEpoch(long processNonce, long sequence) {
        this(processNonce, BigInteger.valueOf(sequence));
    }

    public BleRouteEpoch(long processNonce, BigInteger sequence) {
        if (processNonce == 0L) throw new IllegalArgumentException("processNonce must be non-zero");
        this.sequence = Objects.requireNonNull(sequence, "sequence");
        if (sequence.signum() <= 0) throw new IllegalArgumentException("sequence must be positive");
        this.processNonce = processNonce;
    }

    public BleRouteEpoch(long processNonce, String decimalSequence) {
        this(processNonce, new BigInteger(Objects.requireNonNull(
                decimalSequence, "decimalSequence")));
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BleRouteEpoch)) return false;
        BleRouteEpoch that = (BleRouteEpoch) other;
        return processNonce == that.processNonce && sequence.equals(that.sequence);
    }

    @Override public int hashCode() {
        return Objects.hash(processNonce, sequence);
    }

    @Override public String toString() {
        return Long.toUnsignedString(processNonce, 16) + ":" + sequence;
    }
}
