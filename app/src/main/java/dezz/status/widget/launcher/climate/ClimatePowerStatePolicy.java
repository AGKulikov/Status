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

    /**
     * Some ECARX variants never expose a usable HVAC_FUNC_POWER value. A confirmed stopped fan or
     * confirmed BLOWING_MODE_OFF is their observable master-off state. Direct power remains
     * authoritative whenever it is known, so a delayed child callback cannot turn an explicitly
     * active climate system off in the UI.
     */
    public static boolean isConfirmedOff(boolean powerKnown, boolean powerActive,
                                         boolean fanKnown, boolean fanActive,
                                         boolean airflowKnown, boolean airflowActive) {
        if (powerKnown) return !powerActive;
        return (fanKnown && !fanActive) || (airflowKnown && !airflowActive);
    }
}
