/*
 * Copyright © 2026 Dezz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.status.widget.drivemode.car;

import androidx.annotation.Nullable;

// Public enum constants from Natro bundled ecarx-adaptapi.jar; UI catalog only, not raw CAN.

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dezz.status.widget.R;

public final class DriveModeCatalog {

    private static final List<DriveModeDescriptor> ALL;
    private static final Map<Integer, DriveModeDescriptor> BY_CODE;

    static {
        List<DriveModeDescriptor> list = new ArrayList<>();
        list.add(new DriveModeDescriptor(570491138,
                "comfort", R.string.drive_selector_mode_comfort, R.drawable.ic_mode_comfort, R.color.drive_selector_accent_comfort, true));
        list.add(new DriveModeDescriptor(570491137,
                "eco", R.string.drive_selector_mode_eco, R.drawable.ic_mode_eco, R.color.drive_selector_accent_eco, true));
        list.add(new DriveModeDescriptor(570491156,
                "eco_plus", R.string.drive_selector_mode_eco_plus, R.drawable.ic_mode_eco, R.color.drive_selector_accent_eco, true));
        list.add(new DriveModeDescriptor(570491139,
                "dynamic", R.string.drive_selector_mode_dynamic, R.drawable.ic_mode_sport, R.color.drive_selector_accent_sport, true));
        list.add(new DriveModeDescriptor(570491157,
                "sport_plus", R.string.drive_selector_mode_sport_plus, R.drawable.ic_mode_sport, R.color.drive_selector_accent_sport, true));
        list.add(new DriveModeDescriptor(570491153,
                "normal", R.string.drive_selector_mode_normal, R.drawable.ic_mode_generic, R.color.drive_selector_accent_neutral));
        list.add(new DriveModeDescriptor(570491145,
                "snow", R.string.drive_selector_mode_snow, R.drawable.ic_mode_snow, R.color.drive_selector_accent_snow, true));
        list.add(new DriveModeDescriptor(570491155,
                "offroad", R.string.drive_selector_mode_offroad, R.drawable.ic_mode_offroad, R.color.drive_selector_accent_offroad, true));
        list.add(new DriveModeDescriptor(570491146,
                "mud", R.string.drive_selector_mode_mud, R.drawable.ic_mode_mud, R.color.drive_selector_accent_mud));
        list.add(new DriveModeDescriptor(570491149,
                "sand", R.string.drive_selector_mode_sand, R.drawable.ic_mode_sand, R.color.drive_selector_accent_sand, true));
        list.add(new DriveModeDescriptor(570491147,
                "rock", R.string.drive_selector_mode_rock, R.drawable.ic_mode_rock, R.color.drive_selector_accent_rock));
        list.add(new DriveModeDescriptor(570491144,
                "power", R.string.drive_selector_mode_power, R.drawable.ic_mode_power, R.color.drive_selector_accent_power));
        list.add(new DriveModeDescriptor(570491143,
                "hybrid", R.string.drive_selector_mode_hybrid, R.drawable.ic_mode_hybrid, R.color.drive_selector_accent_hybrid));
        list.add(new DriveModeDescriptor(570491142,
                "pure", R.string.drive_selector_mode_pure, R.drawable.ic_mode_pure, R.color.drive_selector_accent_pure));
        list.add(new DriveModeDescriptor(570491150,
                "awd", R.string.drive_selector_mode_awd, R.drawable.ic_mode_awd, R.color.drive_selector_accent_awd));
        list.add(new DriveModeDescriptor(570491154,
                "eawd", R.string.drive_selector_mode_eawd, R.drawable.ic_mode_awd, R.color.drive_selector_accent_awd));
        list.add(new DriveModeDescriptor(570491200,
                "custom", R.string.drive_selector_mode_custom, R.drawable.ic_mode_custom, R.color.drive_selector_accent_custom));
        list.add(new DriveModeDescriptor(570491158,
                "adaptive", R.string.drive_selector_mode_adaptive, R.drawable.ic_mode_adaptive, R.color.drive_selector_accent_neutral, true));
        list.add(new DriveModeDescriptor(570491141,
                "hdc", R.string.drive_selector_mode_hdc, R.drawable.ic_mode_hdc, R.color.drive_selector_accent_offroad));
        list.add(new DriveModeDescriptor(570491148,
                "phev", R.string.drive_selector_mode_phev, R.drawable.ic_mode_phev, R.color.drive_selector_accent_pure));
        list.add(new DriveModeDescriptor(570491152,
                "eco_hev_phev", R.string.drive_selector_mode_eco_hev_phev, R.drawable.ic_mode_eco, R.color.drive_selector_accent_eco, true));
        list.add(new DriveModeDescriptor(570491151,
                "save", R.string.drive_selector_mode_save, R.drawable.ic_mode_save, R.color.drive_selector_accent_neutral));
        list.add(new DriveModeDescriptor(570491140,
                "xc", R.string.drive_selector_mode_xc, R.drawable.ic_mode_xc, R.color.drive_selector_accent_neutral));
        list.add(new DriveModeDescriptor(255,
                "unknown", R.string.drive_selector_mode_unknown, R.drawable.ic_mode_generic, R.color.drive_selector_accent_neutral));
        ALL = Collections.unmodifiableList(list);

        Map<Integer, DriveModeDescriptor> map = new HashMap<>();
        for (DriveModeDescriptor d : list) {
            map.put(d.code, d);
        }
        BY_CODE = Collections.unmodifiableMap(map);
    }

    private DriveModeCatalog() {}

    public static List<DriveModeDescriptor> all() {
        return ALL;
    }

    @Nullable
    public static DriveModeDescriptor byCode(int code) {
        return BY_CODE.get(code);
    }

    public static DriveModeDescriptor byCodeOrGeneric(int code) {
        DriveModeDescriptor d = BY_CODE.get(code);
        if (d != null) return d;
        return new DriveModeDescriptor(code, "code_" + code,
                R.string.drive_selector_mode_unknown, R.drawable.ic_mode_generic, R.color.drive_selector_accent_neutral);
    }

    /** Default order — when user hasn't set anything yet. */
    public static int[] defaultOrder() {
        return new int[] {
                570491138,
                570491137,
                570491139,
                570491145,
                570491155,
        };
    }

}
