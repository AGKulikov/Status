/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.phone;

/**
 * Selects the iPhone battery percentage without allowing a coarse source to overwrite a direct
 * Android/BLE reading.
 *
 * <p>On recent iOS versions {@code UIDevice.batteryLevel} can advance in roughly five-percent
 * steps. Android's selected-device battery broadcast and the standard BLE Battery Service carry
 * an integer in the full {@code 0..100} range, so those direct sources always win. The Helper and
 * six-step HFP indicator remain availability fallbacks only.</p>
 */
public final class PhoneBatteryLevelPolicy {
    private PhoneBatteryLevelPolicy() {
    }

    public static final class Reading {
        public final int level;
        public final String source;
        public final boolean direct;

        private Reading(int level, String source, boolean direct) {
            this.level = level;
            this.source = source;
            this.direct = direct;
        }
    }

    public static Reading resolve(
            boolean basKnown, Integer basLevel, long basUpdatedAt,
            boolean androidKnown, Integer androidLevel, long androidUpdatedAt,
            Integer helperLevel,
            boolean hfpKnown, Integer hfpLevel, boolean hfpPercentScale) {
        boolean validBas = basKnown && valid(basLevel);
        boolean validAndroid = androidKnown && valid(androidLevel);

        // Both are direct 0..100 transports. Prefer the one that changed most recently so an
        // Android framework broadcast and a BAS notification can coexist without stale rollback.
        if (validBas && (!validAndroid || basUpdatedAt >= androidUpdatedAt)) {
            return new Reading(basLevel, "ble_bas", true);
        }
        if (validAndroid) {
            return new Reading(androidLevel, "android_broadcast", true);
        }

        // Some ECARX HFP callbacks already contain a real percentage instead of Apple's 0..5
        // accessory scale. Preserve that full-range value ahead of the coarse Helper fallback.
        if (hfpKnown && hfpPercentScale && valid(hfpLevel)) {
            return new Reading(hfpLevel, "hfp_ecarx_percent", true);
        }
        if (valid(helperLevel)) {
            return new Reading(helperLevel, "iphone_helper_coarse", false);
        }
        if (hfpKnown && valid(hfpLevel)) {
            return new Reading(hfpLevel, "hfp_ecarx_coarse", false);
        }
        return null;
    }

    private static boolean valid(Integer level) {
        return level != null && level >= 0 && level <= 100;
    }
}
