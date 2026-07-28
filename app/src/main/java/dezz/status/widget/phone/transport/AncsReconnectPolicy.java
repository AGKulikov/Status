/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.phone.transport;

import androidx.annotation.NonNull;

/**
 * Retry timing and identity rules for the dedicated iPhone_ANCS transport.
 *
 * <p>The transport keeps quick recovery inside one owner/session. Recreating the whole
 * controller for every Android status 133 loses the controller's IRK resolution context and
 * leaves the user waiting behind a minute-long outer backoff.</p>
 */
public final class AncsReconnectPolicy {
    private static final long[] RETRY_DELAYS_MS = {
            250L, 750L, 1_500L, 3_000L, 5_000L, 8_000L, 15_000L
    };

    private AncsReconnectPolicy() {
    }

    /** Unlimited retry schedule with a short ceiling suitable for an in-car powered receiver. */
    public static long retryDelayMillis(int attempt) {
        if (attempt <= 0) return RETRY_DELAYS_MS[0];
        return RETRY_DELAYS_MS[Math.min(attempt, RETRY_DELAYS_MS.length - 1)];
    }

    /**
     * A broad software scan may accept the selected identity after Android resolves an iOS
     * private address. ANCS solicitation is required unless the address already matches exactly.
     */
    public static boolean candidateMayBeSelected(
            @NonNull String selectedAddress,
            @NonNull String observedAddress,
            boolean selectedBonded,
            boolean observedBonded,
            boolean solicitsAncs,
            boolean uniqueBondedNameMatch) {
        String selected = selectedAddress.trim();
        String observed = observedAddress.trim();
        if (!selected.isEmpty() && selected.equalsIgnoreCase(observed)) return true;
        return solicitsAncs && selectedBonded && observedBonded && uniqueBondedNameMatch;
    }
}
