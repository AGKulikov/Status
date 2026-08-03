/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import androidx.annotation.NonNull;

/**
 * Small, Android-independent ownership barrier for the ANCS link.
 *
 * <p>Android may deliver callbacks and watchdogs from a BluetoothGatt that has already been
 * closed. Every scan/connect/discovery cycle receives a monotonically increasing generation;
 * only the current generation is allowed to advance the link. The transport still checks the
 * BluetoothGatt object itself, while this barrier also protects delayed tasks that do not carry
 * a framework object.</p>
 */
final class AncsSessionStateMachine {
    enum Phase {
        IDLE,
        BACKGROUND_CONNECT,
        SCANNING,
        DIRECT_CONNECT,
        DISCOVERING,
        SUBSCRIBING,
        READY,
        VERIFYING_LINK,
        RETRY_WAIT,
        CLOSED
    }

    private long generation;
    @NonNull private Phase phase = Phase.IDLE;

    long begin(@NonNull Phase next) {
        if (next == Phase.CLOSED) throw new IllegalArgumentException("Use close()");
        generation++;
        phase = next;
        return generation;
    }

    boolean move(long expectedGeneration, @NonNull Phase next) {
        if (!isCurrent(expectedGeneration) || phase == Phase.CLOSED) return false;
        phase = next;
        return true;
    }

    boolean isCurrent(long expectedGeneration) {
        return expectedGeneration > 0L && expectedGeneration == generation;
    }

    boolean is(long expectedGeneration, @NonNull Phase expectedPhase) {
        return isCurrent(expectedGeneration) && phase == expectedPhase;
    }

    @NonNull Phase phase() {
        return phase;
    }

    long generation() {
        return generation;
    }

    void close() {
        generation++;
        phase = Phase.CLOSED;
    }
}
