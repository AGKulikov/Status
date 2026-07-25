/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package dezz.status.widget.launcher.climate;

/** Shared precedence rule for the confirmed HVAC master-power state. */
public final class ClimatePowerStatePolicy {
    private ClimatePowerStatePolicy() {
    }

    /**
     * A confirmed inactive master switch wins over stale or unknown child-control values.
     * An unknown power state must not be interpreted as OFF.
     */
    public static boolean isConfirmedOff(boolean powerKnown, boolean powerActive) {
        return powerKnown && !powerActive;
    }
}
